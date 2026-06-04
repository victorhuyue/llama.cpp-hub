package org.mark.llamacpp.server.service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mark.llamacpp.server.LlamaHubNode;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.struct.ActiveRequest;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;

/**
 * 	这东西挺重要的，用于把聊天补全的JSON流式处理给llamacpp进程。
 */
public class ChatStreamSession {

	private static final Logger logger = LoggerFactory.getLogger(ChatStreamSession.class);
	
	/**
	 * 	依然是虚拟线程。
	 */
	private static final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

	private final ChannelHandlerContext ctx;
	private final OpenAIService openAIService;
	private final HttpMethod method;
	private final Map<String, String> headers;

	private final StreamingForwarder forwarder = new StreamingForwarder();

	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AtomicBoolean completed = new AtomicBoolean(false);
	private final AtomicBoolean cancelled = new AtomicBoolean(false);

	private volatile HttpURLConnection connection;
	private volatile String requestId;
	private volatile boolean receivedBody;
	private volatile String routingNodeId;
	
	
	
	/**
	 * 	初始化一个流式会话。
	 * @param ctx
	 * @param openAIService
	 * @param method
	 * @param headers
	 */
	public ChatStreamSession(ChannelHandlerContext ctx, OpenAIService openAIService, HttpMethod method, Map<String, String> headers) {
		this.ctx = ctx;
		this.openAIService = openAIService;
		this.method = method;
		this.headers = headers;
		String headerNodeId = headers.get("X-Node-Id");
		if (headerNodeId != null && !headerNodeId.isBlank()) {
			this.forwarder.setNodeId(headerNodeId);
		}
	}
	
	
	/**
	 * 	哎，启动！
	 */
	public void start() {
		if (this.started.compareAndSet(false, true)) {
			worker.execute(this::run);
		}
	}
	
	/**
	 * 	传入数据块。
	 * @param content
	 * @throws IOException
	 */
	public void offer(ByteBuf content) throws IOException {
		if (content == null || !content.isReadable()) {
			return;
		}
		byte[] bytes = new byte[content.readableBytes()];
		content.getBytes(content.readerIndex(), bytes);
		this.receivedBody = true;
		this.forwarder.offer(bytes);
	}

	/**
	 * 	传入最后一个数据块，进行注入后转发。
	 * @param content
	 * @throws IOException
	 */
	public void offerLast(ByteBuf content) throws IOException {
		if (content == null || !content.isReadable()) {
			return;
		}
		byte[] bytes = new byte[content.readableBytes()];
		content.getBytes(content.readerIndex(), bytes);
		this.receivedBody = true;
		this.forwarder.offerLast(bytes);
	}
	
	/**
	 * 	完成了，需要显式地调用这个表明任务正常结束了。
	 */
	public void complete() {
		if (this.completed.compareAndSet(false, true)) {
			if (this.forwarder != null) {
				this.forwarder.complete();
			}
		}
	}
	
	/**
	 * 	取消，取他妈的消。
	 */
	public void cancel() {
		if (this.cancelled.compareAndSet(false, true)) {
			ModelRequestTracker.getInstance().removeRequest(this.requestId);
			if (this.forwarder != null) {
				this.forwarder.fail(new IOException("client disconnected"));
			}
			HttpURLConnection connToDisconnect = null;
			synchronized (this) {
				if (this.connection != null) {
					connToDisconnect = this.connection;
					this.connection = null;
				}
			}
			if (connToDisconnect != null) {
				try {
					connToDisconnect.getInputStream().close();
				} catch (IOException ignored) {
				}
				try {
					connToDisconnect.getOutputStream().close();
				} catch (IOException ignored) {
				}
				connToDisconnect.disconnect();
				logger.info("已主动中止远程连接");
			}
			try {
				this.forwarder.close();
			} catch (IOException e) {
			}
		}
	}
	
	/**
	 * 	run run run
	 */
	private void run() {
		try {
			if (this.method != HttpMethod.POST) {
				this.openAIService.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			StreamingForwarder fwd = this.forwarder;
			if (fwd == null) {
				this.openAIService.sendOpenAIErrorResponseWithCleanup(this.ctx, 500, null, "Forwarder not initialized", null);
				return;
			}
			StreamingForwarder.TransformResult result = fwd.extract();

			if (!this.receivedBody) {
				this.openAIService.sendOpenAIErrorResponseWithCleanup(this.ctx, 400, null, "Request body is empty", "messages");
				return;
			}

			this.openConnectionForModel(result.getModelName(), result.getNodeId());
			if (this.connection == null) {
				throw new IOException("llama.cpp connection was not created");
			}

	OutputStream connOutput = this.connection.getOutputStream();
			fwd.streamBody(connOutput);
			connOutput.flush();
			connOutput.close();

			this.requestId = ModelRequestTracker.getInstance().createRequest(result.getModelName(), "/v1/chat/completions");
			if (this.cancelled.get()) {
				logger.info("聊天流式会话已取消，中止请求");
				return;
			}
			int responseCode = this.connection.getResponseCode();
			ModelRequestTracker.getInstance().updatePhase(this.requestId, ActiveRequest.Phase.GENERATION);
			this.openAIService.handleProxyResponse(this.ctx, this.connection, responseCode, result.isStream(), result.getModelName(), this.requestId, this.routingNodeId);
		} catch (StreamingForwarder.ForwarderException e) {
			if (!this.cancelled.get()) {
				this.openAIService.sendOpenAIErrorResponseWithCleanup(this.ctx, e.getHttpStatus(), null, e.getMessage(), e.getParam());
			}
		} catch (IOException e) {
			if (this.cancelled.get()) {
				logger.info("聊天流式会话已取消: {}", e.getMessage());
				return;
			}
			if (!this.receivedBody) {
				this.openAIService.sendOpenAIErrorResponseWithCleanup(this.ctx, 400, null, "Request body is empty", "messages");
				return;
			}
			logger.info("处理聊天流式请求时发生错误 [{}]", this.resolveNodeName(this.routingNodeId), e);
			this.openAIService.sendOpenAIErrorResponseWithCleanup(this.ctx, 500, null, e.getMessage(), null);
		} catch (Exception e) {
			if (this.cancelled.get()) {
				logger.info("聊天流式会话已取消: {}", e.getMessage());
				return;
			}
			logger.info("处理聊天流式请求时发生错误 [{}]", this.resolveNodeName(this.routingNodeId), e);
			this.openAIService.sendOpenAIErrorResponseWithCleanup(this.ctx, 500, null, e.getMessage(), null);
		} catch (Throwable t) {
			logger.error("虚拟线程异常已兜底: {}", t.getMessage(), t);
		} finally {
			ModelRequestTracker.getInstance().removeRequest(this.requestId);
			try {
				this.forwarder.close();
			} catch (IOException e) {
			}
			this.openAIService.cleanupTrackedConnection(this.ctx, this.connection);
		}
	}
	
	/**
	 * 解析节点名称
	 */
	private String resolveNodeName(String nodeId) {
		if (nodeId == null || nodeId.isBlank()) return "本机";
		try {
			LlamaHubNode node = NodeManager.getInstance().getNode(nodeId);
			if (node != null && node.getName() != null && !node.getName().isBlank()) {
				return node.getName();
			}
		} catch (Exception e) {
		}
		return nodeId;
	}

	/**
	 * 	连接到指定的llamacpp进程。
	 * @param modelName
	 * @throws IOException
	 */
	private synchronized void openConnectionForModel(String modelName, String nodeIdFromBody) throws IOException {
		if (this.connection != null) {
			return;
		}
		if (modelName == null) {
			return;
		}

		logger.info("[Node路由] 开始解析模型: model={}", modelName);

		String targetUrl;

		if (nodeIdFromBody != null && !nodeIdFromBody.isBlank()) {
			logger.info("[Node路由] 请求体指定 nodeId，直接路由远程节点: nodeId={}, model={}", nodeIdFromBody, modelName);
			targetUrl = this.resolveRemoteModelUrl(nodeIdFromBody, modelName);
			this.routingNodeId = nodeIdFromBody;
		} else {
			logger.info("[Node路由] 无 nodeId，先查本地模型");
			targetUrl = this.resolveLocalModelUrl(modelName);
			if (targetUrl == null) {
				logger.info("[Node路由] 本地未找到模型，开始搜索远程节点: model={}", modelName);
				String[] remote = this.resolveFromRemoteNodes(modelName);
				if (remote != null) {
					targetUrl = remote[0];
					this.routingNodeId = remote[1];
				}
			}
		}

		if (targetUrl == null) {
			logger.warn("[Node路由] 模型未找到: model={}", modelName);
			throw new StreamingForwarder.ForwarderException(404, "Model not found: " + modelName, "model");
		}

		logger.info("[Node路由] 路由成功: model={}, target={}", modelName, targetUrl);
		this.connection = this.openAIService.openTrackedConnection(this.ctx, targetUrl, this.method, this.headers, true);
		logger.info("[Node路由] 聊天流式请求已连接到模型: {}, target: {}", modelName, targetUrl);
	}

	/**
	 * 解析本地模型 URL
	 */
	private String resolveLocalModelUrl(String modelName) {
		try {
			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				String resolved = manager.findModelIdByAlias(modelName);
				if (resolved != null) {
					modelName = resolved;
				}
			}
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				logger.info("[Node路由] 本地模型未加载: model={}, loadedModels={}", modelName, manager.getLoadedProcesses().keySet());
				return null;
			}
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				logger.warn("[Node路由] 本地模型端口未找到: model={}", modelName);
				return null;
			}
			return String.format("http://localhost:%d/v1/chat/completions", modelPort.intValue());
		} catch (Exception e) {
			logger.warn("[Node路由] 解析本地模型异常: model={}, error={}", modelName, e.getMessage());
			return null;
		}
	}

	/**
	 * 从远程节点中查找模型
	 */
	private String[] resolveFromRemoteNodes(String modelName) {
		NodeManager nodeManager = NodeManager.getInstance();
		List<LlamaHubNode> enabledNodes = nodeManager.listEnabledNodes();
		logger.info("[Node路由] 远程节点列表: count={}", enabledNodes.size());

		for (LlamaHubNode node : enabledNodes) {
			logger.info("[Node路由] 检查远程节点: nodeId={}, nodeName={}, baseUrl={}", node.getNodeId(), node.getName(), node.getBaseUrl());
			try {
				NodeManager.HttpResult result = nodeManager.callRemoteApi(node.getNodeId(), "GET", "/v1/models", null);
				logger.info("[Node路由] 远程节点响应: nodeId={}, code={}", node.getNodeId(), result.getStatusCode());
				if (!result.isSuccess()) {
					logger.warn("[Node路由] 远程节点请求失败: nodeId={}, body={}", node.getNodeId(), result.getBody());
					continue;
				}

				JsonObject root = JsonUtil.fromJson(result.getBody(), JsonObject.class);
				if (root == null) continue;

				if (root.has("models") && root.get("models").isJsonArray()) {
					JsonArray remoteModels = root.getAsJsonArray("models");
					for (com.google.gson.JsonElement el : remoteModels) {
						if (!el.isJsonObject()) continue;
						com.google.gson.JsonObject m = el.getAsJsonObject();
						String remoteModelKey = JsonUtil.getJsonString(m, "model");
						if (remoteModelKey.isEmpty()) remoteModelKey = JsonUtil.getJsonString(m, "name");
						logger.info("[Node路由] 远程模型条目: nodeId={}, remoteModelKey={}", node.getNodeId(), remoteModelKey);
						if (modelName.equals(remoteModelKey)) {
							logger.info("[Node路由] 远程节点匹配成功: model={}, nodeId={}", modelName, node.getNodeId());
							return new String[]{ node.getBaseUrl() + "/v1/chat/completions", node.getNodeId() };
						}
					}
				}
			} catch (Exception e) {
				logger.warn("[Node路由] 检查远程节点异常: nodeId={}, error={}", node.getNodeId(), e.getMessage());
			}
		}
		logger.warn("[Node路由] 所有远程节点均未找到模型: model={}", modelName);
		return null;
	}

	/**
	 * 解析远程节点模型 URL
	 */
	private String resolveRemoteModelUrl(String nodeId, String modelName) {
		NodeManager nodeManager = NodeManager.getInstance();
		LlamaHubNode node = nodeManager.getNode(nodeId);
		if (node == null || !node.isEnabled()) {
			logger.warn("[Node路由] 远程节点不存在或未启用: nodeId={}", nodeId);
			return null;
		}
		return node.getBaseUrl() + "/v1/chat/completions";
	}
}
