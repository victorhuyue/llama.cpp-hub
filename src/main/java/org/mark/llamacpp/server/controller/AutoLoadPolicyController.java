package org.mark.llamacpp.server.controller;

import com.google.gson.JsonObject;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.AutoLoadPolicyManager;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;

public class AutoLoadPolicyController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(AutoLoadPolicyController.class);

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (uri.startsWith("/api/auto-load/policy")) {
			handlePolicy(ctx, request);
			return true;
		}
		return false;
	}

	private void handlePolicy(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}

		if (request.method() == HttpMethod.GET) {
			handleGetPolicies(ctx, request);
		} else if (request.method() == HttpMethod.PUT) {
			handleSetPolicy(ctx, request);
		} else if (request.method() == HttpMethod.DELETE) {
			handleResetPolicy(ctx, request);
		} else {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("不支持的请求方法"));
		}
	}

	private void handleGetPolicies(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				logger.info("[自动加载策略] 远程代理获取策略: nodeId={}", nodeId);
				proxyGetRemote(ctx, request, nodeId, "api/auto-load/policy");
				return;
			}
			AutoLoadPolicyManager manager = AutoLoadPolicyManager.getInstance();
			Map<String, Object> data = manager.getAllPolicies();
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取自动加载策略失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取自动加载策略失败: " + e.getMessage()));
		}
	}

	private void handleSetPolicy(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}

			String nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				logger.info("[自动加载策略] 远程代理设置策略: nodeId={}, modelId={}", nodeId, JsonUtil.getJsonString(obj, "modelId", ""));
				proxyPutRemote(ctx, request, nodeId, "api/auto-load/policy");
				return;
			}

			String modelId = JsonUtil.getJsonString(obj, "modelId");
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的 modelId 参数"));
				return;
			}

			String mode = JsonUtil.getJsonString(obj, "mode");
			if (mode == null || !("allow".equalsIgnoreCase(mode) || "deny".equalsIgnoreCase(mode))) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("mode 参数无效，必须为 allow 或 deny"));
				return;
			}

			String error = AutoLoadPolicyManager.getInstance().setModelPolicy(modelId, mode);
			if (error != null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error));
				return;
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(null));
		} catch (Exception e) {
			logger.info("设置自动加载策略失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("设置自动加载策略失败: " + e.getMessage()));
		}
	}

	private void handleResetPolicy(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			String uri = request.uri();
			String modelId = null;
			String nodeId = null;

			// 尝试从 URL 路径获取 modelId
			if (uri != null && uri.length() > "/api/auto-load/policy/".length()) {
				String pathPart = uri.substring("/api/auto-load/policy/".length());
				int qIdx = pathPart.indexOf('?');
				if (qIdx >= 0) {
					modelId = pathPart.substring(0, qIdx);
					Map<String, String> params = ParamTool.getQueryParam(pathPart.substring(qIdx));
					nodeId = params.get("nodeId");
				} else {
					modelId = pathPart;
				}
			}

			// 如果 URL 中没有，尝试从请求体获取
			if (modelId == null || modelId.trim().isEmpty()) {
				String content = request.content().toString(CharsetUtil.UTF_8);
				if (content != null && !content.trim().isEmpty()) {
					JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
					if (obj != null) {
						modelId = JsonUtil.getJsonString(obj, "modelId");
						nodeId = JsonUtil.getJsonString(obj, "nodeId", "");
					}
				}
			}

			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				logger.info("[自动加载策略] 远程代理重置策略: nodeId={}, modelId={}", nodeId, modelId);
				proxyDeleteRemote(ctx, request, nodeId, "api/auto-load/policy", modelId);
				return;
			}

			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的 modelId 参数"));
				return;
			}

			String error = AutoLoadPolicyManager.getInstance().resetModelPolicy(modelId);
			if (error != null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(error));
				return;
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(null));
		} catch (Exception e) {
			logger.info("重置自动加载策略失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("重置自动加载策略失败: " + e.getMessage()));
		}
	}

	/**
	 * 代理GET请求到远程节点（透传URI查询参数）
	 */
	private void proxyGetRemote(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path) {
		if (nodeId == null || nodeId.isBlank() || "local".equals(nodeId)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("无效的远程节点: " + nodeId));
			return;
		}
		try {
			String uri = request.uri();
			int qIdx = uri.indexOf('?');
			String fullPath;
			if (qIdx >= 0) {
				String query = uri.substring(qIdx + 1);
				String[] pairs = query.split("&");
				StringBuilder cleanQuery = new StringBuilder();
				for (String pair : pairs) {
					if (pair.startsWith("nodeId=")) continue;
					if (cleanQuery.length() > 0) cleanQuery.append('&');
					cleanQuery.append(pair);
				}
				fullPath = cleanQuery.length() > 0 ? path + "?" + cleanQuery.toString() : path;
			} else {
				fullPath = path;
			}
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(nodeId, "GET", fullPath, null);
			writeRemoteResult(ctx, result, nodeId);
		} catch (Exception e) {
			logger.warn("[自动加载策略] 远程代理失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("调用远程节点失败: " + e.getMessage()));
		}
	}

	/**
	 * 代理PUT请求到远程节点（透传请求体，移除nodeId避免回环）
	 */
	private void proxyPutRemote(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path) {
		if (nodeId == null || nodeId.isBlank() || "local".equals(nodeId)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("无效的远程节点: " + nodeId));
			return;
		}
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			JsonObject body = content != null && !content.trim().isEmpty()
					? JsonUtil.fromJson(content, JsonObject.class) : null;
			if (body != null) {
				body.remove("nodeId");
				if (body.size() == 0) body = null;
			}
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(nodeId, "PUT", path, body);
			writeRemoteResult(ctx, result, nodeId);
		} catch (Exception e) {
			logger.warn("[自动加载策略] 远程代理失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("调用远程节点失败: " + e.getMessage()));
		}
	}

	/**
	 * 代理DELETE请求到远程节点
	 */
	private void proxyDeleteRemote(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path, String modelId) {
		if (nodeId == null || nodeId.isBlank() || "local".equals(nodeId)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("无效的远程节点: " + nodeId));
			return;
		}
		try {
			String fullPath = path + "/" + modelId;
			JsonObject body = new JsonObject();
			if (modelId != null) {
				body.addProperty("modelId", modelId);
			}
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(nodeId, "DELETE", fullPath, body);
			writeRemoteResult(ctx, result, nodeId);
		} catch (Exception e) {
			logger.warn("[自动加载策略] 远程代理失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("调用远程节点失败: " + e.getMessage()));
		}
	}

	private void writeRemoteResult(ChannelHandlerContext ctx, NodeManager.HttpResult result, String nodeId) {
		if (result.isSuccess()) {
			NodeManager.writeHttpResultToChannel(ctx, result, "[自动加载策略]");
		} else {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("远程节点调用失败: code=" + result.getStatusCode()));
		}
	}
}
