package org.mark.llamacpp.server.controller;

import java.io.InputStream;
import java.util.Collections;
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
		if ("/api/perplexity/run".equals(uri)) {
			handleRun(ctx, request);
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
