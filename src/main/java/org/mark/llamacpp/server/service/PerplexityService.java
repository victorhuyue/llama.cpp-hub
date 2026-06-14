package org.mark.llamacpp.server.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.io.NettyWriteHelper;
import org.mark.llamacpp.server.tools.CommandLineRunner;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * 困惑度（Perplexity）测试服务。
 * <p>
 * 负责管理 Wikitext-2 数据集、构建 llama-perplexity 命令、启动子进程，
 * 并通过 Netty chunked 响应实时推送输出到前端。
 */
public class PerplexityService {

	private static final Logger logger = LoggerFactory.getLogger(PerplexityService.class);

	/**
	 * 数据集 zip 在 classpath 中的路径（例如打包在 jar 内或放在 classes/tools/perplexity 下）。
	 */
	private static final String DATASET_RESOURCE_PATH = "/tools/perplexity/wikitext-2.zip";
	private static final String EXTRACT_BASE_DIR = "tools" + File.separator + "perplexity";
	private static final String DOWNLOAD_URL = "https://huggingface.co/datasets/ggml-org/ci/resolve/main/wikitext-2-raw-v1.zip";
	private static final String TEST_FILE_NAME = "wiki.test.raw";

	private static final Pattern FINAL_PPL_PATTERN = Pattern.compile(
			"Final estimate:\\s*PPL\\s*=\\s*([0-9.Ee+\\-]+)\\s*\\+/-\\s*([0-9.Ee+\\-]+)");

	private static final List<String> CACHE_TYPES = List.of(
			"f32", "f16", "bf16", "q8_0", "q4_0", "q4_1", "iq4_nl", "q5_0", "q5_1");

	private static final ReentrantLock RUN_LOCK = new ReentrantLock();

	private volatile String cachedTestRawPath = null;

	/**
	 * 执行一次困惑度测试，并实时流式输出到客户端。
	 *
	 * @param ctx            Netty 通道上下文
	 * @param json           请求体 JSON
	 * @param activeProcesses 由控制器维护的 ctx -> Process 映射，用于断开时终止进程
	 * @throws Exception 仅在响应头尚未发送时抛出；已发送后所有错误通过流输出
	 */
	public void run(ChannelHandlerContext ctx, JsonObject json,
			ConcurrentHashMap<ChannelHandlerContext, Process> activeProcesses) throws Exception {
		String modelId = requireString(json, "modelId");
		String llamaBinPath = requireString(json, "llamaBinPath");
		int ctxSize = JsonUtil.getJsonInt(json, "ctxSize", 512);
		int ngl = JsonUtil.getJsonInt(json, "ngl", 0);
		int pplStride = JsonUtil.getJsonInt(json, "pplStride", 0);
		String extraParams = JsonUtil.getJsonString(json, "extraParams", "").trim();
		String cacheTypeK = JsonUtil.getJsonString(json, "cacheTypeK", "").trim();
		String cacheTypeV = JsonUtil.getJsonString(json, "cacheTypeV", "").trim();

		if (ctxSize <= 0) {
			throw new IllegalArgumentException("ctx-size 必须大于 0");
		}
		if (ngl < -1) {
			throw new IllegalArgumentException("n-gpu-layers 不能小于 -1");
		}
		if (pplStride < 0) {
			throw new IllegalArgumentException("ppl-stride 不能小于 0");
		}
		if (!cacheTypeK.isEmpty() && !CACHE_TYPES.contains(cacheTypeK)) {
			throw new IllegalArgumentException("--cache-type-k 不支持: " + cacheTypeK);
		}
		if (!cacheTypeV.isEmpty() && !CACHE_TYPES.contains(cacheTypeV)) {
			throw new IllegalArgumentException("--cache-type-v 不支持: " + cacheTypeV);
		}

		LlamaServerManager manager = LlamaServerManager.getInstance();
		GGUFModel model = manager.findModelById(modelId);
		if (model == null) {
			throw new IllegalArgumentException("未找到模型: " + modelId);
		}
		GGUFMetaData primary = model.getPrimaryModel();
		if (primary == null) {
			throw new IllegalArgumentException("模型元数据不完整: " + modelId);
		}
		String modelPath = primary.getFilePath();

		File exeDir = new File(llamaBinPath);
		File perplexityExe = findPerplexityExecutable(exeDir);
		if (perplexityExe == null) {
			throw new IllegalArgumentException("目录中不存在 llama-perplexity 可执行文件: " + exeDir.getAbsolutePath());
		}

		File testFile = ensureTestFile();

		if (!RUN_LOCK.tryLock()) {
			sendJsonLine(ctx, "error", Map.of("text", "已有困惑度测试正在运行，请等待其结束后再试"));
			sendJsonLine(ctx, "final", Map.of("exitCode", -1));
			return;
		}

		Process process = null;
		StringBuilder rawOutput = new StringBuilder();
		long startTime = System.currentTimeMillis();
		try {
			List<String> command = buildCommand(perplexityExe, modelPath, testFile, ctxSize, ngl, pplStride,
					cacheTypeK, cacheTypeV, extraParams);
			sendJsonLine(ctx, "started", Map.of("command", command));

			ProcessBuilder pb = new ProcessBuilder(command);
			pb.redirectErrorStream(true);
			CommandLineRunner.applyExecutableDirEnv(pb, command.toArray(new String[0]));

			process = pb.start();
			activeProcesses.put(ctx, process);
			final Process capturedProcess = process;

			Thread reader = Thread.ofVirtual().name("ppl-reader-" + modelId).start(() -> {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(capturedProcess.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = br.readLine()) != null) {
						if (!ctx.channel().isActive()) {
							break;
						}
						rawOutput.append(line).append(System.lineSeparator());
						sendJsonLine(ctx, "line", Map.of("text", line));
					}
				} catch (IOException e) {
					if (ctx.channel().isActive()) {
						logger.warn("读取 llama-perplexity 输出失败: {}", e.getMessage());
					}
				}
			});

			int exitCode = process.waitFor();
			reader.join(2000);

			String outputText = rawOutput.toString().trim();
			Matcher matcher = FINAL_PPL_PATTERN.matcher(outputText);
			Double ppl = null;
			Double uncertainty = null;
			if (matcher.find()) {
				try {
					ppl = Double.parseDouble(matcher.group(1));
					uncertainty = Double.parseDouble(matcher.group(2));
				} catch (NumberFormatException ignored) {
				}
			}

			String savedPath = null;
			try {
				File outFile = saveResult(modelId, outputText, command);
				savedPath = outFile.getAbsolutePath();
			} catch (Exception ex) {
				logger.info("保存困惑度测试结果失败", ex);
			}

			JsonObject finalObj = new JsonObject();
			finalObj.addProperty("type", "final");
			finalObj.addProperty("exitCode", exitCode);
			if (ppl != null) finalObj.addProperty("ppl", ppl);
			if (uncertainty != null) finalObj.addProperty("uncertainty", uncertainty);
			if (savedPath != null) finalObj.addProperty("savedPath", savedPath);
			finalObj.addProperty("elapsedMs", System.currentTimeMillis() - startTime);
			finalObj.addProperty("rawOutput", outputText);
			sendLine(ctx, JsonUtil.toJson(finalObj));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			sendJsonLine(ctx, "error", Map.of("text", "测试被中断"));
		} catch (Exception e) {
			logger.info("执行困惑度测试时发生错误", e);
			sendJsonLine(ctx, "error", Map.of("text", "测试失败: " + e.getMessage()));
		} finally {
			if (process != null) {
				activeProcesses.remove(ctx, process);
			}
			if (process != null && process.isAlive()) {
				CommandLineRunner.destroyProcessTree(process);
			}
			RUN_LOCK.unlock();
			if (ctx.channel().isActive()) {
				ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(f -> ctx.close());
			}
		}
	}

	private File ensureTestFile() throws IOException {
		String cached = cachedTestRawPath;
		if (cached != null) {
			File f = new File(cached);
			if (f.isFile()) {
				return f;
			}
		}
		synchronized (this) {
			cached = cachedTestRawPath;
			if (cached != null) {
				File f = new File(cached);
				if (f.isFile()) {
					return f;
				}
			}

			URL url = PerplexityService.class.getResource(DATASET_RESOURCE_PATH);
			if (url == null) {
				throw new IllegalStateException(
						"未在 classpath 中找到标准测试数据集: " + DATASET_RESOURCE_PATH
								+ "。请从 " + DOWNLOAD_URL + " 下载 wikitext-2-raw-v1.zip，"
								+ "重命名为 wikitext-2.zip 后放到 src/main/resources/tools/perplexity/ 目录（"
								+ "或打包后的 classes" + DATASET_RESOURCE_PATH + " 路径）。");
			}

			File zipFile = resolveDatasetZipFile(url);
			File extractDir = new File(EXTRACT_BASE_DIR);
			File existing = findTestRaw(extractDir);
			if (existing != null) {
				cachedTestRawPath = existing.getAbsolutePath();
				return existing;
			}

			extractZip(zipFile, extractDir);
			File extracted = findTestRaw(extractDir);
			if (extracted == null) {
				throw new IllegalStateException(
						"解压数据集后仍未找到 " + TEST_FILE_NAME + "，请检查 zip 文件内容。");
			}
			cachedTestRawPath = extracted.getAbsolutePath();
			return extracted;
		}
	}

	private File resolveDatasetZipFile(URL url) throws IOException {
		if ("file".equalsIgnoreCase(url.getProtocol())) {
			try {
				return new File(url.toURI());
			} catch (Exception e) {
				return new File(url.getPath());
			}
		}

		// jar 包等资源：复制到工作目录后再解压
		File dir = new File(EXTRACT_BASE_DIR);
		if (!dir.exists()) {
			dir.mkdirs();
		}
		File zipFile = new File(dir, "wikitext-2.zip");
		try (InputStream in = PerplexityService.class.getResourceAsStream(DATASET_RESOURCE_PATH)) {
			if (in == null) {
				throw new IOException("无法读取数据集资源流: " + DATASET_RESOURCE_PATH);
			}
			try (FileOutputStream out = new FileOutputStream(zipFile)) {
				in.transferTo(out);
			}
		}
		logger.info("已从 classpath 资源复制困惑度数据集到: {}", zipFile.getAbsolutePath());
		return zipFile;
	}

	private File findTestRaw(File dir) {
		if (!dir.isDirectory()) {
			return null;
		}
		try (Stream<Path> paths = Files.walk(dir.toPath())) {
			return paths.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().equals(TEST_FILE_NAME))
					.findFirst()
					.map(Path::toFile)
					.orElse(null);
		} catch (IOException e) {
			logger.warn("查找 {} 失败: {}", TEST_FILE_NAME, e.getMessage());
			return null;
		}
	}

	private void extractZip(File zipFile, File destDir) throws IOException {
		destDir.mkdirs();
		try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
			ZipEntry entry;
			byte[] buffer = new byte[8192];
			while ((entry = zis.getNextEntry()) != null) {
				String name = entry.getName();
				if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
					throw new IOException("非法的 zip 条目: " + name);
				}
				File outFile = new File(destDir, name);
				if (entry.isDirectory()) {
					outFile.mkdirs();
				} else {
					outFile.getParentFile().mkdirs();
					try (OutputStream os = new FileOutputStream(outFile)) {
						int len;
						while ((len = zis.read(buffer)) > 0) {
							os.write(buffer, 0, len);
						}
					}
				}
				zis.closeEntry();
			}
		}
		logger.info("已解压困惑度数据集到: {}", destDir.getAbsolutePath());
	}

	private File findPerplexityExecutable(File dir) {
		if (!dir.isDirectory()) {
			return null;
		}
		String os = System.getProperty("os.name").toLowerCase();
		String name = os.contains("win") ? "llama-perplexity.exe" : "llama-perplexity";
		File exe = new File(dir, name);
		return exe.isFile() ? exe : null;
	}

	private List<String> buildCommand(File perplexityExe, String modelPath, File testFile,
			int ctxSize, int ngl, int pplStride, String cacheTypeK, String cacheTypeV, String extraParams) {
		List<String> command = new ArrayList<>();
		command.add(perplexityExe.getAbsolutePath());
		command.add("-m");
		command.add(modelPath);
		command.add("-f");
		command.add(testFile.getAbsolutePath());
		command.add("-c");
		command.add(String.valueOf(ctxSize));
		command.add("-ngl");
		command.add(String.valueOf(ngl));
		if (pplStride > 0) {
			command.add("--ppl-stride");
			command.add(String.valueOf(pplStride));
		}
		if (!cacheTypeK.isEmpty()) {
			command.add("--cache-type-k");
			command.add(cacheTypeK);
		}
		if (!cacheTypeV.isEmpty()) {
			command.add("--cache-type-v");
			command.add(cacheTypeV);
		}
		if (!extraParams.isEmpty()) {
			command.addAll(CommandLineRunner.splitCommandLineArgs(extraParams));
		}
		return command;
	}

	private File saveResult(String modelId, String outputText, List<String> command) throws IOException {
		String safeModelId = (modelId == null ? "unknown" : modelId).replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
		File dir = new File("benchmarks");
		if (!dir.exists()) {
			dir.mkdirs();
		}
		File outFile = new File(dir, "PPL_" + safeModelId + "_" + timestamp + ".txt");
		StringBuilder content = new StringBuilder();
		content.append("command: ").append(String.join(" ", command)).append(System.lineSeparator())
				.append(System.lineSeparator())
				.append(outputText);
		try (FileOutputStream fos = new FileOutputStream(outFile)) {
			fos.write(content.toString().getBytes(StandardCharsets.UTF_8));
		}
		return outFile;
	}

	private void sendJsonLine(ChannelHandlerContext ctx, String type, Map<String, Object> fields) {
		JsonObject obj = new JsonObject();
		obj.addProperty("type", type);
		if (fields != null) {
			for (Map.Entry<String, Object> e : fields.entrySet()) {
				Object v = e.getValue();
				if (v == null) {
					obj.add(e.getKey(), null);
				} else if (v instanceof String) {
					obj.addProperty(e.getKey(), (String) v);
				} else if (v instanceof Number) {
					obj.addProperty(e.getKey(), (Number) v);
				} else if (v instanceof Boolean) {
					obj.addProperty(e.getKey(), (Boolean) v);
				} else if (v instanceof List) {
					JsonArray arr = new JsonArray();
					for (Object item : (List<?>) v) {
						if (item != null) {
							arr.add(String.valueOf(item));
						}
					}
					obj.add(e.getKey(), arr);
				} else {
					obj.addProperty(e.getKey(), v.toString());
				}
			}
		}
		sendLine(ctx, JsonUtil.toJson(obj));
	}

	private void sendLine(ChannelHandlerContext ctx, String text) {
		if (!ctx.channel().isActive()) {
			return;
		}
		byte[] bytes = (text + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
		DefaultHttpContent content = new DefaultHttpContent(Unpooled.copiedBuffer(bytes));
		NettyWriteHelper.writeAndFlushBlocking(ctx, content, logger, "[perplexity]");
	}

	private String requireString(JsonObject json, String key) {
		if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
			throw new IllegalArgumentException("缺少必需参数: " + key);
		}
		String value = json.get(key).getAsString();
		if (value == null || value.trim().isEmpty()) {
			throw new IllegalArgumentException("参数不能为空: " + key);
		}
		return value.trim();
	}
}
