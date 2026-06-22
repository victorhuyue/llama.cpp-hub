package org.mark.llamacpp.server.controller;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.NodeManager.StreamResult;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.PerplexityService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;

/**
 * 困惑度（Perplexity）测试控制器。
 */
public class PerplexityController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(PerplexityController.class);

	private final PerplexityService perplexityService = new PerplexityService();
	private final ConcurrentHashMap<ChannelHandlerContext, Process> activeProcesses = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<ChannelHandlerContext, StreamResult> activeRemoteCalls = new ConcurrentHashMap<>();

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		String path = uri;
		int q = uri.indexOf('?');
		if (q >= 0) {
			path = uri.substring(0, q);
		}
		if ("/api/perplexity/run".equals(path)) {
			handleRun(ctx, request);
			return true;
		}
		if ("/api/perplexity/records".equals(path)) {
			handleListRecords(ctx, request);
			return true;
		}
		if (path.startsWith("/api/perplexity/records/")) {
			String fileName = path.substring("/api/perplexity/records/".length());
			handleRecordByFilename(ctx, request, fileName);
			return true;
		}
		return false;
	}

	private void handleRun(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		String content = request.content().toString(CharsetUtil.UTF_8);
		if (content == null || content.trim().isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
			return;
		}

		JsonObject json;
		try {
			json = JsonUtil.fromJson(content, JsonObject.class);
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败: " + e.getMessage()));
			return;
		}
		if (json == null) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
			return;
		}

		String modelId = JsonUtil.getJsonString(json, "modelId", "").trim();
		String llamaBinPath = JsonUtil.getJsonString(json, "llamaBinPath", "").trim();

		if (modelId.isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的 modelId 参数"));
			return;
		}
		if (llamaBinPath.isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的 llamaBinPath 参数"));
			return;
		}

		String nodeId = JsonUtil.getJsonString(json, "nodeId", "").trim();
		if (!nodeId.isEmpty() && !"local".equals(nodeId)) {
			handleRemoteRun(ctx, json, nodeId);
			return;
		}

		// 发送流式响应头
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
		response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
		LlamaServer.setCorsHeaders(response.headers());
		ctx.writeAndFlush(response);

		try {
			perplexityService.run(ctx, json, activeProcesses);
		} catch (Exception e) {
			logger.info("启动困惑度测试失败", e);
			sendErrorAndClose(ctx, e.getMessage());
		}
	}

	private void handleRemoteRun(ChannelHandlerContext ctx, JsonObject json, String nodeId) {
		// 转发给远程节点时移除 nodeId，避免递归代理
		JsonObject forwarded = json.deepCopy();
		forwarded.remove("nodeId");

		StreamResult result = NodeManager.getInstance().callRemoteApiStreaming(
				nodeId, "POST", "api/perplexity/run", forwarded,
				Collections.emptyMap(), 0);

		if (!result.isSuccess()) {
			result.abort();
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(
					"远程节点调用失败 (HTTP " + result.getStatusCode() + ")"));
			return;
		}

		activeRemoteCalls.put(ctx, result);

		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
		response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
		LlamaServer.setCorsHeaders(response.headers());
		ctx.writeAndFlush(response);

		// 在独立线程中读取远程响应并转发给前端
		Thread.startVirtualThread(() -> {
			byte[] buffer = new byte[8192];
			try (InputStream in = result.getBody()) {
				int len;
				while ((len = in.read(buffer)) != -1) {
					if (!ctx.channel().isActive()) {
						break;
					}
					ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(buffer, 0, len)));
				}
			} catch (Exception e) {
				logger.debug("远程困惑度测试流读取结束: {}", e.getMessage());
			} finally {
				activeRemoteCalls.remove(ctx);
				result.abort();
				if (ctx.channel().isActive()) {
					ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
							.addListener(ChannelFutureListener.CLOSE);
				}
			}
		});
	}

	private void handleListRecords(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			File dir = new File("benchmarks");
			List<Map<String, Object>> records = new ArrayList<>();
			if (dir.exists() && dir.isDirectory()) {
				File[] all = dir.listFiles();
				if (all != null) {
					Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
					SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
					for (File f : all) {
						String name = f.getName();
						if (!f.isFile() || !name.startsWith("PPL_") || !name.endsWith(".json")) {
							continue;
						}
						try {
							String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
							JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
							if (obj == null) {
								continue;
							}
							Map<String, Object> info = new HashMap<>();
							info.put("fileName", name);
							info.put("modelId", obj.has("modelId") ? obj.get("modelId").getAsString() : "");
							info.put("nodeId", obj.has("nodeId") ? obj.get("nodeId").getAsString() : "local");
							info.put("timestamp", obj.has("timestamp") ? obj.get("timestamp").getAsString() : fmt.format(new Date(f.lastModified())));
							if (obj.has("ppl") && !obj.get("ppl").isJsonNull()) {
								info.put("ppl", obj.get("ppl").getAsDouble());
							}
							if (obj.has("uncertainty") && !obj.get("uncertainty").isJsonNull()) {
								info.put("uncertainty", obj.get("uncertainty").getAsDouble());
							}
							info.put("exitCode", obj.has("exitCode") ? obj.get("exitCode").getAsInt() : -1);
							info.put("elapsedMs", obj.has("elapsedMs") ? obj.get("elapsedMs").getAsLong() : 0);
							records.add(info);
						} catch (Exception e) {
							logger.debug("解析困惑度记录失败: {}", name, e);
						}
					}
				}
			}
			Map<String, Object> data = new HashMap<>();
			data.put("records", records);
			data.put("count", records.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取困惑度测试记录列表失败: " + e.getMessage()));
		}
	}

	private void handleRecordByFilename(ChannelHandlerContext ctx, FullHttpRequest request, String fileName)
			throws RequestMethodException {
		if (fileName == null || fileName.isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少文件名"));
			return;
		}
		if (!fileName.matches("[a-zA-Z0-9._\\-]+") || !fileName.startsWith("PPL_") || !fileName.endsWith(".json")) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("文件名不合法"));
			return;
		}
		if (request.method() == HttpMethod.GET) {
			handleGetRecord(ctx, fileName);
		} else if (request.method() == HttpMethod.DELETE) {
			handleDeleteRecord(ctx, fileName);
		} else {
			this.assertRequestMethod(true, "只支持GET或DELETE请求");
		}
	}

	private void handleGetRecord(ChannelHandlerContext ctx, String fileName) {
		try {
			File dir = new File("benchmarks");
			File target = new File(dir, fileName);
			if (!target.exists() || !target.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("文件不存在"));
				return;
			}
			String content = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("记录解析失败"));
				return;
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(obj));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("读取困惑度测试记录失败: " + e.getMessage()));
		}
	}

	private void handleDeleteRecord(ChannelHandlerContext ctx, String fileName) {
		try {
			File dir = new File("benchmarks");
			File jsonFile = new File(dir, fileName);
			if (!jsonFile.exists() || !jsonFile.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("文件不存在"));
				return;
			}
			Files.delete(jsonFile.toPath());
			String txtName = fileName.substring(0, fileName.length() - ".json".length()) + ".txt";
			File txtFile = new File(dir, txtName);
			if (txtFile.isFile()) {
				try {
					Files.delete(txtFile.toPath());
				} catch (Exception ignored) {
				}
			}
			Map<String, Object> data = new HashMap<>();
			data.put("fileName", fileName);
			data.put("deleted", true);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("删除困惑度测试记录失败: " + e.getMessage()));
		}
	}

	private void sendErrorAndClose(ChannelHandlerContext ctx, String message) {
		if (!ctx.channel().isActive()) {
			return;
		}
		String errorLine = JsonUtil.toJson(
				java.util.Map.of("type", "error", "text", message)) + System.lineSeparator();
		byte[] errorBytes = errorLine.getBytes(CharsetUtil.UTF_8);
		ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(errorBytes)))
				.addListener(f -> ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
						.addListener(ChannelFutureListener.CLOSE));
	}

	@Override
	public void inactive(ChannelHandlerContext ctx) {
		Process process = activeProcesses.remove(ctx);
		if (process != null && process.isAlive()) {
			logger.info("客户端断开连接，终止困惑度测试进程");
			org.mark.llamacpp.server.tools.CommandLineRunner.destroyProcessTree(process);
		}
		StreamResult remote = activeRemoteCalls.remove(ctx);
		if (remote != null) {
			logger.info("客户端断开连接，中止远程困惑度测试调用");
			remote.abort();
		}
	}
}
