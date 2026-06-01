package org.mark.llamacpp.server.tools;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import org.mark.llamacpp.server.LlamaServer;


public class GGufMemEstimator {

    public record MemoryBreakdown(
            List<DeviceMemory> devices,
            String architecture,
            long contextLength,
            String error) {
        public boolean hasError() {
            return error != null && !error.isEmpty();
        }

        public double totalModelMiB() {
            double sum = 0;
            if (devices != null) {
                for (DeviceMemory d : devices) sum += d.modelMiB();
            }
            return sum;
        }

        public double totalContextMiB() {
            double sum = 0;
            if (devices != null) {
                for (DeviceMemory d : devices) sum += d.contextMiB();
            }
            return sum;
        }

        public double totalComputeMiB() {
            double sum = 0;
            if (devices != null) {
                for (DeviceMemory d : devices) sum += d.computeMiB();
            }
            return sum;
        }

        public double totalMiB() {
            return totalModelMiB() + totalContextMiB() + totalComputeMiB();
        }
    }

    public record DeviceMemory(
            String device,
            long modelMiB,
            long contextMiB,
            long computeMiB) {
        public long totalMiB() {
            return modelMiB + contextMiB + computeMiB;
        }
    }

    // ---- Singleton + init ----

    private static final GGufMemEstimator INSTANCE = new GGufMemEstimator();
    private static final String TOOLS_CACHE_DIR = "cache/tools/gguf-mem";
    private static final String RESOURCE_ROOT = "/tools/gguf-mem";

    volatile boolean initialized = false;
    String exePath;
    boolean available = false;
    String initError;

    private GGufMemEstimator() {
    }

    public static GGufMemEstimator getInstance() {
        return INSTANCE;
    }

    /**
     * 检测平台并从 classpath 提取 gguf-mem 二进制及依赖到 cache/tools/gguf-mem/<platform>/。
     * 幂等，可重复调用。
     */
    public synchronized String init() {
        if (initialized)
            return initError;
        initialized = true;

        try {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);

            String platform;
            if (os.contains("win")) {
                platform = "win-x64";
            } else if (os.contains("linux")) {
                platform = "linux-x64";
            } else {
                throw new UnsupportedOperationException("Unsupported OS: " + os);
            }

            String dirName = "gguf-mem-" + platform;
            String exeName = platform.equals("win-x64") ? "gguf-mem.exe" : "gguf-mem";

            Path cacheDir = LlamaServer.getCachePath().resolve(TOOLS_CACHE_DIR).resolve(dirName);
            Files.createDirectories(cacheDir);

            String resourceRoot = RESOURCE_ROOT + "/" + platform + "/";

            // Extract all files from the resource directory
            extractResourceDir(resourceRoot, cacheDir);

            exePath = cacheDir.resolve(exeName).toAbsolutePath().toString();
            File exeFile = new File(exePath);
            if (!exeFile.exists()) {
                throw new FileNotFoundException("gguf-mem binary not extracted: " + exePath);
            }

            // Set executable permission
            if (!exeFile.setExecutable(true, false) && !exeFile.canExecute()) {
                if (!os.contains("win")) {
                    try {
                        new ProcessBuilder("chmod", "+x", exePath).start().waitFor();
                    } catch (Exception ignored) {
                    }
                }
                if (!exeFile.canExecute()) {
                    throw new IOException("Failed to set executable permission: " + exePath);
                }
            }

            available = true;
            initError = null;
            System.err.println("[GGufMemEstimator] Initialized: " + exePath);
        } catch (Exception e) {
            initError = "Failed to initialize GGufMemEstimator: " + e.getMessage();
            available = false;
            System.err.println("[GGufMemEstimator] " + initError);
        }

        return initError;
    }

    public boolean isAvailable() {
        if (!initialized) {
            init();
        }
        return available;
    }

   // ---- Public API: estimate from combined command string ----

    /**
     * 从完整启动命令字符串估算模型内存占用。
     * <p>
     * 前端传入完整启动命令（如 "--ctx-size 4096 --flash-attn on --parallel 2"），
     * 内部通过 ParamTool.splitCmdArgs 解析，提取已知参数并映射为 gguf-mem 参数。
     * </p>
     *
     * @param modelPathOrUrl 模型文件路径（本地 .gguf 绝对路径）或 HuggingFace URL
     * @param combinedCmd    完整启动命令字符串，可为空或 null（使用默认参数）
     * @return 内存估算结果
     */
    public static MemoryBreakdown estimateFromCmd(String modelPathOrUrl, String combinedCmd) {
        Objects.requireNonNull(modelPathOrUrl, "modelPathOrUrl");

        GGufMemEstimator inst = getInstance();
        if (!inst.isAvailable()) {
            return new MemoryBreakdown(null, null, 0,
                    inst.initError != null ? inst.initError : "gguf-mem not available");
        }

        try {
            // Step 1: Get the model/meta file path
            String metaPath = ensureMetaFile(modelPathOrUrl);
            if (metaPath == null) {
                return new MemoryBreakdown(null, null, 0, "Failed to resolve model: " + modelPathOrUrl);
            }

            // Step 2: Parse combined command and extract known parameters
            Map<String, String> params = extractParams(combinedCmd);

            // Step 3: Build gguf-mem command from extracted parameters
            List<String> cmd = buildGgufMemCommand(inst.exePath, metaPath, params);

            // Step 4: Execute
            CommandLineRunner.CommandResult result = CommandLineRunner.execute(cmd.toArray(new String[0]), 30);

            // gguf-mem outputs to stderr; merge stdout + stderr for parsing
            String combined = new StringBuilder()
                    .append(result.getOutput() != null ? result.getOutput() : "")
                    .append(result.getError() != null ? result.getError() : "")
                    .toString();

            if (result.getExitCode() == null || result.getExitCode() != 0) {
                String errMsg = result.getError() != null ? result.getError() : "Unknown error";
                return new MemoryBreakdown(null, null, 0,
                        "gguf-mem failed (exit code: " + result.getExitCode() + "): " + errMsg);
            }

            // Step 5: Parse output
            return parseOutput(combined);

        } catch (Exception e) {
            return new MemoryBreakdown(null, null, 0, "Estimation failed: " + e.getMessage());
        }
    }

    // ---- Legacy typed-parameter API (delegates to estimateFromCmd) ----

    /**
     * 估算模型内存占用（类型化参数版，内部转换为命令字符串后调用 estimateFromCmd）。
     */
    public static MemoryBreakdown estimate(String modelPathOrUrl, int ctxSize,
            String kvCacheTypeK, String kvCacheTypeV,
            boolean flashAttention, int batchSize, int ubatchSize,
            int parallel, int nGpuLayers, boolean swaFull, String tensorSplit) {
        List<String> parts = new ArrayList<>();
        parts.add("--ctx-size");
        parts.add(String.valueOf(ctxSize));
        if (kvCacheTypeK != null && !kvCacheTypeK.isEmpty()) {
            parts.add("--cache-type-k");
            parts.add(kvCacheTypeK);
        }
        if (kvCacheTypeV != null && !kvCacheTypeV.isEmpty()) {
            parts.add("--cache-type-v");
            parts.add(kvCacheTypeV);
        }
        if (flashAttention) {
            parts.add("--flash-attn");
            parts.add("on");
        }
        if (batchSize > 0) {
            parts.add("--batch-size");
            parts.add(String.valueOf(batchSize));
        }
        if (ubatchSize > 0) {
            parts.add("--ubatch-size");
            parts.add(String.valueOf(ubatchSize));
        }
        if (parallel > 1) {
            parts.add("--parallel");
            parts.add(String.valueOf(parallel));
        }
        if (nGpuLayers > 0) {
            parts.add("--ngl");
            parts.add(String.valueOf(nGpuLayers));
        }
        if (swaFull) {
            parts.add("--swa-full");
        }
        if (tensorSplit != null && !tensorSplit.isEmpty()) {
            parts.add("--tensor-split");
            parts.add(tensorSplit);
        }
        return estimateFromCmd(modelPathOrUrl, String.join(" ", parts));
    }

    public static MemoryBreakdown estimate(String modelPathOrUrl, int ctxSize) {
        return estimateFromCmd(modelPathOrUrl, "--ctx-size " + ctxSize);
    }

    public static MemoryBreakdown estimate(String modelPathOrUrl, int ctxSize, int nGpuLayers) {
        return estimateFromCmd(modelPathOrUrl,
                "--ctx-size " + ctxSize + (nGpuLayers > 0 ? " --ngl " + nGpuLayers : ""));
    }

    // ---- Internal helpers ----

    /**
     * Ensures a .meta.gguf file exists for the given model URL or path.
     * For URLs, downloads header via GGufMetaDataExtractor.
     * For local files, returns the path directly.
     */
    static String ensureMetaFile(String modelPathOrUrl) throws Exception {
        boolean isUrl = modelPathOrUrl.startsWith("http://") || modelPathOrUrl.startsWith("https://");

        Path metaDir = LlamaServer.getCachePath().resolve("meta");
        if (!Files.exists(metaDir)) {
            Files.createDirectories(metaDir);
        }

        if (isUrl) {
            // Download header and extract metadata
            byte[] headerData = GGufMetaDataExtractor.downloadHeader(modelPathOrUrl);
            if (headerData == null) {
                return null;
            }

            String base = modelPathOrUrl.substring(modelPathOrUrl.lastIndexOf('/') + 1);
            int qm = base.indexOf('?');
            if (qm >= 0) base = base.substring(0, qm);
            String outPath = metaDir.resolve(base + ".meta.gguf").toString();

            // If already exists, skip
            if (Files.exists(Paths.get(outPath))) {
                return outPath;
            }

            GGufMetaDataExtractor.writeMetaFile(headerData, outPath);
            return outPath;
        } else {
            // Local file - use directly
            File f = new File(modelPathOrUrl);
            if (!f.exists()) {
                throw new FileNotFoundException("Model file not found: " + modelPathOrUrl);
            }
            return f.getAbsolutePath();
        }
    }

    // ---- Parameter extraction from combined command string ----

    /**
     * Known parameters to extract from combined command.
     * Maps frontend long-form params (--ctx-size) to gguf-mem short-form (-c).
     */
    static final Map<String, String> PARAM_MAP = new LinkedHashMap<>() {{
        put("--ctx-size", "-c");
        put("--cache-type-k", "-ctk");
        put("--cache-type-v", "-ctv");
        put("--flash-attn", "-fa");
        put("--batch-size", "-b");
        put("--ubatch-size", "-ub");
        put("--parallel", "-np");
        put("--ngl", "-ngl");
        put("--n-gpu-layers", "-ngl");
        put("--tensor-split", "-ts");
        put("--n-tensor-gpu", "-ts");
        put("--device", "--device");
        put("--main-gpu", "--main-gpu");
    }};
    static final Set<String> BOOL_FLAGS = Set.of("--swa-full");

    /**
     * Parse combined command string, extract known parameters.
     * Returns a map of frontend param name -> value (or flag name itself for boolean flags).
     */
    static Map<String, String> extractParams(String combinedCmd) {
        Map<String, String> result = new LinkedHashMap<>();
        if (combinedCmd == null || combinedCmd.isBlank()) {
            return result;
        }

        List<String> tokens = ParamTool.splitCmdArgs(combinedCmd);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            // Check if this token matches a known parameter
            if (PARAM_MAP.containsKey(token)) {
                if (i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("-")) {
                    result.put(token, tokens.get(i + 1));
                    i++; // skip value
                }
            } else if (BOOL_FLAGS.contains(token)) {
                result.put(token, token); // boolean flag, value = key
            }
        }
        return result;
    }

    /**
     * Build gguf-mem command from extracted parameters.
     */
    static List<String> buildGgufMemCommand(String exePath, String modelPath, Map<String, String> params) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exePath);
        cmd.add("-m");
        cmd.add(modelPath);

        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (BOOL_FLAGS.contains(key)) {
                // Boolean flag like --swa-full
                cmd.add(key);
                continue;
            }

            String ggufArg = PARAM_MAP.get(key);
            if (ggufArg == null) {
                continue; // unknown param, skip
            }

            // Special handling for --device -> -ngl mapping
            if ("--device".equals(key)) {
                int ngl = parseDeviceToNgl(value);
                cmd.add("-ngl");
                cmd.add(String.valueOf(ngl));
                continue;
            }

            // Special handling for --flash-attn: pass value as-is (on/off/auto)
            if ("--flash-attn".equals(key)) {
                cmd.add("-fa");
                cmd.add(value);
                continue;
            }

            // Special handling for --main-gpu: not needed for gguf-mem memory estimation
            if ("--main-gpu".equals(key)) {
                continue;
            }

            // Default: pass through with mapped argument name
            cmd.add(ggufArg);
            cmd.add(value);
        }

        return cmd;
    }

    /**
     * Map --device value to -ngl (GPU layer offload count).
     * "cuda", "cuda0", "all" -> 999 (full offload)
     * "host", "cpu" -> 0 (no offload)
     */
    static int parseDeviceToNgl(String device) {
        if (device == null || device.isBlank()) {
            return 0;
        }
        String lower = device.toLowerCase(Locale.ROOT).trim();
        if (lower.equals("host") || lower.equals("cpu")) {
            return 0;
        }
        // cuda, cuda0, cuda1, all, etc. -> full offload
        return 999;
    }

    /**
     * Parses gguf-mem output:
     * Each line: &lt;device_name&gt; &lt;model_mib&gt; &lt;context_mib&gt; &lt;compute_mib&gt;
     * Also looks for architecture info in stderr-like lines.
     */
    static MemoryBreakdown parseOutput(String output) {
        List<DeviceMemory> devices = new ArrayList<>();
        String architecture = null;
        long contextLength = 0;

        if (output == null || output.isBlank()) {
            return new MemoryBreakdown(devices, architecture, contextLength, "Empty output from gguf-mem");
        }

        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty())
                continue;

            // Try to parse as device memory line
            DeviceMemory dm = tryParseDeviceLine(trimmed);
            if (dm != null) {
                devices.add(dm);
                continue;
            }

            // Try to extract architecture info
            if (trimmed.toLowerCase(Locale.ROOT).contains("arch")) {
                int eqIdx = trimmed.indexOf('=');
                if (eqIdx > 0 && eqIdx + 1 < trimmed.length()) {
                    architecture = trimmed.substring(eqIdx + 1).trim();
                }
            }
        }

        if (devices.isEmpty()) {
            return new MemoryBreakdown(devices, architecture, contextLength,
                    "No device memory lines parsed. Raw output: " + output);
        }

        return new MemoryBreakdown(devices, architecture, contextLength, null);
    }

    static DeviceMemory tryParseDeviceLine(String line) {
        // Format: <device> <model_mib> <context_mib> <compute_mib>
        // Device name may contain digits (e.g., "CUDA0", "Host")
        String[] parts = line.split("\\s+");
        if (parts.length < 4)
            return null;

        String device = parts[0];
        try {
            long modelMiB = Long.parseLong(parts[1]);
            long contextMiB = Long.parseLong(parts[2]);
            long computeMiB = Long.parseLong(parts[3]);
            return new DeviceMemory(device, modelMiB, contextMiB, computeMiB);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- Resource extraction ----

    static final String[] WIN_FILES = {
        "gguf-mem.exe", "llama.dll", "llama-common.dll",
        "llama-fit-params-impl.dll", "llama-fit-params.exe",
        "ggml.dll", "ggml-cpu.dll", "ggml-base.dll"
    };
    static final String[] LINUX_FILES = {
        "gguf-mem", "gguf-extract-meta",
        "libllama.so", "libllama.so.0", "libllama.so.0.0.0",
        "libllama-common.so", "libllama-common.so.0", "libllama-common.so.0.0.0",
        "libggml.so", "libggml.so.0", "libggml.so.0.13.1",
        "libggml-cpu.so", "libggml-cpu.so.0", "libggml-cpu.so.0.13.1",
        "libggml-base.so", "libggml-base.so.0", "libggml-base.so.0.13.1"
    };

    static void extractResourceDir(String resourceRoot, Path destDir) throws IOException {
        URL dirUrl = GGufMemEstimator.class.getResource(resourceRoot);
        if (dirUrl == null) {
            throw new IOException("Resource directory not found: " + resourceRoot);
        }

        String protocol = dirUrl.getProtocol();

        if ("file".equals(protocol)) {
            // File system - copy directory contents directly
            Path sourcePath;
            try {
                sourcePath = Paths.get(dirUrl.toURI());
            } catch (java.net.URISyntaxException e) {
                throw new IOException("Invalid resource URL: " + dirUrl, e);
            }
            if (Files.isDirectory(sourcePath)) {
                try (Stream<Path> stream = Files.list(sourcePath)) {
                    stream.forEach(src -> {
                        try {
                            Path dest = destDir.resolve(src.getFileName());
                            if (Files.exists(dest))
                                return;
                            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            // skip individual file errors
                        }
                    });
                }
            }
        } else {
            // JAR or other protocol - enumerate known files
            String[] files = resourceRoot.contains("win") ? WIN_FILES : LINUX_FILES;
            for (String fileName : files) {
                String resPath = resourceRoot + fileName;
                copyFromClasspath(resPath, destDir.resolve(fileName));
            }
        }
    }

    static void copyFromClasspath(String resource, Path dest) {
        if (Files.exists(dest))
            return;
        try (InputStream is = GGufMemEstimator.class.getResourceAsStream(resource)) {
            if (is == null)
                return;
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // non-fatal; skip missing optional files
        }
    }

    // ---- Main for testing ----

    public static void main(String[] args) throws Exception {
        GGufMemEstimator inst = getInstance();
        String err = inst.init();
        if (err != null) {
            System.err.println("Init failed: " + err);
            return;
        }

        String model = "https://huggingface.co/ReadyArt/Melody1437-27B-v0.3-GGUF/resolve/main/Melody1437-27B-v0.3-Q6_K.gguf";
        String cmd = "--ctx-size 4096 --flash-attn on --device cuda";

        if (args.length >= 1) {
            model = args[0];
        }
        if (args.length >= 2) {
            cmd = args[1];
        }

        // Test parameter extraction
        Map<String, String> params = extractParams(cmd);
        System.err.println("Extracted params: " + params);

        // Build gguf-mem command
        List<String> builtCmd = buildGgufMemCommand(inst.exePath, model, params);
        System.err.println("Built command: " + builtCmd);

        // Estimate
        System.err.println("Estimating memory for: " + model + " cmd=" + cmd);
        MemoryBreakdown result = estimateFromCmd(model, cmd);

        if (result.hasError()) {
            System.err.println("Error: " + result.error());
        } else {
            System.out.println("Architecture: " + result.architecture());
            System.out.println("Total Model: " + result.totalModelMiB() + " MiB");
            System.out.println("Total Context: " + result.totalContextMiB() + " MiB");
            System.out.println("Total Compute: " + result.totalComputeMiB() + " MiB");
            System.out.println("Total: " + result.totalMiB() + " MiB");
            System.out.println("Devices:");
            for (DeviceMemory dm : result.devices()) {
                System.out.printf("  %s: model=%d context=%d compute=%d total=%d MiB%n",
                        dm.device(), dm.modelMiB(), dm.contextMiB(), dm.computeMiB(), dm.totalMiB());
            }
        }
    }
}
