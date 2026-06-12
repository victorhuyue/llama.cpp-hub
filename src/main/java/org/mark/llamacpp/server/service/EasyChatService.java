package org.mark.llamacpp.server.service;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.io.NettyWriteHelper;
import org.mark.llamacpp.server.struct.ActiveRequest;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.Timing;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

public class EasyChatService {

	private static final Logger logger = LoggerFactory.getLogger(EasyChatService.class);
	private static final int STREAM_TIMEOUT_MS = 36000 * 1000;
	private static final boolean ENABLE_REQUEST_LOG = false;

	private static EasyChatService instance;

	public static EasyChatService getInstance() {
		if (instance == null) {
			instance = new EasyChatService();
		}
		return instance;
	}

	private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
	private final Map<ChannelHandlerContext, TrackedConnection> channelConnectionMap = new ConcurrentHashMap<>();
	private final Map<String, Object> conversationLocks = new ConcurrentHashMap<>();
	private final EasyChatStorage storage = new EasyChatStorage();
	private final EasyChatRequestWriter requestWriter = new EasyChatRequestWriter(storage);
	private final EasyChatGlobalLock globalLock = EasyChatGlobalLock.getInstance();

	private EasyChatService() {
	}

   /**
     * Decode a URL-encoded header value. Returns the original string if decoding fails.
     */
    private static String decodeHeader(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

	private static String readTerminalFinishReason(JsonObject json) {
		if (json == null || !json.has("choices") || !json.get("choices").isJsonArray()) {
			return null;
		}
		JsonArray choices = json.getAsJsonArray("choices");
		if (choices.size() == 0 || !choices.get(0).isJsonObject()) {
			return null;
		}
		JsonObject choice = choices.get(0).getAsJsonObject();
		if (!choice.has("finish_reason") || choice.get("finish_reason").isJsonNull()) {
			return null;
		}
		try {
			String finishReason = choice.get("finish_reason").getAsString();
			return finishReason == null || finishReason.isBlank() ? null : finishReason.trim();
		} catch (Exception ignore) {
			return null;
		}
	}

    /* ---- Public API ---- */

	/**
	 * Handle stream-chat request.
	 * Reads metadata from HTTP headers, body bytes written directly to fragment (no JSON parse),
	 * validates, acquires lock, allocates sequences, writes user fragment,
	 * then dispatches async processing.
	 */
	public void handleStreamChat(ChannelHandlerContext ctx, FullHttpRequest request) {
		EasyChatGlobalLock.Lease globalLease = null;
		try {
			if (request.method() != HttpMethod.POST) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}
			globalLease = acquireGlobalLease(ctx, "chat.stream");
			if (globalLease == null) {
				return;
			}
        // Read metadata from headers
            String hdrConvId = decodeHeader(request.headers().get("X-Conversation-Id"));
            String conversationId = (hdrConvId != null) ? hdrConvId : "";
			if (conversationId.isBlank()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少conversationId"));
				return;
			}

       String hdrModelId = decodeHeader(request.headers().get("X-Model-Id"));
            String modelId = (hdrModelId != null) ? hdrModelId : "";
			if (modelId.isBlank()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少modelId"));
				return;
			}

      String hdrAsstName = decodeHeader(request.headers().get("X-Assistant-Name"));
            String assistantName = (hdrAsstName != null) ? hdrAsstName : "";

			// Read body bytes directly (no JSON parsing)
			byte[] bodyBytes = new byte[request.content().readableBytes()];
			request.content().readBytes(bodyBytes);

			// Parse optional small headers (safe, tiny)
       JsonArray toolsArr = null;
            String toolsHeader = decodeHeader(request.headers().get("X-Tools"));
			if (toolsHeader != null && !toolsHeader.isBlank()) {
				try {
					JsonElement el = JsonUtil.fromJson(toolsHeader, JsonElement.class);
					if (el != null && el.isJsonArray()) {
						toolsArr = el.getAsJsonArray();
					}
				} catch (Exception e) {
					logger.warn("[EasyChat] 解析X-Tools header失败", e);
				}
			}

			String toolChoice = request.headers().get("X-Tool-Choice");
			if (toolChoice == null || toolChoice.isBlank()) {
				toolChoice = "auto";
			}

			String ephemeralMode = decodeHeader(request.headers().get("X-Ephemeral-Mode"));
			boolean isEphemeral = ephemeralMode != null && !ephemeralMode.isBlank();
			String streamHeader = decodeHeader(request.headers().get("X-Stream"));
			boolean requestStream = streamHeader == null || streamHeader.isBlank()
				|| Boolean.parseBoolean(streamHeader.trim());

       JsonObject samplingParams = null;
            String spHeader = decodeHeader(request.headers().get("X-Sampling-Params"));
			if (spHeader != null && !spHeader.isBlank()) {
				try {
					JsonElement el = JsonUtil.fromJson(spHeader, JsonElement.class);
					if (el != null && el.isJsonObject()) {
						samplingParams = el.getAsJsonObject();
					}
				} catch (Exception e) {
					logger.warn("[EasyChat] 解析X-Sampling-Params header失败", e);
				}
			}

			// Read X-Node-Id header for remote node routing
			String nodeId = decodeHeader(request.headers().get("X-Node-Id"));
			if (nodeId != null) {
				nodeId = nodeId.trim();
			}
			boolean isRemoteNode = nodeId != null && !nodeId.isEmpty();
			logger.info("[EasyChat][Node路由] X-Node-Id={}, isRemoteNode={}, modelId={}", nodeId, isRemoteNode, modelId);

			// Resolve model port (local only; remote nodes skip this)
			Integer modelPort = null;
			if (!isRemoteNode) {
				LlamaServerManager manager = LlamaServerManager.getInstance();
				if (!manager.getLoadedProcesses().containsKey(modelId)) {
					String resolved = manager.findModelIdByAlias(modelId);
					if (resolved != null) {
						modelId = resolved;
					}
				}
				if (!manager.getLoadedProcesses().containsKey(modelId)) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("模型未找到: " + modelId));
					return;
				}
				modelPort = manager.getModelPort(modelId);
				if (modelPort == null) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("模型端口未找到: " + modelId));
					return;
				}
			}

			// Resolve system prompt from synced assistant config.
			String systemPrompt = resolveAssistantSystemPrompt(assistantName);

			// Parse regenerate headers
			Long regenerateSeq = null;
			String regHeader = request.headers().get("X-Regenerate-Id");
			if (regHeader != null && !regHeader.isBlank()) {
				try {
					regenerateSeq = Long.parseLong(regHeader);
				} catch (NumberFormatException e) {
					logger.warn("[EasyChat] 解析X-Regenerate-Id失败: {}", regHeader);
				}
			}

			Map<Long, Integer> variants = null;
			String variantsHeader = request.headers().get("X-Variants");
			if (variantsHeader != null && !variantsHeader.isBlank()) {
				variants = new HashMap<>();
				for (String pair : variantsHeader.split(",")) {
					String[] parts = pair.split(":");
					if (parts.length == 2) {
						try {
							long vSeq = Long.parseLong(parts[0].trim());
							int vIdx = Integer.parseInt(parts[1].trim());
							variants.put(vSeq, vIdx);
						} catch (NumberFormatException e) {
							logger.warn("[EasyChat] 解析X-Variants pair失败: {}", pair);
						}
					}
				}
			}

			boolean isRegenerate = regenerateSeq != null;
			if (isRegenerate) {
				// For regenerate: no body needed, backend reads user message from fragment
				// bodyBytes can be empty
			} else if (bodyBytes.length == 0) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			// Per-conversation lock
			Object convLock = conversationLocks.computeIfAbsent(conversationId, k -> new Object());

			Path convDir = isEphemeral ? null : storage.getConversationDir(conversationId);

			long userSeq;
			long aiSeq;
			byte[] toolsBytes;

			synchronized (convLock) {
				if (isEphemeral) {
					userSeq = -1;
					aiSeq = -1;
				} else {
					Path indexPath = storage.indexFile(convDir);
					storage.ensureIndex(convDir, assistantName);
					long idxSeq = storage.readIndexSeq(indexPath);

					if (isRegenerate) {
						// Regenerate: reuse existing AI seq, don't write user fragment
						userSeq = -1;
						aiSeq = regenerateSeq;
					} else {
						userSeq = idxSeq;
						aiSeq = idxSeq + 1;
						storage.writeIndexSeq(indexPath, idxSeq + 2);

						// Write user fragment (raw body bytes, no JSON parsing)
						storage.writeFragment(convDir, userSeq, System.currentTimeMillis(), bodyBytes);
					}
				}

				// Write tools.bin if tools provided via header
				if (toolsArr != null) {
					JsonObject toolsObj = new JsonObject();
					toolsObj.add("tools", toolsArr);
					toolsObj.addProperty("tool_choice", toolChoice);
					toolsBytes = JsonUtil.toJson(toolsObj).getBytes(StandardCharsets.UTF_8);
					if (!isEphemeral) {
						storage.writeTools(convDir, toolsBytes);
					}
				} else {
					toolsBytes = isEphemeral ? null : storage.readTools(convDir);
				}

				// Persist X-Variants to fragment headers
				if (!isEphemeral && variants != null) {
					for (Map.Entry<Long, Integer> entry : variants.entrySet()) {
						storage.writeActiveVariantIndex(convDir, entry.getKey(), entry.getValue());
					}
				}
			}
			// Create effectively final copies for lambda capture
			final String finalModelId = modelId;
			final int finalModelPort = modelPort == null ? 0 : modelPort;
			final String finalNodeId = nodeId;
			final boolean finalIsRemoteNode = isRemoteNode;
			final String finalSystemPrompt = systemPrompt;
			final Path finalConvDir = convDir;
			final byte[] finalToolsBytes = toolsBytes;
			final JsonObject finalSamplingParams = samplingParams;
			final boolean finalIsRegenerate = isRegenerate;
			final boolean finalIsEphemeral = isEphemeral;
			final Map<Long, Integer> finalVariants = variants;
			final Long finalRegenerateSeq = regenerateSeq;
			final byte[] finalBodyBytes = bodyBytes;
			// For persisted chats, the current message has already been written to fragments
			// and will be replayed from history. Only ephemeral requests should append the
			// transient body directly to the model request.
			final byte[] finalTransientBodyBytes = finalIsEphemeral ? finalBodyBytes : null;
			final boolean finalRequestStream = requestStream;

			// Dispatch to worker thread
			final EasyChatGlobalLock.Lease finalGlobalLease = globalLease;
			worker.execute(() -> {
				HttpURLConnection connection = null;
				StreamAccumulator accumulator = new StreamAccumulator();
				Path indexPath = finalConvDir == null ? null : storage.indexFile(finalConvDir);
				try {
					if (finalIsRemoteNode) {
						handleRemoteNodeRequest(ctx, conversationId, finalNodeId, finalModelId,
							finalSystemPrompt, finalConvDir, finalToolsBytes, finalSamplingParams,
							finalVariants, finalRegenerateSeq, finalTransientBodyBytes, finalIsEphemeral, finalRequestStream, accumulator);
					} else {
						connection = openTrackedConnection(ctx, finalModelId, finalModelPort);

                       // Stream request body to llama.cpp
                        writeRequestBody(connection, conversationId, finalModelId, finalSystemPrompt, finalConvDir,
							finalToolsBytes, finalSamplingParams, finalVariants, finalRegenerateSeq,
							finalTransientBodyBytes, finalIsEphemeral, finalRequestStream);

						int responseCode = connection.getResponseCode();

						if (!(responseCode >= 200 && responseCode < 300)) {
							String errBody = readErrorBody(connection);
							logger.info("[EasyChat] llama.cpp错误响应 code={} body={}", responseCode, errBody);
							sendErrorResponse(ctx, responseCode, errBody);
							return;
						}

						if (finalRequestStream) {
							HttpResponse sseResp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
							sseResp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
							sseResp.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
							sseResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
							sseResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
							sseResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
							if (!NettyWriteHelper.writeAndFlushBlocking(ctx, sseResp, logger, "[EasyChat]")) {
								return;
							}

							proxySseStream(ctx, connection, accumulator);
						} else {
							byte[] responseBytes = connection.getInputStream().readAllBytes();
							String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
							accumulateNonStreamResponse(JsonUtil.tryParseObject(responseBody), accumulator);
							sendJsonPayloadResponse(ctx, responseCode, connection.getContentType(), responseBytes);
						}
					}

					// Write AI fragment and update state (always, if buffer has content)
					if (!finalIsEphemeral) {
						synchronized (convLock) {
							if (accumulator.hasContent()) {
								JsonObject aiMsg = buildAiMessage(accumulator);
								byte[] aiBytes = JsonUtil.toJson(aiMsg).getBytes(StandardCharsets.UTF_8);
								boolean wroteNewAssistantFragment = persistAssistantFragment(finalConvDir, aiSeq, aiBytes, finalIsRegenerate, indexPath);
								if (!finalIsRegenerate) {
									updateStateMessageCount(conversationId, 2);
								} else if (wroteNewAssistantFragment) {
									updateStateMessageCount(conversationId, 1);
								}
							}
						}
					}

					// Record usage to LlamaRecordService
					if (accumulator.timings != null) {
						try {
							Timing timing = timingFromJson(accumulator.timings);
							ActiveRequest activeReq = new ActiveRequest(conversationId, finalModelId, "chat/completions");
							activeReq.setTiming(timing);
							activeReq.setStatus(ActiveRequest.RequestStatus.COMPLETED);
							activeReq.setPhase(ActiveRequest.Phase.GENERATION);
							LlamaRecordService.getInstance().recordRequest(activeReq);
						} catch (Exception e) {
							logger.warn("[EasyChat] 记录用量失败 conversation={}", conversationId, e);
						}
					}

					if (finalRequestStream) {
						if (ctx.channel().isActive()) {
							ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
								.addListener(ChannelFutureListener.CLOSE);
						}
					}

				} catch (Exception e) {
					logger.info("[EasyChat] 处理流式聊天失败 conversation={}", conversationId, e);
					// Still write fragment if we have partial content
					try {
						if (!finalIsEphemeral) {
							synchronized (convLock) {
								if (accumulator.hasContent()) {
									JsonObject aiMsg = buildAiMessage(accumulator);
									byte[] aiBytes = JsonUtil.toJson(aiMsg).getBytes(StandardCharsets.UTF_8);
									boolean wroteNewAssistantFragment = persistAssistantFragment(finalConvDir, aiSeq, aiBytes, finalIsRegenerate, indexPath);
									if (finalIsRegenerate && wroteNewAssistantFragment) {
										updateStateMessageCount(conversationId, 1);
									}
								}
							}
						}
					} catch (Exception ex) {
						logger.warn("[EasyChat] 异常状态下写入AI碎片失败", ex);
					}
					// Record usage even on error if timings available
					if (accumulator.timings != null) {
						try {
							Timing timing = timingFromJson(accumulator.timings);
							ActiveRequest activeReq = new ActiveRequest(conversationId, finalModelId, "chat/completions");
							activeReq.setTiming(timing);
							activeReq.setStatus(ActiveRequest.RequestStatus.FAILED);
							activeReq.setPhase(ActiveRequest.Phase.GENERATION);
							LlamaRecordService.getInstance().recordRequest(activeReq);
						} catch (Exception ex) {
							logger.warn("[EasyChat] 异常状态下记录用量失败 conversation={}", conversationId, ex);
						}
					}
					sendOpenAIError(ctx, 500, e.getMessage());
				} finally {
					cleanupConnection(ctx);
					finalGlobalLease.close();
				}
			});
			globalLease = null;

		} catch (Exception e) {
			logger.info("[EasyChat] 处理stream-chat请求失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("处理失败: " + e.getMessage()));
		} finally {
			if (globalLease != null) {
				globalLease.close();
			}
		}
	}

	/**
	 * Stream conversation history as chunked JSON with zero-copy file regions.
	 * Payload bytes leave disk via sendfile/splice and never enter JVM heap.
	 * Returns all variants for AI messages that have been regenerated.
	 */
	public void handleStreamChatHistory(ChannelHandlerContext ctx, String conversationId) {
		EasyChatGlobalLock.Lease globalLease = acquireGlobalLease(ctx, "chat.history");
		if (globalLease == null) {
			return;
		}
		if (conversationId == null || conversationId.isBlank()) {
			sendHistoryError(ctx, "缺少conversationId");
			globalLease.close();
			return;
		}

		Path convDir;
		try {
			convDir = storage.getConversationDir(conversationId);
		} catch (IOException e) {
			logger.info("[EasyChat] 获取碎片目录失败 conversation={}", conversationId, e);
			sendHistoryError(ctx, "获取目录失败: " + e.getMessage());
			globalLease.close();
			return;
		}

		// Phase 1: pre-scan metadata (read header for count/lengths)
		long recordCount = 0;
		long totalSize = 0;
		long variantCount = 0;
		try {
			long endExclusive = storage.readNextSeq(convDir);
			for (long seq = 0; seq < endExclusive; seq++) {
				EasyChatStorage.FragmentHeader header = storage.readFragmentHeader(convDir, seq);
				if (header == null) {
					continue;
				}
				if (!storage.isDeleted(header)) {
					for (int v = 0; v < header.variantCount; v++) {
						totalSize += Math.max(0, header.lengths[v]);
						if (v > 0) {
							variantCount++;
						}
					}
					recordCount++;
				}
			}
		} catch (Exception e) {
			logger.info("[EasyChat] 预扫描碎片失败 conversation={}", conversationId, e);
			sendHistoryError(ctx, "扫描碎片失败: " + e.getMessage());
			globalLease.close();
			return;
		}

		// Phase 2: build JSON prefix
		String prefix = "{\"message\":\"success\",\"totalSize\":" + totalSize
			+ ",\"recordCount\":" + recordCount + ",\"variantCount\":" + variantCount + ",\"data\":[";

		// Start chunked response
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		ctx.writeAndFlush(response);
		ctx.writeAndFlush(Unpooled.wrappedBuffer(prefix.getBytes(StandardCharsets.UTF_8)));

		// Phase 3: zero-copy fragment loop, writeAndFlush for ordering
		byte[] comma = ",".getBytes(StandardCharsets.UTF_8);
		byte[] variantPrefix = "{\"content\":".getBytes(StandardCharsets.UTF_8);
		byte[] variantSuffix = "}".getBytes(StandardCharsets.UTF_8);
		byte[] msgSuffix = "]}" .getBytes(StandardCharsets.UTF_8);
		byte[] dataSuffix = "]}".getBytes(StandardCharsets.UTF_8);
		try {
			long endExclusive = storage.readNextSeq(convDir);
			boolean first = true;
			for (long seq = 0; seq < endExclusive; seq++) {
				EasyChatStorage.FragmentHeader header = storage.readFragmentHeader(convDir, seq);
				if (header == null) {
					continue;
				}
				if (storage.isDeleted(header)) {
					continue;
				}

				int roleVariantIndex = storage.resolveVariantIndex(header, 0);
				byte[] firstPayload = roleVariantIndex < 0 ? new byte[0] : storage.readPayload(convDir, seq, roleVariantIndex);

				if (!first) {
					ctx.writeAndFlush(Unpooled.wrappedBuffer(comma));
				}
				first = false;

				String role = "unknown";
				if (firstPayload != null && firstPayload.length > 0) {
					try {
						JsonObject msgObj = JsonUtil.tryParseObject(new String(firstPayload, StandardCharsets.UTF_8));
						if (msgObj != null && msgObj.has("role")) {
							role = msgObj.get("role").getAsString();
						}
					} catch (Exception e) {
						// ignore
					}
				}

				// {"seq":N,"role":"R","activeVariant":N,"variants":[
				int activeVariant = storage.resolveVariantIndex(header, header.activeVariantIndex);
				if (activeVariant < 0) {
					activeVariant = 0;
				}
				String msgPrefix = "{\"seq\":" + seq + ",\"role\":\"" + escapeJsonString(role)
					+ "\",\"activeVariant\":" + activeVariant + ",\"variants\":[";
				ctx.writeAndFlush(Unpooled.wrappedBuffer(msgPrefix.getBytes(StandardCharsets.UTF_8)));

				// All variants
				for (int v = 0; v < header.variantCount; v++) {
					if (v > 0) {
						ctx.writeAndFlush(Unpooled.wrappedBuffer(comma));
					}
					ctx.writeAndFlush(Unpooled.wrappedBuffer(variantPrefix));
					EasyChatStorage.FragmentSlice slice = storage.getVariantSlice(convDir, seq, v);
					if (slice != null && slice.length > 0) {
						ctx.writeAndFlush(new DefaultFileRegion(slice.file.toFile(), slice.offset, slice.length));
					} else {
						ctx.writeAndFlush(Unpooled.wrappedBuffer("null".getBytes(StandardCharsets.UTF_8)));
					}
					ctx.writeAndFlush(Unpooled.wrappedBuffer(variantSuffix));
				}

				ctx.writeAndFlush(Unpooled.wrappedBuffer(msgSuffix));
			}
			ctx.writeAndFlush(Unpooled.wrappedBuffer(dataSuffix));
			logger.info("[EasyChat] 流式传输历史完成 conversation={} records={} variants={} bytes={}",
				conversationId, recordCount, variantCount, totalSize);
			final EasyChatGlobalLock.Lease finalGlobalLease = globalLease;
			ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(future -> {
				finalGlobalLease.close();
				ctx.close();
			});
			globalLease = null;
		} catch (Exception e) {
			logger.info("[EasyChat] 流式传输历史失败 conversation={}", conversationId, e);
			ctx.close();
		} finally {
			if (globalLease != null) {
				globalLease.close();
			}
		}
	}

	private void sendHistoryError(ChannelHandlerContext ctx, String msg) {
		byte[] body = ("{\"message\":\"" + msg + "\",\"totalSize\":0,\"recordCount\":0,\"variantCount\":0,\"data\":[]}")
			.getBytes(StandardCharsets.UTF_8);
		FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
		resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		resp.content().writeBytes(body);
		ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
	}

	private EasyChatGlobalLock.Lease acquireGlobalLease(ChannelHandlerContext ctx, String operationName) {
		EasyChatGlobalLock.Lease lease = globalLock.tryAcquire(operationName);
		if (lease != null) {
			return lease;
		}
		sendGlobalLockBusy(ctx, operationName);
		return null;
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

	/* ---- Connection management ---- */

	@FunctionalInterface
	private interface TrackedConnection {
		void close();
	}

	private HttpURLConnection openTrackedConnection(ChannelHandlerContext ctx, String modelId, int port) throws IOException {
		String targetUrl = String.format("http://localhost:%d/v1/chat/completions", port);
		HttpURLConnection connection = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setConnectTimeout(36000000);
		connection.setReadTimeout(36000000);
		connection.setDoOutput(true);
		connection.setChunkedStreamingMode(8192);
		channelConnectionMap.put(ctx, connection::disconnect);
		return connection;
	}

	private void trackConnection(ChannelHandlerContext ctx, TrackedConnection connection) {
		if (connection == null) {
			return;
		}
		channelConnectionMap.put(ctx, connection);
	}

	private void cleanupConnection(ChannelHandlerContext ctx) {
		TrackedConnection tracked = channelConnectionMap.remove(ctx);
		if (tracked != null) {
			try {
				tracked.close();
			} catch (Exception ignore) {
			}
		}
	}

   /**
     * Create a FileOutputStream for request logging.
     * Returns null if ENABLE_REQUEST_LOG is false or file creation fails (non-fatal).
     */
    private OutputStream createRequestLogStream(String conversationId, String modelId) {
        if (!ENABLE_REQUEST_LOG) {
            return null;
        }
        try {
            Path logDir = LlamaServer.getCachePath().resolve("easy-chat");
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }
            String safeModel = modelId.replace("/", "_").replace("\\", "_");
            String safeConv = conversationId.replace("/", "_").replace("\\", "_");
            String filename = safeModel + "-" + safeConv + "-request-" + System.currentTimeMillis() + ".log";
            Path logFile = logDir.resolve(filename);
            logger.info("[EasyChat][RequestLog] {}", logFile);
            return new FileOutputStream(logFile.toFile());
        } catch (Exception e) {
            logger.warn("[EasyChat][RequestLog] 创建日志文件失败", e);
            return null;
        }
    }

    /**
     *  在这构建请求体。
     * @param conn
     * @param conversationId
     * @param modelId
     * @param systemPrompt
     * @param convDir
     * @param toolsBytes
     * @param samplingParams
     * @param variants
     * @param regenerateSeq
     * @throws IOException
     */
    private void writeRequestBody(HttpURLConnection conn, String conversationId, String modelId, String systemPrompt, Path convDir,
            byte[] toolsBytes, JsonObject samplingParams, Map<Long, Integer> variants, Long regenerateSeq,
            byte[] transientUserMessage, boolean skipHistory, boolean stream)
            throws IOException {
        OutputStream logStream = createRequestLogStream(conversationId, modelId);
        OutputStream primary = conn.getOutputStream();
        OutputStream os = (logStream != null) ? new TeeOutputStream(primary, logStream) : primary;
        try {
            requestWriter.writeRequestBody(os,
                new EasyChatRequestWriter.RequestSpec(modelId, systemPrompt, convDir, toolsBytes,
                    samplingParams, false, variants, regenerateSeq, transientUserMessage, skipHistory, stream));
        } finally {
            if (logStream != null) {
                logStream.close();
            }
        }
    }

	private boolean persistAssistantFragment(Path convDir, long aiSeq, byte[] aiBytes, boolean isRegenerate, Path indexPath)
			throws IOException {
		if (convDir == null || aiSeq < 0 || aiBytes == null) {
			return false;
		}
		EasyChatStorage.FragmentHeader existingHeader = storage.readFragmentHeader(convDir, aiSeq);
		if (isRegenerate && existingHeader != null) {
			if (storage.isDeleted(existingHeader)) {
				storage.clearDeletedFlag(convDir, aiSeq);
			}
			storage.appendVariant(convDir, aiSeq, aiBytes);
			return false;
		}
		if (isRegenerate && existingHeader == null) {
			logger.warn("[EasyChat] regenerate目标碎片不存在，降级为新写入 seq={}", aiSeq);
		}
		storage.writeFragment(convDir, aiSeq, System.currentTimeMillis(), aiBytes);
		if (indexPath != null) {
			storage.writeIndexSeq(indexPath, aiSeq + 1);
		}
		return true;
	}

	private String escapeJsonString(String s) {
		if (s == null) return "";
		StringBuilder sb = new StringBuilder(s.length() + 16);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"': sb.append('\\').append('"'); break;
				case '\\': sb.append('\\').append('\\'); break;
				case '\n': sb.append('\\').append('n'); break;
				case '\r': sb.append('\\').append('r'); break;
				case '\t': sb.append('\\').append('t'); break;
				case '\b': sb.append('\\').append('b'); break;
				case '\f': sb.append('\\').append('f'); break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
					break;
			}
		}
		return sb.toString();
	}

	/* ---- SSE streaming ---- */

	/**
	 * Accumulator for streaming AI response content.
	 */
	private static final class StreamAccumulator {
		final StringBuilder content = new StringBuilder();
		final StringBuilder reasoningContent = new StringBuilder();
		// Accumulated tool calls: index -> {id, type, function:{name, arguments}}
		final Map<Integer, JsonObject> toolCalls = new HashMap<>();
		JsonObject timings = null;

		boolean hasContent() {
			return content.length() > 0 || reasoningContent.length() > 0 || !toolCalls.isEmpty();
		}
	}

	public static final class RemoteStreamTrace {
		public final long startedAt = System.currentTimeMillis();
		public long firstLineAt = -1L;
		public long lastLineAt = -1L;
		public long lastDataEventAt = -1L;
		public long lastUsefulDeltaAt = -1L;
		public long doneReceivedAt = -1L;
		public long eofAt = -1L;
		public int dataEventCount = 0;
		public int nonDataLineCount = 0;
		public String terminalFinishReason = "";
		public String endReason;
	}

	private boolean proxySseStream(ChannelHandlerContext ctx, HttpURLConnection connection,
		StreamAccumulator accumulator) throws IOException {

		Map<Integer, String> toolCallIds = new HashMap<>();
		try (BufferedReader br = new BufferedReader(
			new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

			String line;
			while ((line = br.readLine()) != null) {
				if (!ctx.channel().isActive()) {
					logger.info("[EasyChat] 客户端断开，中止流式代理");
					return false;
				}

				if (!line.startsWith("data: ")) {
					// Pass through non-data lines
					if (!writeSseLine(ctx, line)) {
						return false;
					}
					continue;
				}

				String data = line.substring(6);
				if ("[DONE]".equals(data)) {
					logger.info("[EasyChat] 流式响应结束");
					return writeSseLine(ctx, line);
				}

				// Parse and accumulate
				JsonObject json = JsonUtil.tryParseObject(data);
				if (json != null) {
					accumulateDelta(json, accumulator, toolCallIds);
					if (json.has("timings")) {
						accumulator.timings = json.getAsJsonObject("timings");
					}
					boolean changed = JsonUtil.ensureToolCallIds(json, toolCallIds);
					if (changed) {
						line = "data: " + JsonUtil.toJson(json);
					}
					String terminalFinishReason = readTerminalFinishReason(json);
					if (terminalFinishReason != null) {
						if (!writeSseLine(ctx, line)) {
							return false;
						}
						return writeSseLine(ctx, "data: [DONE]");
					}
				}

				if (!writeSseLine(ctx, line)) {
					return false;
				}
			}
		}
		return true;
	}

	private void accumulateDelta(JsonObject json, StreamAccumulator acc, Map<Integer, String> toolCallIds) {
		if (!json.has("choices") || !json.get("choices").isJsonArray()) return;
		JsonArray choices = json.getAsJsonArray("choices");
		if (choices.size() == 0) return;
		JsonObject choice = choices.get(0).getAsJsonObject();

		// Delta
		if (!choice.has("delta") || !choice.get("delta").isJsonObject()) return;
		JsonObject delta = choice.getAsJsonObject("delta");

		// Content
		if (delta.has("content") && !delta.get("content").isJsonNull()) {
			String content = delta.get("content").getAsString();
			if (content != null && !content.isEmpty()) {
				acc.content.append(content);
			}
		}

		// Reasoning content
		if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
			String rc = delta.get("reasoning_content").getAsString();
			if (rc != null && !rc.isEmpty()) {
				acc.reasoningContent.append(rc);
			}
		}

		// Tool calls
		if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
			JsonArray tcArr = delta.getAsJsonArray("tool_calls");
			for (int i = 0; i < tcArr.size(); i++) {
				JsonObject tc = tcArr.get(i).getAsJsonObject();
				Integer idx = getToolCallIndex(tc);
				if (idx == null) continue;

				JsonObject existing = acc.toolCalls.computeIfAbsent(idx, k -> {
					JsonObject newTc = new JsonObject();
					newTc.addProperty("type", "function");
					return newTc;
				});

				// ID
				String id = JsonUtil.getJsonString(tc, "id", null);
				if (id != null && !id.isBlank() && (!existing.has("id") || existing.get("id").isJsonNull())) {
					existing.addProperty("id", id);
				}

				// Type
				String type = JsonUtil.getJsonString(tc, "type", null);
				if (type != null && !type.isBlank()) {
					existing.addProperty("type", type);
				}

				// Function
				if (tc.has("function") && tc.get("function").isJsonObject()) {
					JsonObject fn = tc.getAsJsonObject("function");
					if (!existing.has("function") || !existing.get("function").isJsonObject()) {
						existing.add("function", new JsonObject());
					}
					JsonObject existingFn = existing.getAsJsonObject("function");

					if (fn.has("name") && !fn.get("name").isJsonNull()) {
						String name = fn.get("name").getAsString();
						if (name != null && !name.isEmpty()) {
							existingFn.addProperty("name", name);
						}
					}
					if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
						String args = fn.get("arguments").getAsString();
						if (args != null && !args.isEmpty()) {
							String current = existingFn.has("arguments") ? existingFn.get("arguments").getAsString() : "";
							existingFn.addProperty("arguments", current + args);
						}
					}
				}
			}
		}
	}

	private Integer getToolCallIndex(JsonObject tc) {
		if (!tc.has("index")) return null;
		JsonElement idxEl = tc.get("index");
		if (idxEl == null || idxEl.isJsonNull()) return null;
		try {
			return idxEl.getAsInt();
		} catch (Exception e) {
			return null;
		}
	}

	private boolean writeSseLine(ChannelHandlerContext ctx, String line) {
		ByteBuf content = ctx.alloc().buffer();
		content.writeBytes(line.getBytes(StandardCharsets.UTF_8));
		content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
		return NettyWriteHelper.writeAndFlushBlocking(ctx, new DefaultHttpContent(content), logger, "[EasyChat]");
	}

	/* ---- AI message building ---- */

	private JsonObject buildAiMessage(StreamAccumulator acc) {
		JsonObject msg = new JsonObject();
		msg.addProperty("role", "assistant");

		String contentStr = acc.content.toString();
		msg.addProperty("content", contentStr);

		String reasoningStr = acc.reasoningContent.toString();
		if (!reasoningStr.isEmpty()) {
			msg.addProperty("reasoning_content", reasoningStr);
		}

		if (!acc.toolCalls.isEmpty()) {
			JsonArray tcArray = new JsonArray();
			// Sort by index to maintain order
			acc.toolCalls.entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.forEachOrdered(e -> tcArray.add(e.getValue()));
			msg.add("tool_calls", tcArray);
		}

		if (acc.timings != null) {
			msg.add("timings", acc.timings);
		}

		return msg;
	}

	/* ---- Timing conversion ---- */

	private Timing timingFromJson(JsonObject timingsJson) {
		Timing timing = new Timing();
		if (timingsJson.has("cache_n")) timing.setCache_n(timingsJson.get("cache_n").getAsInt());
		if (timingsJson.has("prompt_n")) timing.setPrompt_n(timingsJson.get("prompt_n").getAsInt());
		if (timingsJson.has("prompt_ms")) timing.setPrompt_ms(timingsJson.get("prompt_ms").getAsDouble());
		if (timingsJson.has("prompt_per_token_ms")) timing.setPrompt_per_token_ms(timingsJson.get("prompt_per_token_ms").getAsDouble());
		if (timingsJson.has("prompt_per_second")) timing.setPrompt_per_second(timingsJson.get("prompt_per_second").getAsDouble());
		if (timingsJson.has("predicted_n")) timing.setPredicted_n(timingsJson.get("predicted_n").getAsInt());
		if (timingsJson.has("predicted_ms")) timing.setPredicted_ms(timingsJson.get("predicted_ms").getAsDouble());
		if (timingsJson.has("predicted_per_token_ms")) timing.setPredicted_per_token_ms(timingsJson.get("predicted_per_token_ms").getAsDouble());
		if (timingsJson.has("predicted_per_second")) timing.setPredicted_per_second(timingsJson.get("predicted_per_second").getAsDouble());
		if (timingsJson.has("draft_n")) timing.setDraft_n(timingsJson.get("draft_n").getAsInt());
		if (timingsJson.has("draft_n_accepted")) timing.setDraft_n_accepted(timingsJson.get("draft_n_accepted").getAsInt());
		return timing;
	}

	/* ---- State update ---- */

	private void updateStateMessageCount(String conversationId, int increment) {
		try {
			Path stateDir = LlamaServer.getCachePath().resolve("easy-chat");
			Path stateFile = stateDir.resolve("state.json");
			if (!Files.exists(stateFile)) return;

			String json = Files.readString(stateFile, StandardCharsets.UTF_8);
			JsonObject state = JsonUtil.fromJson(json, JsonObject.class);
			if (state == null || !state.has("conversations")) return;

			JsonArray convs = state.getAsJsonArray("conversations");
			for (int i = 0; i < convs.size(); i++) {
				JsonElement el = convs.get(i);
				if (!el.isJsonObject()) continue;
				JsonObject conv = el.getAsJsonObject();
				String id = JsonUtil.getJsonString(conv, "id", "");
				if (conversationId.equals(id)) {
					int current = conv.has("messageCount") ? conv.get("messageCount").getAsInt() : 0;
					conv.addProperty("messageCount", current + increment);
					break;
				}
			}
			Files.writeString(stateFile, JsonUtil.toJson(state), StandardCharsets.UTF_8);
		} catch (Exception e) {
			logger.warn("[EasyChat] 更新state.json失败 conversation={}", conversationId, e);
		}
	}

	/* ---- Assistant config lookup ---- */

	private String resolveAssistantSystemPrompt(String assistantName) {
		if (assistantName == null || assistantName.isBlank()) {
			return null;
		}
		for (Path stateFile : getAssistantStateFiles()) {
			String systemPrompt = readAssistantSystemPromptFromState(stateFile, assistantName);
			if (systemPrompt != null && !systemPrompt.isBlank()) {
				return systemPrompt;
			}
		}
		logger.info("[EasyChat] 未在同步状态中找到助手 systemPrompt assistantName={}", assistantName);
		return null;
	}

	private List<Path> getAssistantStateFiles() {
		Path cachePath = LlamaServer.getCachePath().toAbsolutePath().normalize();
		return List.of(
			cachePath.resolve("easy-chat").resolve("state.json")
		);
	}

	private String readAssistantSystemPromptFromState(Path stateFile, String assistantName) {
		if (stateFile == null || assistantName == null || assistantName.isBlank() || !Files.isRegularFile(stateFile)) {
			return null;
		}
		try {
			JsonObject state = JsonUtil.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), JsonObject.class);
			if (state == null || !state.has("assistants") || !state.get("assistants").isJsonArray()) {
				return null;
			}
			JsonArray assistants = state.getAsJsonArray("assistants");
			for (JsonElement element : assistants) {
				if (element == null || !element.isJsonObject()) {
					continue;
				}
				JsonObject assistant = element.getAsJsonObject();
				String name = JsonUtil.getJsonString(assistant, "name", "");
				if (!assistantName.equals(name)) {
					continue;
				}
				String systemPrompt = JsonUtil.getJsonString(assistant, "systemPrompt", "");
				return systemPrompt == null || systemPrompt.isBlank() ? null : systemPrompt;
			}
		} catch (Exception e) {
			logger.warn("[EasyChat] 读取助手同步状态失败 stateFile={}", stateFile, e);
		}
		return null;
	}

	public Path getFragmentsDir() throws IOException {
		return storage.getFragmentsDir();
	}

	/* ---- Delete operations ---- */

	/**
	 * Delete an entire conversation: remove fragment directory.
	 */
	public void deleteConversation(String conversationId) throws Exception {
		Path fragmentsBase = getFragmentsDir();
		Path dir = fragmentsBase.resolve(conversationId);
		if (!Files.exists(dir)) {
			logger.info("[EasyChat] 删除会话: 目录不存在 conversationId={}", conversationId);
			return;
		}
		Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
			@Override
			public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return java.nio.file.FileVisitResult.CONTINUE;
			}
			@Override
			public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
				Files.delete(d);
				return java.nio.file.FileVisitResult.CONTINUE;
			}
		});
		logger.info("[EasyChat] 删除会话成功 conversationId={}", conversationId);
	}

	/**
	 * Delete a whole message or a single variant within a message.
	 */
	public void deleteMessage(String conversationId, long seq, Integer variantIndex) throws Exception {
		Path dir = storage.getConversationDir(conversationId);
		if (!Files.exists(dir)) {
			throw new IOException("Conversation directory not found: " + conversationId);
		}
		Object convLock = conversationLocks.computeIfAbsent(conversationId, k -> new Object());
		synchronized (convLock) {
			if (variantIndex != null) {
				storage.deleteVariant(dir, seq, variantIndex);
			} else {
				storage.deleteMessage(dir, seq);
			}
		}
	}

	private String readErrorBody(HttpURLConnection conn) throws IOException {
		try (BufferedReader br = new BufferedReader(
			new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
			return sb.toString();
		}
	}

	private void sendJsonPayloadResponse(ChannelHandlerContext ctx, int code, String contentType, byte[] bytes) {
		byte[] safeBytes = bytes == null ? new byte[0] : bytes;
		FullHttpResponse resp = new DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(code), Unpooled.wrappedBuffer(safeBytes));
		resp.headers().set(HttpHeaderNames.CONTENT_TYPE,
			contentType == null || contentType.isBlank() ? "application/json; charset=UTF-8" : contentType);
		resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, safeBytes.length);
		resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
	}

	private void accumulateNonStreamResponse(JsonObject json, StreamAccumulator accumulator) {
		if (json == null || accumulator == null) {
			return;
		}
		if (json.has("timings") && json.get("timings").isJsonObject()) {
			accumulator.timings = json.getAsJsonObject("timings").deepCopy();
		}
		JsonObject choice = null;
		if (json.has("choices") && json.get("choices").isJsonArray()) {
			JsonArray choices = json.getAsJsonArray("choices");
			if (choices.size() > 0 && choices.get(0).isJsonObject()) {
				choice = choices.get(0).getAsJsonObject();
			}
		}
		JsonObject message = choice != null && choice.has("message") && choice.get("message").isJsonObject()
			? choice.getAsJsonObject("message")
			: null;
		if (message != null) {
			appendJsonString(accumulator.content, message.get("content"));
			appendJsonString(accumulator.reasoningContent, message.get("reasoning_content"));
			if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
				JsonArray toolCalls = message.getAsJsonArray("tool_calls");
				for (int i = 0; i < toolCalls.size(); i++) {
					JsonElement toolCall = toolCalls.get(i);
					if (toolCall != null && toolCall.isJsonObject()) {
						accumulator.toolCalls.put(i, toolCall.getAsJsonObject().deepCopy());
					}
				}
			}
		}
	}

	private void appendJsonString(StringBuilder sb, JsonElement element) {
		if (sb == null || element == null || element.isJsonNull()) {
			return;
		}
		try {
			if (element.isJsonPrimitive()) {
				sb.append(element.getAsString());
			}
		} catch (Exception ignore) {
		}
	}

	private void sendErrorResponse(ChannelHandlerContext ctx, int code, String body) {
		FullHttpResponse resp = new DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(code));
		resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
		resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		resp.content().writeBytes(bytes);
		ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
	}

	private void sendOpenAIError(ChannelHandlerContext ctx, int httpStatus, String message) {
		String type = "server_error";
		if (httpStatus == 400) type = "invalid_request_error";
		if (httpStatus == 404) type = "invalid_request_error";
		if (httpStatus >= 500) type = "server_error";

		JsonObject error = new JsonObject();
		error.addProperty("message", message);
		error.addProperty("type", type);
		error.add("param", com.google.gson.JsonNull.INSTANCE);

		JsonObject response = new JsonObject();
		response.add("error", error);

		FullHttpResponse resp = new DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(httpStatus));
		resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		byte[] bytes = JsonUtil.toJson(response).getBytes(StandardCharsets.UTF_8);
		resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
		resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		resp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		resp.content().writeBytes(bytes);
		ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
	}

	/**
	 * Update a specific variant's payload in an existing fragment file.
	 * Reads header, replaces the target variant, rewrites the entire file.
	 */
	public void updateFragmentVariant(Path dir, long seq, int variantIndex, byte[] newPayload) throws IOException {
		storage.updateVariant(dir, seq, variantIndex, newPayload);
		logger.info("[EasyChat] 更新碎片变体成功 seq={} variantIndex={} newLength={}", seq, variantIndex, newPayload.length);
	}

	/**
	 * Handle stream-chat request routed to a remote node.
	 * Builds the request body, forwards to remote node via NodeManager, and proxies the SSE stream back.
	 */
	private void handleRemoteNodeRequest(ChannelHandlerContext ctx, String conversationId, String nodeId,
		String modelId, String systemPrompt, Path convDir, byte[] toolsBytes,
		JsonObject samplingParams, Map<Long, Integer> variants, Long regenerateSeq,
		byte[] transientUserMessage, boolean skipHistory, boolean stream,
		StreamAccumulator accumulator) throws Exception {

		logger.info("[EasyChat][Remote] 转发到远程节点: nodeId={}, model={}, conversation={}", nodeId, modelId, conversationId);

      // Forward to remote node
        NodeManager nodeManager = NodeManager.getInstance();
        OutputStream remoteLogStream = createRequestLogStream(conversationId, modelId);
        NodeManager.StreamResult streamResult = nodeManager.callRemoteApiStreaming(
            nodeId, "POST", "v1/chat/completions",
            output -> {
                OutputStream os = (remoteLogStream != null) ? new TeeOutputStream(output, remoteLogStream) : output;
                try {
                    requestWriter.writeRequestBody(os,
                        new EasyChatRequestWriter.RequestSpec(modelId, systemPrompt, convDir, toolsBytes,
                            samplingParams, false, variants, regenerateSeq, transientUserMessage, skipHistory, stream));
                } finally {
                    if (remoteLogStream != null) {
                        try {
                            remoteLogStream.close();
                        } catch (Exception ignore) {}
                    }
                }
            },
            null, STREAM_TIMEOUT_MS);
		trackConnection(ctx, streamResult::abort);

		int responseCode = streamResult.getStatusCode();
		logger.info("[EasyChat][Remote] 远程节点响应码: {} conversation={}", responseCode, conversationId);

		if (!(responseCode >= 200 && responseCode < 300)) {
			String errBody;
			try {
				errBody = new String(streamResult.getBody().readAllBytes(), StandardCharsets.UTF_8);
			} catch (Exception e) {
				errBody = "Remote node error: " + responseCode;
			}
			logger.info("[EasyChat][Remote] 远程节点错误响应 code={} body={}", responseCode, errBody);
			sendErrorResponse(ctx, responseCode, errBody);
			return;
		}

		if (!stream) {
			byte[] responseBytes = streamResult.getBody().readAllBytes();
			String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
			accumulateNonStreamResponse(JsonUtil.tryParseObject(responseBody), accumulator);
			sendJsonPayloadResponse(ctx, responseCode, "application/json; charset=UTF-8", responseBytes);
			return;
		}

		HttpResponse sseResp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		sseResp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
		sseResp.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
		sseResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
		sseResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		sseResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		if (!NettyWriteHelper.writeAndFlushBlocking(ctx, sseResp, logger, "[EasyChat][Remote]")) {
			return;
		}

		proxySseStreamFromRemote(ctx, streamResult.getBody(), accumulator);
	}

	/**
	 * Proxy SSE stream from a remote node's InputStream to the client.
	 */
	private RemoteStreamTrace proxySseStreamFromRemote(ChannelHandlerContext ctx, java.io.InputStream inputStream,
		StreamAccumulator accumulator) throws IOException {

		RemoteStreamTrace trace = new RemoteStreamTrace();
		Map<Integer, String> toolCallIds = new HashMap<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				long now = System.currentTimeMillis();
				if (trace.firstLineAt < 0L) {
					trace.firstLineAt = now;
				}
				trace.lastLineAt = now;
				if (!ctx.channel().isActive()) {
					logger.info("[EasyChat][Remote] 客户端断开，中止流式代理");
					trace.endReason = "client_inactive";
					return trace;
				}

				if (!line.startsWith("data: ")) {
					trace.nonDataLineCount += 1;
					if (!writeSseLine(ctx, line)) {
						trace.endReason = "write_failed_non_data";
						return trace;
					}
					continue;
				}

				trace.dataEventCount += 1;
				trace.lastDataEventAt = now;
				String data = line.substring(6);
				if ("[DONE]".equals(data)) {
					trace.doneReceivedAt = now;
					trace.endReason = "done";
					if (!writeSseLine(ctx, line)) {
						trace.endReason = "write_failed_done";
					}
					return trace;
				}

				JsonObject json = JsonUtil.tryParseObject(data);
				if (json != null) {
					int contentLength = accumulator.content.length();
					int reasoningLength = accumulator.reasoningContent.length();
					int toolCallCount = accumulator.toolCalls.size();
					boolean hadTimings = accumulator.timings != null;
					accumulateDelta(json, accumulator, toolCallIds);
					if (json.has("timings")) {
						accumulator.timings = json.getAsJsonObject("timings");
					}
					if (accumulator.content.length() != contentLength
						|| accumulator.reasoningContent.length() != reasoningLength
						|| accumulator.toolCalls.size() != toolCallCount
						|| (!hadTimings && accumulator.timings != null)
						|| json.has("timings")) {
						trace.lastUsefulDeltaAt = now;
					}
					boolean changed = JsonUtil.ensureToolCallIds(json, toolCallIds);
					if (changed) {
						line = "data: " + JsonUtil.toJson(json);
					}
					String terminalFinishReason = readTerminalFinishReason(json);
					if (terminalFinishReason != null) {
						trace.terminalFinishReason = terminalFinishReason;
						if (!writeSseLine(ctx, line)) {
							trace.endReason = "write_failed_terminal_chunk";
							return trace;
						}
						trace.doneReceivedAt = System.currentTimeMillis();
						trace.endReason = "finish_reason";
						if (!writeSseLine(ctx, "data: [DONE]")) {
							trace.endReason = "write_failed_synthetic_done";
						}
						return trace;
					}
				}

				if (!writeSseLine(ctx, line)) {
					trace.endReason = "write_failed_data";
					return trace;
				}
			}
		}
		trace.eofAt = System.currentTimeMillis();
		trace.endReason = "eof";
		return trace;
	}

	/**
	 * Clean up tracked connection on channel inactive.
	 */
	public void channelInactive(ChannelHandlerContext ctx) {
		cleanupConnection(ctx);
	}
}
