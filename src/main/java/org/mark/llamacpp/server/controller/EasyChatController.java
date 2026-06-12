package org.mark.llamacpp.server.controller;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.EasyChatGlobalLock;
import org.mark.llamacpp.server.service.EasyChatService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * EasyChat 后端接口。
 * <p>
 * 提供流式聊天、消息更新等功能。
 * </p>
 */
public class EasyChatController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(EasyChatController.class);
	private final EasyChatGlobalLock globalLock = EasyChatGlobalLock.getInstance();

	private static final String PATH_STREAM_CHAT = "/api/chat/stream-chat";
	private static final String PATH_MESSAGE_UPDATE = "/api/chat/message/update";

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
			throws RequestMethodException {
		if (uri.startsWith(PATH_STREAM_CHAT)) {
			if (request.method() == HttpMethod.GET) {
				this.handleStreamChatHistory(ctx, request);
			} else {
				this.handleStreamChatRequest(ctx, request);
			}
			return true;
		}
		if (uri.startsWith(PATH_MESSAGE_UPDATE)) {
			this.withGlobalLock(ctx, "easy-chat.message.update", () -> this.handleMessageUpdateRequest(ctx, request));
			return true;
		}
		return false;
	}

	@FunctionalInterface
	private interface LockedAction {
		void run() throws RequestMethodException;
	}

	private void withGlobalLock(ChannelHandlerContext ctx, String operationName, LockedAction action)
			throws RequestMethodException {
		EasyChatGlobalLock.Lease lease = globalLock.tryAcquire(operationName);
		if (lease == null) {
			sendGlobalLockBusy(ctx, operationName);
			return;
		}
		try (lease) {
			action.run();
		}
	}

	private void sendGlobalLockBusy(ChannelHandlerContext ctx, String requestedOperation) {
		EasyChatGlobalLock.LockState current = globalLock.current();
		String message = "Easy Chat 正在执行其它操作，请稍后再试";
		Map<String, Object> data = new HashMap<>();
		data.put("requestedOperation", requestedOperation);
		if (current != null) {
			if (current.operationName() != null && !current.operationName().isBlank()) {
				message += "（当前操作: " + current.operationName() + "）";
				data.put("activeOperation", current.operationName());
			}
			data.put("startedAt", current.startedAt());
		}
		ApiResponse response = ApiResponse.error(message);
		response.setData(data);
		LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.LOCKED, response, true);
	}

	private void handleStreamChatRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		EasyChatService.getInstance().handleStreamChat(ctx, request);
	}

	private void handleStreamChatHistory(ChannelHandlerContext ctx, FullHttpRequest request) {
		Map<String, String> params = ParamTool.getQueryParam(request.uri());
		String conversationId = params.getOrDefault("conversationId", "").trim();
		EasyChatService.getInstance().handleStreamChatHistory(ctx, conversationId);
	}

	private void handleMessageUpdateRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			JsonObject body = JsonUtil.parseFullHttpRequestToJsonObject(request, ctx);
			if (body == null) {
				return;
			}
			String conversationId = JsonUtil.getJsonString(body, "conversationId", "");
			if (conversationId == null || conversationId.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("conversationId 为必填项"));
				return;
			}
			if (!body.has("seq") || body.get("seq").isJsonNull()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("seq 为必填项"));
				return;
			}
			long seq = body.get("seq").getAsLong();
			int variantIndex = body.has("variantIndex") && !body.get("variantIndex").isJsonNull()
				? body.get("variantIndex").getAsInt() : 0;
			JsonObject payloadObj = body.has("payload") && !body.get("payload").isJsonNull()
				? body.getAsJsonObject("payload") : null;
			if (payloadObj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("payload 为必填项"));
				return;
			}
			byte[] payloadBytes = JsonUtil.toJson(payloadObj).getBytes(StandardCharsets.UTF_8);
			EasyChatService service = EasyChatService.getInstance();
			Path fragmentsBase = service.getFragmentsDir();
			Path convDir = fragmentsBase.resolve(conversationId);
			service.updateFragmentVariant(convDir, seq, variantIndex, payloadBytes);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(Map.of("updated", true, "seq", seq)));
		} catch (Exception e) {
			logger.info("更新 easy-chat 消息失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("更新消息失败: " + e.getMessage()));
		}
	}
}
