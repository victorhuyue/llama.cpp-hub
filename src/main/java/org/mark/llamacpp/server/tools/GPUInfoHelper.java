package org.mark.llamacpp.server.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 	这个东西用来执行gpu-info。
 */
public class GPUInfoHelper {

	private static final Logger logger = LoggerFactory.getLogger(GPUInfoHelper.class);
	private static final GPUInfoHelper INSTANCE = new GPUInfoHelper();

	private volatile boolean initialized = false;
	private String exePath;
	private boolean available = false;
	private String initError;

	private GPUInfoHelper() {
	}

	public static GPUInfoHelper getInstance() {
		return INSTANCE;
	}

	public synchronized String init() {
		if (initialized)
			return initError;
		initialized = true;

		try {
			String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
			String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

			if (!arch.equals("amd64") && !arch.equals("x86_64")) {
				throw new UnsupportedOperationException("Unsupported architecture: " + arch + " (only x64 supported)");
			}

			String platform;
			if (os.contains("win")) {
				platform = "windows";
			} else if (os.contains("linux")) {
				platform = "linux";
			} else {
				throw new UnsupportedOperationException("Unsupported OS: " + os);
			}

			String exeName = platform.equals("windows") ? "gpu-info-x64.exe" : "gpu-info-x64";
			String resourcePath = "/tools/easy-tools/" + platform + "/" + exeName;

			URL resource = GPUInfoHelper.class.getResource(resourcePath);
			if (resource == null) {
				throw new IOException("gpu-info binary not found in resources: " + resourcePath);
			}

			exePath = new File(resource.toURI()).getAbsolutePath();
			File exeFile = new File(exePath);
			if (!exeFile.exists()) {
				throw new IOException("gpu-info binary not found: " + exePath);
			}
			if (!exeFile.canExecute()) {
				if (platform.equals("linux")) {
					new ProcessBuilder("chmod", "+x", exePath).start().waitFor();
				}
				if (!exeFile.canExecute()) {
					throw new IOException("Failed to set executable permission: " + exePath);
				}
			}

			available = true;
			return null;
		} catch (Exception e) {
			available = false;
			initError = e.getMessage();
			return initError;
		}
	}

	public boolean isAvailable() {
		if (!initialized)
			init();
		return available;
	}

	public String getInitError() {
		return initError;
	}

	public JsonObject getInfo() {
		try {
			if (!isAvailable()) {
				return null;
			}

			String output = execJson();
			JsonObject root = JsonUtil.fromJson(output.trim(), JsonObject.class);
			if (root == null) {
				return null;
			}

			filterDevices(root);

			return root;
		} catch (Exception e) {
			return null;
		}
	}

	private static void filterDevices(JsonObject root) {
		JsonArray devicesArr = root.getAsJsonArray("devices");
		if (devicesArr == null)
			return;

		JsonArray filtered = new JsonArray();
		for (int i = 0; i < devicesArr.size(); i++) {
			JsonObject dev = devicesArr.get(i).getAsJsonObject();
			String name = JsonUtil.getJsonString(dev, "name", "");
			if (name.contains("llvmpipe"))
				continue;
			String type = JsonUtil.getJsonString(dev, "type", "");
			if ("CPU".equals(type))
				continue;

			removeField(dev, "vendor_id");
			removeField(dev, "device_id");

			JsonObject mem = dev.getAsJsonObject("memory");
			if (mem != null) {
				removeField(mem, "shared_ram_bytes");
				removeField(mem, "heaps");
			}

			removeField(dev, "vulkan");

			filtered.add(dev);
		}

		root.remove("devices");
		root.add("devices", filtered);
		root.addProperty("device_count", filtered.size());
	}

	private static void removeField(JsonObject obj, String key) {
		if (obj != null) {
			obj.remove(key);
		}
	}

	private String execJson() throws IOException {
		ProcessBuilder pb = new ProcessBuilder(exePath, "--json");
		pb.redirectErrorStream(true);
		Process process = pb.start();

		StringBuilder output = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append('\n');
			}
		}

		try {
			int exitCode = process.waitFor();
			if (exitCode != 0) {
				throw new IOException("gpu-info exited with code " + exitCode + ": " + output);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("gpu-info execution interrupted", e);
		}

		return output.toString();
	}

	/**
	 * 执行 gpu-info --json --memory 命令，获取包含内存信息的 JSON 输出。
	 */
	private String execJsonWithMemory() throws IOException {
		ProcessBuilder pb = new ProcessBuilder(exePath, "--json", "--memory");
		pb.redirectErrorStream(true);
		Process process = pb.start();

		StringBuilder output = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append('\n');
			}
		}

		try {
			int exitCode = process.waitFor();
			if (exitCode != 0) {
				throw new IOException("gpu-info exited with code " + exitCode + ": " + output);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("gpu-info execution interrupted", e);
		}

		return output.toString();
	}

	/**
	 * 获取系统可用内存和GPU显存信息。
	 * @return Map 包含:
	 *   - "availableRam": 可用 RAM (bytes)
	 *   - "availableVram": 可用 VRAM (bytes)
	 *   失败返回 null
	 */
	public Map<String, Long> getMemoryInfo() {
		try {
			if (!isAvailable()) {
				logger.info("[自动加载] gpu-info 不可用: {}", getInitError());
				return null;
			}
			String output = execJsonWithMemory();
			JsonObject root = JsonUtil.fromJson(output.trim(), JsonObject.class);
			if (root == null) {
				logger.info("[自动加载] gpu-info JSON 解析失败");
				return null;
			}
			Map<String, Long> result = new HashMap<>();

			// 解析 system.memory
			JsonObject system = root.getAsJsonObject("system");
			if (system == null) return null;
			JsonObject mem = system.getAsJsonObject("memory");
			if (mem == null) return null;

			long totalRam = mem.has("total_bytes") ? mem.get("total_bytes").getAsLong() : 0;
			long usedRam = mem.has("used_bytes") ? mem.get("used_bytes").getAsLong() : 0;
			result.put("availableRam", totalRam - usedRam);

			// 解析 devices[].sensors 计算可用显存
			long totalAvailableVram = 0;
			JsonArray devices = root.getAsJsonArray("devices");
			if (devices != null) {
				for (JsonElement dev : devices) {
					JsonObject sensorsObj = dev.getAsJsonObject().getAsJsonObject("sensors");
					if (sensorsObj != null && sensorsObj.has("memory_total_bytes") && sensorsObj.has("memory_used_bytes")) {
						JsonElement totalEl = sensorsObj.get("memory_total_bytes");
						JsonElement usedEl = sensorsObj.get("memory_used_bytes");
						if (!totalEl.isJsonNull() && !usedEl.isJsonNull()) {
							long total = totalEl.getAsLong();
							long used = usedEl.getAsLong();
							totalAvailableVram += (total - used);
						}
					} else {
						// fallback: 使用 dedicated_vram_bytes 总容量
						JsonObject devMem = dev.getAsJsonObject().getAsJsonObject("memory");
						if (devMem != null && devMem.has("dedicated_vram_bytes")) {
							totalAvailableVram += devMem.get("dedicated_vram_bytes").getAsLong();
						}
					}
				}
			}
			result.put("availableVram", totalAvailableVram);

			logger.info("[自动加载] 系统内存: availableRam={} GiB, availableVram={} GiB",
				Math.round(result.get("availableRam") / 1024.0 / 1024.0 / 1024.0 * 100.0) / 100.0,
				Math.round(result.get("availableVram") / 1024.0 / 1024.0 / 1024.0 * 100.0) / 100.0);
			return result;
		} catch (Exception e) {
			logger.info("[自动加载] gpu-info 执行异常: {}", e.getMessage());
			return null;
		}
	}
}
