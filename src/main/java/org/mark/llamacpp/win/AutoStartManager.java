package org.mark.llamacpp.win;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows 开机自启管理器：通过启动文件夹快捷方式实现。
 */
public class AutoStartManager {

    private static final Logger logger = LoggerFactory.getLogger(AutoStartManager.class);

    private static final String SHORTCUT_NAME = "llama.cpp-hub.lnk";
    private static final String APP_NAME = "llama.cpp-hub";

    private AutoStartManager() {
    }

    /**
     * 获取当前用户启动文件夹路径。
     */
    private static String getStartupFolderPath() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isEmpty()) {
            appData = System.getProperty("user.home") + "\\AppData\\Roaming";
        }
        return appData + "\\Microsoft\\Windows\\Start Menu\\Programs\\Startup";
    }

    /**
     * 获取快捷方式的完整路径。
     */
    private static String getShortcutPath() {
        return getStartupFolderPath() + "\\" + SHORTCUT_NAME;
    }

    /**
     * 判断是否已设置开机自启。
     */
    public static boolean isAutoStartEnabled() {
        String shortcutPath = getShortcutPath();
        File shortcut = new File(shortcutPath);
        return shortcut.exists() && shortcut.isFile();
    }

    /**
     * 启用开机自启：在启动文件夹创建快捷方式。
     *
     * @return true 成功，false 失败
     */
    public static boolean enableAutoStart() {
        if (isAutoStartEnabled()) {
            logger.info("开机自启已启用，无需重复设置");
            return true;
        }

        // 确定启动命令和目标路径
        StartupTarget target = resolveStartupTarget();
        if (target == null) {
            logger.error("无法解析启动目标路径");
            return false;
        }

        String shortcutPath = getShortcutPath();
        String targetPath = target.getTargetPath();
        String workDir = target.getWorkDir();
        String args = target.getArgs();

        logger.info("创建开机自启快捷方式: {} -> {} {}", shortcutPath, targetPath, args);

        // 使用 PowerShell 调用 WScript.Shell 创建快捷方式
        String psScript = String.format(
            "$shell = New-Object -ComObject WScript.Shell; " +
            "$shortcut = $shell.CreateShortcut('%s'); " +
            "$shortcut.TargetPath = '%s'; " +
            "$shortcut.Arguments = '%s'; " +
            "$shortcut.WorkingDirectory = '%s'; " +
            "$shortcut.Description = '%s'; " +
            "$shortcut.Save();",
            escapeSingleQuote(shortcutPath),
            escapeSingleQuote(targetPath),
            escapeSingleQuote(args),
            escapeSingleQuote(workDir),
            escapeSingleQuote(APP_NAME)
        );

        return executePowerShell(psScript);
    }

    /**
     * 禁用开机自启：删除启动文件夹中的快捷方式。
     *
     * @return true 成功，false 失败
     */
    public static boolean disableAutoStart() {
        String shortcutPath = getShortcutPath();
        File shortcut = new File(shortcutPath);

        if (!shortcut.exists()) {
            logger.info("开机自启快捷方式不存在，无需删除");
            return true;
        }

        logger.info("删除开机自启快捷方式: {}", shortcutPath);

        String psScript = String.format(
            "Remove-Item -LiteralPath '%s' -Force -ErrorAction Stop;",
            escapeSingleQuote(shortcutPath)
        );

        boolean success = executePowerShell(psScript);
        if (success) {
            logger.info("开机自启已禁用");
        } else {
            // PowerShell 删除失败时尝试 Java 直接删除
            success = shortcut.delete();
            if (success) {
                logger.info("开机自启已禁用（通过 Java 删除）");
            }
        }
        return success;
    }

    /**
     * 解析启动目标：优先使用 run.bat，其次用 javaw + classpath 直接运行。
     */
    private static StartupTarget resolveStartupTarget() {
        String userDir = System.getProperty("user.dir");

        // 尝试 1: build/run.bat
        Path runBatPath = Paths.get(userDir, "build", "run.bat");
        if (runBatPath.toFile().exists()) {
            logger.info("使用 run.bat 作为启动目标: {}", runBatPath);
            return new StartupTarget("cmd.exe", "/c start \"\" \"" + runBatPath.toString().replace("\\", "\\\\") + "\"", userDir);
        }

        // 尝试 2: 使用 javaw + classpath 直接运行
        Path classesDir = Paths.get(userDir, "build", "classes");
        Path libDir = Paths.get(userDir, "build", "lib");
        if (classesDir.toFile().exists()) {
            String classpath = classesDir.toString().replace("\\", "\\\\") + ";";
            if (libDir.toFile().exists()) {
                classpath += libDir.toString().replace("\\", "\\\\") + "\\*";
            }
            logger.info("使用 classpath 作为启动目标");
            return new StartupTarget("javaw.exe",
                "-Xms512m -Xmx512m -XX:MaxDirectMemorySize=256m -classpath \"" + classpath +
                "\" org.mark.llamacpp.server.LlamaServer",
                userDir);
        }

        logger.error("无法找到有效的启动目标：run.bat 不存在，build/classes 不存在");
        return null;
    }

    /**
     * 执行 PowerShell 命令。
     */
    private static boolean executePowerShell(String script) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-ExecutionPolicy", "Bypass", "-NoProfile", "-Command", script
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("PS: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.error("PowerShell 执行失败，退出码: {}", exitCode);
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.error("执行 PowerShell 命令失败", e);
            return false;
        }
    }

    /**
     * 转义 PowerShell 字符串中的单引号（单引号在 PS 单引号字符串中需转义为 ''）。
     */
    private static String escapeSingleQuote(String s) {
        if (s == null) return "";
        return s.replace("'", "''");
    }

    /**
     * 启动目标信息。
     */
    private static class StartupTarget {
        private final String targetPath;
        private final String args;
        private final String workDir;

        StartupTarget(String targetPath, String args, String workDir) {
            this.targetPath = targetPath;
            this.args = args;
            this.workDir = workDir;
        }

        String getTargetPath() {
            return targetPath;
        }

        String getArgs() {
            return args;
        }

        String getWorkDir() {
            return workDir;
        }
    }
}
