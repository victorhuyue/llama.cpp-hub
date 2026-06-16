package org.mark.llamacpp.server.channel;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mark.llamacpp.server.BuildInfo;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.controller.BaseController;
import org.mark.llamacpp.server.controller.AutoLoadPolicyController;
import org.mark.llamacpp.server.controller.ChatStateController;
import org.mark.llamacpp.server.controller.EasyChatController;
import org.mark.llamacpp.server.controller.HuggingFaceController;
import org.mark.llamacpp.server.controller.LlamacppController;
import org.mark.llamacpp.server.controller.ModelActionController;
import org.mark.llamacpp.server.controller.ModelInfoController;
import org.mark.llamacpp.server.controller.ModelPathController;
import org.mark.llamacpp.server.controller.NodeController;
import org.mark.llamacpp.server.controller.PerplexityController;
import org.mark.llamacpp.server.controller.ProxyController;
import org.mark.llamacpp.server.controller.ParamController;
import org.mark.llamacpp.server.controller.SystemController;
import org.mark.llamacpp.server.controller.CertController;
import org.mark.llamacpp.server.controller.ToolController;
import org.mark.llamacpp.server.controller.UsageReportController;
import org.mark.test.mcp.DefaultMcpServiceImpl;
import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpToolInputSchema;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

/**
 * 基本路由处理器。 实现本项目用到的API端点。
 */
public class BasicRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(BasicRouterHandler.class);

	private static final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
	
	private static final List<BaseController> pipeline = new LinkedList<>();
	
	
	static {
		pipeline.add(new ChatStateController());
		pipeline.add(new EasyChatController());
		pipeline.add(new HuggingFaceController());
		pipeline.add(new LlamacppController());
		pipeline.add(new ModelActionController());
		pipeline.add(new PerplexityController());
		pipeline.add(new ModelInfoController());
		pipeline.add(new ModelPathController());
		pipeline.add(new NodeController());
		pipeline.add(new ProxyController());
		pipeline.add(new ParamController());
		pipeline.add(new ToolController());
		pipeline.add(new SystemController());
		pipeline.add(new UsageReportController());
		pipeline.add(new AutoLoadPolicyController());
		pipeline.add(new CertController());
	}
	
	

	public BasicRouterHandler() {

	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		FullHttpRequest retained = request.retainedDuplicate();
		async.execute(() -> {
			try {
				this.handleRequest(ctx, retained);
			} finally {
				ReferenceCountUtil.release(retained);
			}
		});
	}
	
	
	/**
	 * 	真正处理请求的地方
	 * @param ctx
	 * @param request
	 */
	private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (!request.decoderResult().isSuccess()) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求解析失败");
			return;
		}
		String uri = request.uri();
		
		// 这里是日志专区
		// 1.
		if(LlamaServer.logRequestUrl) {
			logger.info("DEBUG - 收到请求：{}", uri);	
		}
		// 2.
		if(LlamaServer.logRequestHeader) {
			logger.info("DEBUG - 请求头：{}", request.headers());
		}
		// 3.
		if(LlamaServer.logRequestBody) {
			logger.info("DEBUG - 请求体：{}", request.content().toString(CharsetUtil.UTF_8));
		}
		
		// 傻逼浏览器不知道为什么一直在他妈的访问/.well-known/appspecific/com.chrome.devtools.json
		if ("/.well-known/appspecific/com.chrome.devtools.json".equals(uri)) {
			ctx.close();
			return;
		}
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		try {
			// 处理模型API请求
			if (this.isApiRequest(uri)) {
				if (uri.startsWith("/llama.cpp/props")) {
					this.handleProps(ctx, request);
					return;
				}
				if (uri.startsWith("/llama.cpp/tools")) {
					this.handleTools(ctx, request);
					return;
				}
				if (uri.startsWith("/llama.cpp/models/load")) {
					this.handleLoadModel(ctx, request);
					return;
				}
				if (uri.startsWith("/llama.cpp/models/unload")) {
					this.handleUnloadModel(ctx, request);
					return;
				}
				boolean handled = false;
				for (BaseController c : pipeline) {
					handled = c.handleRequest(uri, ctx, request);
					if (handled) {
						break;
					}
				}
				if (!handled) {
					ctx.fireChannelRead(request.retain());
				}
				return;
			}
			// 断言一下请求方式
			this.assertRequestMethod(request.method() != HttpMethod.GET, "仅支持GET请求");
			// 解码URI
			String path = URLDecoder.decode(uri, "UTF-8");
			if(path.indexOf('?') > 0) {
				path = path.substring(0, path.indexOf('?'));
			}
			boolean isRootRequest = path.equals("/");

			if (isRootRequest) {
				path = isMobileRequest(request) ? "/index-mobile.html" : "/index.html";
			}
			if (path.equals("/llama.cpp") || path.equals("/llama.cpp/")) {
				path = "/llama.cpp/index.html";
			}
			// 
			URL url = LlamaServer.class.getResource("/web" + path);

			if (url == null) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "文件不存在: " + path);
				return;
			}
			// 对于非API请求，只允许访问静态文件，不允许目录浏览
			// 首先尝试从resources目录获取文件
			File file = Paths.get(url.toURI()).toFile();
			if (!file.exists()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "文件不存在: " + path);
				return;
			}
			if (file.isDirectory()) {
				// 不允许直接访问目录，必须通过API
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.FORBIDDEN, "不允许直接访问目录，请使用API获取文件列表");
			} else {
				LlamaServer.sendStaticFile(ctx, file, request);
			}
		} catch (RequestMethodException e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
		} catch (Exception e) {
			logger.info("处理静态文件请求时发生错误", e);
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
		}
	}

	private boolean isMobileRequest(FullHttpRequest request) {
		if (request == null) {
			return false;
		}
		String chMobile = request.headers().get("Sec-CH-UA-Mobile");
		if (chMobile != null && chMobile.indexOf("?1") >= 0) {
			return true;
		}
		String userAgent = request.headers().get("User-Agent");
		if (userAgent == null || userAgent.isBlank()) {
			return false;
		}
		String ua = userAgent.toLowerCase();
		return ua.contains("mobi")
				|| ua.contains("android")
				|| ua.contains("iphone")
				|| ua.contains("ipad")
				|| ua.contains("ipod")
				|| ua.contains("windows phone")
				|| ua.contains("webos")
				|| ua.contains("blackberry")
				|| ua.contains("opera mini")
				|| ua.contains("opera mobi");
	}
	
	
	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		for(BaseController controller : pipeline) {
			controller.inactive(ctx);
		}
		// 事件通知
		super.channelInactive(ctx);
	}
	
	
	/**
	 * 	简单的断言。
	 * @param check
	 * @param message
	 * @throws RequestMethodException
	 */
	private void assertRequestMethod(boolean check, String message) throws RequestMethodException {
		if (check)
			throw new RequestMethodException(message);
	}
	
	/**
	 * 是否为API请求。
	 * <p>整合了路由层定义的所有 OpenAI 风格端点及系统内部 API。</p>
	 * @param uri 请求路径
	 * @return true 如果是 API 请求，否则 false
	 */
	private boolean isApiRequest(String uri) {
		if (uri == null) {
			return false;
		}
		// 1. 现有通用系统 API
		if (uri.startsWith("/api/") || 
				uri.startsWith("/session") || 
				uri.startsWith("/tokenize") || 
				uri.startsWith("/apply-template") || 
				uri.startsWith("/infill")) {
			return true;
		}
		// 2. OpenAI 标准协议路径 (/v1/... 覆盖所有 v1 前缀的变体)
		if (uri.startsWith("/v1")) {
			return true;
		}
		// 3. llama.cpp 基础端点（仅匹配 API 路径，不拦截静态资源）
		if ("/llama.cpp/props".equals(uri) || uri.startsWith("/llama.cpp/props?")) {
			return true;
		}
		if (uri.startsWith("/llama.cpp/models")) {
			return true;
		}
		if (uri.startsWith("/llama.cpp/v1/chat")) {
			return true;
		}
		if (uri.startsWith("/llama.cpp/v1/models")) {
			return true;
		}
		if (uri.startsWith("/llama.cpp/tools")) {
			return true;
		}
		if (uri.startsWith("/llama.cpp/slots")) {
			return true;
		}
		// 4. 显式补充非 /v1 前缀的具体端点 (源自路由逻辑中的完整路径)
		// 注意：这里写死具体的根路径，以确保与路由层的处理完全一致
		if (uri.startsWith("/models") || 
				uri.startsWith("/chat/completion") || 
				uri.startsWith("/completions") || 
				uri.startsWith("/embeddings") ||
				uri.startsWith("/rerank") || 
				uri.startsWith("/responses") ||
				uri.startsWith("/slots")) {
			if(!uri.endsWith(".html"))
				return true;
		}
		return false;
	}

	private void handleProps(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String model = params.get("model");
			String autoload = params.get("autoload");

			if (model == null || model.trim().isEmpty()) {
				this.handleServerProps(ctx);
			} else {
				this.handleModelProps(ctx, model.trim(), autoload != null && Boolean.parseBoolean(autoload));
			}
		} catch (Exception e) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "获取props失败: " + e.getMessage());
		}
	}

	private void handleServerProps(ChannelHandlerContext ctx) {
		JsonObject result = new JsonObject();
		result.addProperty("role", "router");
		result.addProperty("max_instances", 99);
		result.addProperty("models_autoload", true);
		result.addProperty("model_alias", "llama-server");
		result.addProperty("model_path", "none");

		JsonObject defaultGenSettings = new JsonObject();
		defaultGenSettings.add("params", null);
		defaultGenSettings.addProperty("n_ctx", 0);
		result.add("default_generation_settings", defaultGenSettings);

		result.add("ui_settings", new JsonObject());
		result.add("webui_settings", new JsonObject());
		result.addProperty("build_info", BuildInfo.getTag());
		result.addProperty("cors_proxy_enabled", true);

		LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.OK, result, true);
	}

	private void handleModelProps(ChannelHandlerContext ctx, String modelName, boolean autoload) {
		LlamaServerManager manager = LlamaServerManager.getInstance();
		String modelId = manager.resolveModelId(modelName);

		if (modelId == null) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelName);
			return;
		}

		if (!manager.getLoadedProcesses().containsKey(modelId)) {
			if (autoload) {
				String err = manager.autoLoadModelFromConfig(modelId, 600000);
				if (err != null) {
					LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Auto-load failed: " + err);
					return;
				}
			} else {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "模型未加载: " + modelId);
				return;
			}
		}

		Integer port = manager.getModelPort(modelId);
		if (port == null) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "未找到模型端口: " + modelId);
			return;
		}

		this.forwardProps(ctx, modelId, port);
	}

	private void forwardProps(ChannelHandlerContext ctx, String modelId, int port) {
		try {
			String targetUrl = String.format("http://localhost:%d/props", port);
			URL url = URI.create(targetUrl).toURL();
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			int responseCode = connection.getResponseCode();
			String responseBody;

			if (responseCode >= 200 && responseCode < 300) {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				Object parsed = JsonUtil.fromJson(responseBody, Object.class);
				Map<String, Object> data = new HashMap<>();
				data.put("modelId", modelId);
				data.put("props", parsed);
				LlamaServer.sendJsonResponse(ctx, data);
			} else {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, "获取props失败: " + responseBody);
			}
			connection.disconnect();
		} catch (Exception e) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "获取props失败: " + e.getMessage());
		}
	}

	private void handleLoadModel(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体为空");
				return;
			}

			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体解析失败");
				return;
			}

			String modelId = JsonUtil.getJsonString(obj, "model");
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "缺少必需的model参数");
				return;
			}
			modelId = modelId.trim();

			LlamaServerManager manager = LlamaServerManager.getInstance();

			if (manager.getLoadedProcesses().containsKey(modelId)) {
				JsonObject resp = new JsonObject();
				resp.addProperty("success", true);
				LlamaServer.sendJsonResponse(ctx, resp);
				return;
			}
			if (manager.isLoading(modelId)) {
				JsonObject resp = new JsonObject();
				resp.addProperty("success", true);
				LlamaServer.sendJsonResponse(ctx, resp);
				return;
			}

			logger.info("[llama.cpp API] 加载模型: modelId={}", modelId);
			String err = manager.autoLoadModelFromConfig(modelId, 600000);
			if (err != null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, err);
				return;
			}

			JsonObject resp = new JsonObject();
			resp.addProperty("success", true);
			LlamaServer.sendJsonResponse(ctx, resp);
		} catch (Exception e) {
			logger.info("加载模型时发生错误", e);
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "加载模型失败: " + e.getMessage());
		}
	}

	private void handleUnloadModel(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体为空");
				return;
			}

			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体解析失败");
				return;
			}

			String modelId = JsonUtil.getJsonString(obj, "model");
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "缺少必需的model参数");
				return;
			}
			modelId = modelId.trim();

			LlamaServerManager manager = LlamaServerManager.getInstance();

			if (!manager.getLoadedProcesses().containsKey(modelId)) {
				JsonObject resp = new JsonObject();
				resp.addProperty("success", true);
				LlamaServer.sendJsonResponse(ctx, resp);
				return;
			}

			logger.info("[llama.cpp API] 卸载模型: modelId={}", modelId);
			boolean success = manager.stopModel(modelId);
			if (!success) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "模型停止失败或模型未加载");
				return;
			}

			JsonObject resp = new JsonObject();
			resp.addProperty("success", true);
			LlamaServer.sendJsonResponse(ctx, resp);
		} catch (Exception e) {
			logger.info("卸载模型时发生错误", e);
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "卸载模型失败: " + e.getMessage());
		}
	}

	private void handleTools(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

		DefaultMcpServiceImpl mcpService = LlamaServer.getMcpServerService();
		if (mcpService == null) {
			LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.OK, new JsonArray(), true);
			return;
		}

		try {
			org.mark.test.mcp.struct.McpToolRegistry registry = mcpService.getToolRegistry();
			if (registry == null) {
				LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.OK, new JsonArray(), true);
				return;
			}

			JsonArray result = new JsonArray();
			List<IMCPTool> tools = registry.resolve("llama_hub_info");
			if (tools == null || tools.isEmpty()) {
				LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.OK, result, true);
				return;
			}

			for (IMCPTool tool : tools) {
				if (tool == null) {
					continue;
				}

				JsonObject item = new JsonObject();
				item.addProperty("display_name", tool.getMcpTitle() != null && !tool.getMcpTitle().isBlank() ? tool.getMcpTitle() : tool.getMcpName());
				item.addProperty("tool", tool.getMcpName());
				item.addProperty("type", "builtin");

				JsonObject permissions = new JsonObject();
				permissions.addProperty("write", tool.isWritePermission());
				item.add("permissions", permissions);

				JsonObject definition = new JsonObject();
				definition.addProperty("type", "function");

				JsonObject func = new JsonObject();
				func.addProperty("name", tool.getMcpName());
				String desc = tool.getMcpDescription();
				if (desc != null && !desc.isBlank()) {
					func.addProperty("description", desc);
				}

				McpToolInputSchema schema = tool.getInputSchema();
				if (schema != null) {
					JsonObject schemaJson = schema.toJsonObject();
					func.add("parameters", schemaJson);
				} else {
					JsonObject emptyParams = new JsonObject();
					emptyParams.addProperty("type", "object");
					emptyParams.add("properties", new JsonObject());
					func.add("parameters", emptyParams);
				}

				definition.add("function", func);
				item.add("definition", definition);
				result.add(item);
			}

			LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.OK, result, true);
		} catch (Exception e) {
			logger.info("获取工具列表失败", e);
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "获取工具列表失败: " + e.getMessage());
		}
	}
}
