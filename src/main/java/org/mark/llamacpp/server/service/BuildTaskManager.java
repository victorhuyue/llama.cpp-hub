package org.mark.llamacpp.server.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.tools.CommandLineRunner;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.websocket.WebSocketManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

public class BuildTaskManager {

    private static final Logger logger = LoggerFactory.getLogger(BuildTaskManager.class);
    private static final WebSocketManager wsManager = WebSocketManager.getInstance();
    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private static final int MAX_OUTPUT_SIZE = 1024 * 1024;
    private static final int DEFAULT_TIMEOUT_MINUTES = 60;
    private static final long PERSIST_INTERVAL_MS = 1000;

    private static final String TASKS_DIR = "cache/build-tasks";
    private static final String TASK_FILE_SUFFIX = ".json";

    private static volatile BuildTaskManager instance;

    private final AtomicBoolean building = new AtomicBoolean(false);
    private final Map<String, BuildTask> tasks = new ConcurrentHashMap<>();

    private BuildTaskManager() {
        loadTasks();
    }

    public static BuildTaskManager getInstance() {
        if (instance == null) {
            synchronized (BuildTaskManager.class) {
                if (instance == null) {
                    instance = new BuildTaskManager();
                }
            }
        }
        return instance;
    }

    public BuildTask submitTask(String sourceArchive, String sourceDir, String outputDirName,
            String cmakeCommand, String buildCommand) {
        if (!building.compareAndSet(false, true)) {
            return null;
        }

        BuildTask task = new BuildTask();
        task.taskId = java.util.UUID.randomUUID().toString().substring(0, 8);
        task.sourceArchive = sourceArchive;
        task.sourceDir = sourceDir;
        task.outputDirName = outputDirName;
        task.cmakeCommand = cmakeCommand;
        task.buildCommand = buildCommand;
        task.status = "PENDING";
        task.output = "";
        task.startTime = System.currentTimeMillis();
        task.exitCode = 0;

        tasks.put(task.taskId, task);
        persistTask(task);

        executor.execute(() -> executeTask(task));

        return task;
    }

    public BuildTask getStatus(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return tasks.values().stream().findFirst().orElse(null);
        }
        return tasks.get(taskId);
    }

    public List<BuildTask> getHistory() {
        List<BuildTask> list = new ArrayList<>(tasks.values());
        list.sort((a, b) -> Long.compare(b.startTime, a.startTime));
        return list;
    }

    public boolean cancelTask(String taskId) {
        BuildTask task;
        if (taskId == null || taskId.isEmpty()) {
            task = tasks.values().stream()
                    .filter(t -> "RUNNING".equals(t.status)).findFirst().orElse(null);
        } else {
            task = tasks.get(taskId);
        }
        if (task == null) {
            return false;
        }
        if (!"RUNNING".equals(task.status) && !"PENDING".equals(task.status)) {
            return false;
        }
        task.cancelled.set(true);
        if (task.process != null) {
            task.process.destroy();
        }
        return true;
    }

    public boolean extractArchive(String archivePath, String targetDir) {
        File archive = new File(archivePath);
        if (!archive.exists() || !archivePath.toLowerCase().endsWith(".zip")) {
            return false;
        }

        try {
            Path target = Paths.get(targetDir);
            Files.createDirectories(target);
            extractZip(archive, target);
            return true;
        } catch (Exception e) {
            logger.error("解压失败: {}", e.getMessage(), e);
            return false;
        }
    }

    private void extractZip(File archive, Path target) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archive.toPath()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path dest = target.resolve(entry.getName()).normalize();
                if (!dest.startsWith(target)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private void executeTask(BuildTask task) {
        try {
            Path llamacppDir = Paths.get(LlamaServer.getDefaultLlamaCppPath()).toAbsolutePath().normalize();
            Path outputDir = llamacppDir.resolve(task.outputDirName).toAbsolutePath().normalize();
            if (!outputDir.startsWith(llamacppDir)) {
                task.status = "FAILED";
                appendOutput(task, "输出目录不合法");
                task.endTime = System.currentTimeMillis();
                task.exitCode = -1;
                persistTask(task);
                broadcastStatus(task, "FAILED", -1, null);
                return;
            }

            Path sourceDir = Paths.get(task.sourceDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(sourceDir)) {
                task.status = "FAILED";
                appendOutput(task, "源码目录不存在: " + task.sourceDir);
                task.endTime = System.currentTimeMillis();
                task.exitCode = -1;
                persistTask(task);
                broadcastStatus(task, "FAILED", -1, null);
                return;
            }

            Path buildDir = sourceDir.resolve("build");

            task.outputDir = outputDir.toString();
            task.status = "RUNNING";
            persistTask(task);
            broadcastStatus(task, "RUNNING", 0, null);

            if (!executePhase(task, "cmake", sourceDir, buildDir, outputDir, task.cmakeCommand, DEFAULT_TIMEOUT_MINUTES)) {
                return;
            }
            if (!executePhase(task, "build", sourceDir, buildDir, outputDir, task.buildCommand, DEFAULT_TIMEOUT_MINUTES)) {
                return;
            }
            boolean copied = copyBuildArtifacts(task, sourceDir, outputDir);
            if (!copied) {
                appendOutput(task, "[警告] 未找到编译产物，请检查构建命令或源码结构");
            }

            task.status = "COMPLETED";
            task.endTime = System.currentTimeMillis();
            task.exitCode = 0;
            persistTask(task);
            broadcastStatus(task, "COMPLETED", 0, outputDir.toString());

        } catch (Exception e) {
            logger.error("编译任务异常: {}", e.getMessage(), e);
            task.status = "FAILED";
            appendOutput(task, "[异常] " + e.getMessage());
            task.endTime = System.currentTimeMillis();
            task.exitCode = -1;
            persistTask(task);
            broadcastStatus(task, "FAILED", -1, null);
        } finally {
            building.set(false);
        }
    }

    private boolean executePhase(BuildTask task, String phase, Path sourceDir, Path buildDir, Path outputDir,
            String command, int timeoutMinutes) {
        if (command == null || command.trim().isEmpty()) {
            return true;
        }

        command = command.replace("{{BUILD_DIR}}", buildDir.toString())
                         .replace("{{OUTPUT_DIR}}", outputDir.toString());
        broadcastOutput(task, phase, "[" + phase.toUpperCase() + "] 执行: " + command);

        try {
            List<String> cmdArray;
            if (needsShell(command)) {
                if (isWindows()) {
                    cmdArray = java.util.Arrays.asList("cmd.exe", "/s", "/c", command);
                } else {
                    cmdArray = java.util.Arrays.asList("bash", "-c", command);
                }
            } else {
                cmdArray = CommandLineRunner.splitCommandLineArgs(command);
            }
            ProcessBuilder pb = new ProcessBuilder(cmdArray);
            pb.directory(sourceDir.toFile());
            pb.redirectErrorStream(true);

            Map<String, String> env = pb.environment();
            String existingPath = env.get("PATH");
            String javaHome = System.getProperty("java.home", "");
            if (javaHome.endsWith("bin") || javaHome.endsWith("bin" + File.separator)) {
                String jdkHome = new File(javaHome, "..").getAbsolutePath();
                String mvnBin = jdkHome + File.separator + "bin";
                env.put("PATH", mvnBin + File.pathSeparator + existingPath);
            }

            Process process = pb.start();
            task.process = process;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            Thread readerThread = Thread.ofVirtual().start(() -> {
                try {
                    String line;
                    while (!task.cancelled.get() && (line = reader.readLine()) != null) {
                        broadcastOutput(task, phase, line);
                        appendOutput(task, line);
                    }
                } catch (IOException e) {
                    if (!task.cancelled.get()) {
                        broadcastOutput(task, phase, "[错误] 读取输出失败: " + e.getMessage());
                    }
                }
            });

            boolean finished = false;
            try {
                finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                broadcastOutput(task, phase, "[中断] 编译被中断");
            }

            try {
                readerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (task.cancelled.get()) {
                task.status = "CANCELLED";
                task.endTime = System.currentTimeMillis();
                persistTask(task);
                broadcastStatus(task, "CANCELLED", -1, null);
                return false;
            }

            if (!finished) {
                process.destroyForcibly();
                task.status = "FAILED";
                appendOutput(task, "\n[超时] 编译超时 (" + timeoutMinutes + "分钟)");
                task.endTime = System.currentTimeMillis();
                task.exitCode = -2;
                persistTask(task);
                broadcastStatus(task, "FAILED", -2, null);
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                task.status = "FAILED";
                appendOutput(task, "\n[" + phase.toUpperCase() + "] 退出码: " + exitCode);
                task.endTime = System.currentTimeMillis();
                task.exitCode = exitCode;
                persistTask(task);
                broadcastStatus(task, "FAILED", exitCode, null);
                return false;
            }

            broadcastOutput(task, phase, "[" + phase.toUpperCase() + "] 完成");
            return true;

        } catch (IOException e) {
            broadcastOutput(task, phase, "[错误] 执行失败: " + e.getMessage());
            task.status = "FAILED";
            appendOutput(task, "\n[错误] " + e.getMessage());
            task.endTime = System.currentTimeMillis();
            task.exitCode = -1;
            persistTask(task);
            broadcastStatus(task, "FAILED", -1, null);
            return false;
        }
    }

    private boolean copyBuildArtifacts(BuildTask task, Path sourceDir, Path outputDir) {
        try {
            Files.createDirectories(outputDir);

            Path releaseDir = sourceDir.resolve("build").resolve("bin").resolve("release");
            if (!Files.isDirectory(releaseDir)) {
                releaseDir = sourceDir.resolve("build").resolve("bin").resolve("Release");
            }
            if (!Files.isDirectory(releaseDir)) {
                releaseDir = sourceDir.resolve("build").resolve("Release");
            }
            if (!Files.isDirectory(releaseDir)) {
                releaseDir = sourceDir.resolve("build").resolve("bin");
            }
            if (!Files.isDirectory(releaseDir)) {
                releaseDir = sourceDir.resolve("build");
            }

            if (!Files.isDirectory(releaseDir)) {
                return false;
            }

            final Path srcDir = releaseDir;
            final AtomicBoolean found = new AtomicBoolean(false);

            try (Stream<Path> files = Files.list(srcDir)) {
                files.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        Path target = outputDir.resolve(file.getFileName());
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                        found.set(true);
                        appendOutput(task, "[安装] 复制: " + file.getFileName());
                    } catch (IOException e) {
                        appendOutput(task, "[错误] 复制失败: " + file.getFileName() + " - " + e.getMessage());
                    }
                });
            }

            return found.get();
        } catch (Exception e) {
            logger.error("复制编译产物失败", e);
            appendOutput(task, "[错误] 复制编译产物失败: " + e.getMessage());
            return false;
        }
    }

    private void appendOutput(BuildTask task, String line) {
        synchronized (task.outputLock) {
            if (task.output == null) {
                task.output = "";
            }
            if (task.output.length() < MAX_OUTPUT_SIZE) {
                task.output += line + "\n";
                if (task.output.length() > MAX_OUTPUT_SIZE) {
                    task.output = task.output.substring(task.output.length() - MAX_OUTPUT_SIZE);
                }
            }
        }
        long now = System.currentTimeMillis();
        if (now - task.lastPersistTime > PERSIST_INTERVAL_MS) {
            task.lastPersistTime = now;
            persistTask(task);
        }
    }

    private void broadcastOutput(BuildTask task, String phase, String line) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "build_output");
        msg.addProperty("taskId", task.taskId);
        msg.addProperty("phase", phase);
        msg.addProperty("line", line);
        msg.addProperty("timestamp", System.currentTimeMillis());
        wsManager.broadcast(JsonUtil.toJson(msg));
    }

    private void broadcastStatus(BuildTask task, String status, int exitCode, String outputDir) {
        task.status = status;
        task.exitCode = exitCode;

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "build_status");
        msg.addProperty("taskId", task.taskId);
        msg.addProperty("status", status);
        msg.addProperty("exitCode", exitCode);
        if (outputDir != null) {
            msg.addProperty("outputDir", outputDir);
        }
        msg.addProperty("timestamp", System.currentTimeMillis());
        wsManager.broadcast(JsonUtil.toJson(msg));
    }

    private void persistTask(BuildTask task) {
        synchronized (task.outputLock) {
            try {
                Path dir = Paths.get(TASKS_DIR).toAbsolutePath().normalize();
                Files.createDirectories(dir);
                Path target = dir.resolve(task.taskId + TASK_FILE_SUFFIX);
                Path temp = dir.resolve(task.taskId + TASK_FILE_SUFFIX + ".tmp");
                String json = JsonUtil.toJson(task);
                Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                logger.error("持久化编译任务失败: {}", e.getMessage(), e);
            }
        }
    }

    private void loadTasks() {
        try {
            Path dir = Paths.get(TASKS_DIR).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(TASK_FILE_SUFFIX))
                     .sorted(Comparator.comparing(Path::getFileName))
                     .forEach(this::loadTaskFile);
            }
        } catch (Exception e) {
            logger.error("加载编译任务失败: {}", e.getMessage(), e);
        }
    }

    private void loadTaskFile(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            BuildTask task = JsonUtil.fromJson(json, BuildTask.class);
            if (task == null || task.taskId == null) {
                return;
            }
            task.ensureTransientFields();
            if ("RUNNING".equals(task.status) || "PENDING".equals(task.status)) {
                task.status = "FAILED";
                task.exitCode = -1;
                task.output = (task.output == null ? "" : task.output)
                        + "\n[系统] 应用重启，编译任务中断\n";
                task.endTime = System.currentTimeMillis();
                persistTask(task);
            }
            tasks.put(task.taskId, task);
        } catch (Exception e) {
            logger.error("加载编译任务文件失败 {}: {}", file, e.getMessage(), e);
        }
    }

    private static boolean needsShell(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        String trimmed = command.trim();
        if (trimmed.startsWith("export ") || trimmed.contains(" export ")) {
            return true;
        }
        return trimmed.contains("&&") || trimmed.contains("||") || trimmed.contains(";")
                || trimmed.contains("|") || trimmed.contains("$") || trimmed.contains("`");
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name");
        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        return os.contains("win");
    }

    public static class BuildTask {
        public String taskId;
        public String sourceArchive;
        public String sourceDir;
        public String outputDirName;
        public String outputDir;
        public String cmakeCommand;
        public String buildCommand;
        public String status;
        public String output;
        public long startTime;
        public long endTime;
        public int exitCode;

        public transient Object outputLock = new Object();
        public transient Process process;
        public transient AtomicBoolean cancelled = new AtomicBoolean(false);
        public transient long lastPersistTime;

        public BuildTask() {
            this.outputLock = new Object();
            this.cancelled = new AtomicBoolean(false);
        }

        public void ensureTransientFields() {
            if (this.outputLock == null) {
                this.outputLock = new Object();
            }
            if (this.cancelled == null) {
                this.cancelled = new AtomicBoolean(false);
            }
        }
    }
}
