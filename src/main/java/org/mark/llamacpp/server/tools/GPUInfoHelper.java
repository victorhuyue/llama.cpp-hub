package org.mark.llamacpp.server.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 	这个东西用来执行gpu-info。
 */
public class GPUInfoHelper {

	private static final GPUInfoHelper INSTANCE = new GPUInfoHelper();
	private static final String CACHE_DIR_NAME = "cache/tools/easy-tools";

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

			Path cacheDir = Paths.get(CACHE_DIR_NAME, platform);
			Files.createDirectories(cacheDir);

			String exeName = platform.equals("windows") ? "gpu-info-x64.exe" : "gpu-info-x64";
			String resourcePath = "/tools/easy-tools/" + platform + "/" + exeName;

			copyFromClasspath(resourcePath, cacheDir.resolve(exeName));

			exePath = cacheDir.resolve(exeName).toAbsolutePath().toString();
			File exeFile = new File(exePath);
			if (!exeFile.exists()) {
				throw new FileNotFoundException("gpu-info binary not extracted: " + exePath);
			}
			if (!exeFile.setExecutable(true, false) && !exeFile.canExecute()) {
				if (platform.equals("linux")) {
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

	private static void copyFromClasspath(String resource, Path dest) {
		if (Files.exists(dest))
			return;
		try (InputStream is = GPUInfoHelper.class.getResourceAsStream(resource)) {
			if (is == null)
				return;
			Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			// non-fatal
		}
	}
}
