package org.mark.llamacpp.server.channel;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.service.AnthropicService;
import org.mark.llamacpp.server.service.OpenAIService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 	服务端的主要实现。
 */
public class LlamaRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(LlamaRouterHandler.class);

	private static final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
	
	/**
	 * 	OpenAI接口的实现。
	 */
	private OpenAIService openAIServerHandler = new OpenAIService();
	
	/**
	 * 	Anthropic接口的实现。
	 */
	private AnthropicService anthropicService = new AnthropicService();
	
	public LlamaRouterHandler() {

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

	private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		String uri = request.uri();
		
		// 处理CORS预检请求
		if (request.method() == HttpMethod.OPTIONS) {
			this.handleCorsPreflight(ctx, request);
			return;
		}
		
		this.handleApiRequest(ctx, request, uri);
		return;
	}

	/**
	 * 处理API请求
	 */
    private void handleApiRequest(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
		try {
			// 验证key
			if (uri.startsWith("/v1") && request.method() != HttpMethod.OPTIONS) {
				if (!this.validateApiKey(request)) {
					LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.UNAUTHORIZED, "invalid api key");
					return;
				}
			}
			
			// OpenAI API 端点
			// 获取模型列表
			if (uri.startsWith("/llama.cpp/v1/models")) {
				this.openAIServerHandler.handleOpenAIModelsRequest(ctx, request);
				return;
			}
			if (uri.startsWith("/v1/models") || uri.startsWith("/models")) {
				if (this.isAnthropicClient(request)) {
					this.anthropicService.handleModelsRequest(ctx, request);
				} else {
					this.openAIServerHandler.handleOpenAIModelsRequest(ctx, request);
				}
				return;
			}
			// 文本补全
			if (uri.startsWith("/v1/completions") || uri.startsWith("/completions")) {
				this.openAIServerHandler.handleOpenAICompletionsRequest(ctx, request);
				return;
			}
			if (uri.startsWith("/v1/embeddings") || uri.startsWith("/embeddings")) {
				this.openAIServerHandler.handleOpenAIEmbeddingsRequest(ctx, request);
				return;
			}
			if (uri.startsWith("/v1/responses") || uri.startsWith("/responses")) {
				this.openAIServerHandler.handleOpenAIResponsesRequest(ctx, request);
				return;
			}
			if (uri.startsWith("/v1/rerank") || uri.startsWith("/v1/reranking") || uri.startsWith("/rerank") || uri.startsWith("/reranking")) {
				this.openAIServerHandler.handleOpenAIRerankRequest(ctx, request);
				return;
			}
			// 音频
			if(uri.startsWith("/v1/audio/transcriptions") || uri.startsWith("/audio/transcriptions")) {
				this.openAIServerHandler.handleOpenAIAudioTranscriptionsRequest(ctx, request);
				return;
			}
			
			// Anthropic API 端点 (Messages)
			if (uri.startsWith("/v1/messages/count_tokens")) {
				this.anthropicService.handleMessagesCountTokensRequest(ctx, request);
				return;
			}
			// /llama.cpp/slots and /slots - proxy to model's /slots endpoint
			if (uri.startsWith("/llama.cpp/slots") || uri.startsWith("/slots")) {
				this.handleSlotsRequest(ctx, request);
				return;
			}

			this.sendJsonResponse(ctx, ApiResponse.error("404 Not Found"));
		} catch (Exception e) {
			logger.info("处理API请求时发生错误", e);
			this.sendJsonResponse(ctx, ApiResponse.error("服务器内部错误"));
		}
    }
    
    /**
     * 处理CORS预检请求
     */
    private void handleCorsPreflight(ChannelHandlerContext ctx, FullHttpRequest request) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        
        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                ctx.close();
            }
        });
    }

 /**
  *
  * @param ctx
  * @param data
  */
 private void sendJsonResponse(ChannelHandlerContext ctx, Object data) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(CharsetUtil.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		// 添加CORS头
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization");
		response.content().writeBytes(content);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		// 事件通知
		this.openAIServerHandler.channelInactive(ctx);
		this.anthropicService.channelInactive(ctx);
		super.channelInactive(ctx);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		logger.info("处理请求时发生异常", cause);
		ctx.close();
	}
	
	/**
	 * 	做判断
	 * @param request
	 * @return
	 */
	private boolean validateApiKey(FullHttpRequest request) {
		if (!LlamaServer.isApiKeyValidationEnabled()) {
			return true;
		}
		String expected = LlamaServer.getApiKey();
		if (expected == null || expected.isBlank()) {
			return false;
		}

		String auth = request.headers().get(HttpHeaderNames.AUTHORIZATION);
		if (auth != null) {
			auth = auth.replace("Bearer ", "");
			return auth.equals(expected);
		}

		String apiKey = request.headers().get("x-api-key");
		if (apiKey != null && !apiKey.isBlank()) {
			return apiKey.equals(expected);
		}

		return false;
	}

	private boolean isAnthropicClient(FullHttpRequest request) {
		String anthropicVersion = request.headers().get("anthropic-version");
		if (anthropicVersion != null && !anthropicVersion.isBlank()) {
			return true;
		}
		if (request.headers().contains("x-api-key")) {
			return true;
		}
		return false;
	}

	/**
	 * 处理 /llama.cpp/slots 和 /slots 请求，代理到对应模型的 /slots 端点。
	 * 用法：/llama.cpp/slots?model=Qwen3.5-0.8B-Q4_K_M
	 */
	private void handleSlotsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.GET) {
			this.sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
			return;
		}
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String model = params.get("model");
			if (model == null || model.trim().isEmpty()) {
				this.sendJsonResponse(ctx, ApiResponse.error("缺少必需的model参数"));
				return;
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (!manager.getLoadedProcesses().containsKey(model)) {
				this.sendJsonResponse(ctx, ApiResponse.error("模型未加载: " + model));
				return;
			}
			com.google.gson.JsonObject result = manager.handleModelSlotsGet(model);
			this.sendJsonResponse(ctx, ApiResponse.success(result));
		} catch (Exception e) {
			logger.info("获取slots信息时发生错误", e);
			this.sendJsonResponse(ctx, ApiResponse.error("获取slots信息失败: " + e.getMessage()));
		}
	}
}
