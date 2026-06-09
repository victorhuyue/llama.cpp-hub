package org.mark.llamacpp.server.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.CharactorDataStruct;
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

	private static EasyChatService instance;

	public static EasyChatService getInstance() {
		if (instance == null) {
			instance = new EasyChatService();
		}
		return instance;
	}

	private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
	private final Map<ChannelHandlerContext, HttpURLConnection> channelConnectionMap = new HashMap<>();
	private final Map<String, Object> conversationLocks = new ConcurrentHashMap<>();

	// Index file constants
	private static final int IDX_HEADER = 16;
	private static final int IDX_CONV_NAME_LEN = 4;
	private static final int IDX_CONV_NAME = 4096;
	private static final int IDX_START_TIME = 8;
	private static final int IDX_ASSISTANT_NAME_LEN = 4;
	private static final int IDX_ASSISTANT_NAME = 4096;
	private static final int IDX_SEQ = 8;
	private static final int INDEX_FILE_SIZE = IDX_HEADER + IDX_CONV_NAME_LEN + IDX_CONV_NAME + IDX_START_TIME + IDX_ASSISTANT_NAME_LEN + IDX_ASSISTANT_NAME + IDX_SEQ;

	// Fragment file constants
	private static final int FRAG_HEADER_SIZE = 160;
	private static final int FRAG_PAYLOAD_OFFSET = 160;
	private static final int FRAG_COUNT_OFFSET = 32;
	private static final int FRAG_LENGTHS_OFFSET = 34;
	private static final int FRAG_MAX_VARIANTS = 10;
	private static final int FRAG_LENGTH_ENTRY = 4;
	private static final int FRAG_LENGTHS = FRAG_MAX_VARIANTS * FRAG_LENGTH_ENTRY;

	private static final String FRAG_NAME_FMT = "%016d.bin";
	private static final String INDEX_FILE = "index.bin";
	private static final String TOOLS_FILE = "tools.bin";

	private EasyChatService() {
	}

	/**
	 * Index file data holder.
	 */
	private static final class Index {
		final String convName;
		final long startTime;
		final String assistantName;
		final long seq;

		Index(String convName, long startTime, String assistantName, long seq) {
			this.convName = convName;
			this.startTime = startTime;
			this.assistantName = assistantName;
			this.seq = seq;
       }
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

    /* ---- Public API ---- */

	/**
	 * Handle stream-chat request.
	 * Reads metadata from HTTP headers, body bytes written directly to fragment (no JSON parse),
	 * validates, acquires lock, allocates sequences, writes user fragment,
	 * then dispatches async processing.
	 */
	public void handleStreamChat(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
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

			// Resolve model port
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
			Integer modelPort = manager.getModelPort(modelId);
			if (modelPort == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("模型端口未找到: " + modelId));
				return;
			}

			// Resolve system prompt from assistant name
			String systemPrompt = null;
			if (!assistantName.isBlank()) {
				CharactorDataStruct charactor = findCharactorByTitle(assistantName);
				if (charactor != null && charactor.getSystemPrompt() != null && !charactor.getSystemPrompt().isBlank()) {
					systemPrompt = charactor.getSystemPrompt();
				}
			}

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

			Path fragmentsBase = getFragmentsDir();
			Path convDir = convDir(fragmentsBase, conversationId);

			long userSeq;
			long aiSeq;
			byte[] toolsBytes;

			synchronized (convLock) {
				// Initialize index if needed
				Path indexPath = convDir.resolve(INDEX_FILE);
				if (!Files.exists(indexPath)) {
					Files.createDirectories(convDir);
					createIndex(indexPath, "", System.currentTimeMillis(), assistantName);
				} else if (Files.size(indexPath) != INDEX_FILE_SIZE) {
					// Corrupted or old-version index.bin, delete and recreate
					Files.delete(indexPath);
					createIndex(indexPath, "", System.currentTimeMillis(), assistantName);
				}

				Index idx = readIndex(indexPath);

				if (isRegenerate) {
					// Regenerate: reuse existing AI seq, don't write user fragment
					userSeq = -1;
					aiSeq = regenerateSeq;
				} else {
					userSeq = idx.seq;
					aiSeq = idx.seq + 1;
					writeSeq(indexPath, idx.seq + 2);

					// Write user fragment (raw body bytes, no JSON parsing)
					writeFragment(convDir, userSeq, System.currentTimeMillis(), bodyBytes);
					Path fragFile = fragmentFile(convDir, userSeq);
					logger.info("[EasyChat] 写入用户碎片 seq={} path={} exists={} size={}",
						userSeq, fragFile.toAbsolutePath(), Files.exists(fragFile),
						Files.exists(fragFile) ? Files.size(fragFile) : -1);
				}

				// Write tools.bin if tools provided via header
				if (toolsArr != null) {
					JsonObject toolsObj = new JsonObject();
					toolsObj.add("tools", toolsArr);
					toolsObj.addProperty("tool_choice", toolChoice);
					writeTools(convDir, JsonUtil.toJson(toolsObj).getBytes(StandardCharsets.UTF_8));
					toolsBytes = JsonUtil.toJson(toolsObj).getBytes(StandardCharsets.UTF_8);
				} else {
					toolsBytes = readTools(convDir);
				}
			}

			// Create effectively final copies for lambda capture
			final String finalModelId = modelId;
			final int finalModelPort = modelPort;
			final String finalSystemPrompt = systemPrompt;
			final Path finalConvDir = convDir;
			final byte[] finalToolsBytes = toolsBytes;
			final JsonObject finalSamplingParams = samplingParams;
			final boolean finalIsRegenerate = isRegenerate;
			final Map<Long, Integer> finalVariants = variants;
			final Long finalRegenerateSeq = regenerateSeq;

			// Dispatch to worker thread
			worker.execute(() -> {
				HttpURLConnection connection = null;
				StreamAccumulator accumulator = new StreamAccumulator();
				Path indexPath = finalConvDir.resolve(INDEX_FILE);
				try {
					connection = openTrackedConnection(ctx, finalModelId, finalModelPort);

					// Stream request body to llama.cpp
					writeRequestBody(connection, finalModelId, finalSystemPrompt, finalConvDir,
						finalToolsBytes, finalSamplingParams, finalVariants, finalRegenerateSeq);

					int responseCode = connection.getResponseCode();
					logger.info("[EasyChat] llama.cpp响应码: {} conversation={}", responseCode, conversationId);

					if (!(responseCode >= 200 && responseCode < 300)) {
						String errBody = readErrorBody(connection);
						logger.info("[EasyChat] llama.cpp错误响应 code={} body={}", responseCode, errBody);
						sendErrorResponse(ctx, responseCode, errBody);
						return;
					}

					// Set up SSE response to client
					HttpResponse sseResp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
					sseResp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
					sseResp.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
					sseResp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
					sseResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
					sseResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
					ctx.write(sseResp);
					ctx.flush();

					// Read and proxy SSE stream
					boolean finished = proxySseStream(ctx, connection, finalModelId, accumulator);

					// Write AI fragment and update state (always, if buffer has content)
					synchronized (convLock) {
						if (accumulator.hasContent()) {
							JsonObject aiMsg = buildAiMessage(accumulator);
							byte[] aiBytes = JsonUtil.toJson(aiMsg).getBytes(StandardCharsets.UTF_8);
							if (finalIsRegenerate) {
								appendFragment(finalConvDir, aiSeq, aiBytes);
							} else {
								writeFragment(finalConvDir, aiSeq, System.currentTimeMillis(), aiBytes);
								writeSeq(indexPath, aiSeq + 1);
							}
						}
						if (!finalIsRegenerate) {
							updateStateMessageCount(conversationId, 2);
						}
					}

					// Send SSE close
					if (ctx.channel().isActive()) {
						ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
							.addListener(ChannelFutureListener.CLOSE);
					}

				} catch (Exception e) {
					logger.info("[EasyChat] 处理流式聊天失败 conversation={}", conversationId, e);
					// Still write fragment if we have partial content
					try {
						synchronized (convLock) {
							if (accumulator.hasContent()) {
								JsonObject aiMsg = buildAiMessage(accumulator);
								byte[] aiBytes = JsonUtil.toJson(aiMsg).getBytes(StandardCharsets.UTF_8);
								if (finalIsRegenerate) {
									appendFragment(finalConvDir, aiSeq, aiBytes);
								} else {
									writeFragment(finalConvDir, aiSeq, System.currentTimeMillis(), aiBytes);
									writeSeq(indexPath, aiSeq + 1);
								}
							}
						}
					} catch (Exception ex) {
						logger.warn("[EasyChat] 异常状态下写入AI碎片失败", ex);
					}
					sendOpenAIError(ctx, 500, e.getMessage());
				} finally {
					cleanupConnection(ctx, connection);
				}
			});

		} catch (Exception e) {
			logger.info("[EasyChat] 处理stream-chat请求失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("处理失败: " + e.getMessage()));
		}
	}

	/**
	 * Stream conversation history as chunked JSON with zero-copy file regions.
	 * Payload bytes leave disk via sendfile/splice and never enter JVM heap.
	 * Returns all variants for AI messages that have been regenerated.
	 */
	public void handleStreamChatHistory(ChannelHandlerContext ctx, String conversationId) {
		if (conversationId == null || conversationId.isBlank()) {
			sendHistoryError(ctx, "缺少conversationId");
			return;
		}

		Path convDir;
		try {
			Path fragmentsBase = getFragmentsDir();
			convDir = convDir(fragmentsBase, conversationId);
		} catch (IOException e) {
			logger.info("[EasyChat] 获取碎片目录失败 conversation={}", conversationId, e);
			sendHistoryError(ctx, "获取目录失败: " + e.getMessage());
			return;
		}

		// Phase 1: pre-scan metadata (read header for count/lengths)
		long recordCount = 0;
		long totalSize = 0;
		long variantCount = 0;
		try {
			long seq = 0;
			while (true) {
				Path file = fragmentFile(convDir, seq);
				if (!Files.isRegularFile(file)) break;
				int count;
				try {
					count = readFragmentCount(convDir, seq);
				} catch (Exception e) {
					// Old format without count field, assume 1
					count = 1;
				}
				for (int v = 0; v < count; v++) {
					int len;
					try {
						len = readFragmentLength(convDir, seq, v);
					} catch (Exception e) {
						// Old format, compute from file size
						len = (int) (Files.size(file) - FRAG_PAYLOAD_OFFSET);
						if (len < 0) len = 0;
					}
					totalSize += len;
					if (v > 0) variantCount++;
				}
				recordCount++;
				seq++;
			}
		} catch (Exception e) {
			logger.info("[EasyChat] 预扫描碎片失败 conversation={}", conversationId, e);
			sendHistoryError(ctx, "扫描碎片失败: " + e.getMessage());
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
			long seq = 0;
			boolean first = true;
			while (true) {
				Path file = fragmentFile(convDir, seq);
				if (!Files.isRegularFile(file)) break;

				int count;
				try {
					count = readFragmentCount(convDir, seq);
				} catch (Exception e) {
					count = 1;
				}

				// Read first variant to extract role
				byte[] firstPayload = readFragmentPayload(convDir, seq, 0);

				// Skip empty messages (deleted messages: file size 188 = 160 header + 28 payload)
				if (firstPayload != null && firstPayload.length == 28) {
					seq++;
					continue;
				}

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

				// {"seq":N,"role":"R","variants":[
				String msgPrefix = "{\"seq\":" + seq + ",\"role\":\"" + escapeJsonString(role)
					+ "\",\"variants\":[";
				ctx.writeAndFlush(Unpooled.wrappedBuffer(msgPrefix.getBytes(StandardCharsets.UTF_8)));

				// All variants
				long variantOffset = FRAG_PAYLOAD_OFFSET;
				for (int v = 0; v < count; v++) {
					int vLen;
					try {
						vLen = readFragmentLength(convDir, seq, v);
					} catch (Exception e) {
						if (v == 0) {
							vLen = (int) (Files.size(file) - FRAG_PAYLOAD_OFFSET);
							if (vLen < 0) vLen = 0;
						} else {
							vLen = 0;
						}
					}

					if (v > 0) {
						ctx.writeAndFlush(Unpooled.wrappedBuffer(comma));
					}
					ctx.writeAndFlush(Unpooled.wrappedBuffer(variantPrefix));
					if (vLen > 0) {
						ctx.writeAndFlush(new DefaultFileRegion(file.toFile(), variantOffset, vLen));
					} else {
						ctx.writeAndFlush(Unpooled.wrappedBuffer("null".getBytes(StandardCharsets.UTF_8)));
					}
					ctx.writeAndFlush(Unpooled.wrappedBuffer(variantSuffix));
					variantOffset += vLen;
				}

				ctx.writeAndFlush(Unpooled.wrappedBuffer(msgSuffix));
				seq++;
			}
			ctx.writeAndFlush(Unpooled.wrappedBuffer(dataSuffix));
			logger.info("[EasyChat] 流式传输历史完成 conversation={} records={} variants={} bytes={}",
				conversationId, recordCount, variantCount, totalSize);
			ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
		} catch (Exception e) {
			logger.info("[EasyChat] 流式传输历史失败 conversation={}", conversationId, e);
			ctx.close();
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

	/* ---- Connection management ---- */

	private HttpURLConnection openTrackedConnection(ChannelHandlerContext ctx, String modelId, int port) throws IOException {
		String targetUrl = String.format("http://localhost:%d/v1/chat/completions", port);
		HttpURLConnection connection = (HttpURLConnection) URI.create(targetUrl).toURL().openConnection();
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setConnectTimeout(36000000);
		connection.setReadTimeout(36000000);
		connection.setDoOutput(true);
		connection.setChunkedStreamingMode(8192);
		synchronized (channelConnectionMap) {
			channelConnectionMap.put(ctx, connection);
		}
		return connection;
	}

	private void cleanupConnection(ChannelHandlerContext ctx, HttpURLConnection connection) {
		if (connection != null) {
			connection.disconnect();
		}
		synchronized (channelConnectionMap) {
			channelConnectionMap.remove(ctx);
		}
	}

	/* ---- Index file I/O ---- */

	private void createIndex(Path indexPath, String convName, long startTime, String assistantName) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(indexPath.toFile(), "rw")) {
			FileChannel ch = raf.getChannel();
			ByteBuffer buf = ByteBuffer.allocate(INDEX_FILE_SIZE).order(ByteOrder.LITTLE_ENDIAN);

			// Header: 16 bytes of 0xFF
			for (int i = 0; i < IDX_HEADER; i++) {
				buf.put((byte) 0xFF);
			}

			// Conv name length + content
			byte[] cnBytes = convName.getBytes(StandardCharsets.UTF_8);
			buf.putInt(Math.min(cnBytes.length, IDX_CONV_NAME));
			buf.put(cnBytes);
			// Padding already zero from allocate

			// Start time
			buf.putLong(startTime);

			// Assistant name length + content
			byte[] anBytes = assistantName.getBytes(StandardCharsets.UTF_8);
			buf.putInt(Math.min(anBytes.length, IDX_ASSISTANT_NAME));
			buf.put(anBytes);

			// Seq = 0
			buf.putLong(0);
			// Ensure full 8232 bytes written (zero-padded to end)
			buf.position(INDEX_FILE_SIZE);

			buf.flip();
			ch.write(buf);
		}
	}

	private Index readIndex(Path indexPath) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(indexPath.toFile(), "r")) {
			FileChannel ch = raf.getChannel();
			ByteBuffer buf = ByteBuffer.allocate(INDEX_FILE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
			ch.read(buf);
			buf.flip();

			// Skip header
			buf.position(IDX_HEADER);

			// Conv name
			int cnLen = buf.getInt();
			byte[] cnBytes = new byte[cnLen];
			buf.get(cnBytes);
			String convName = new String(cnBytes, StandardCharsets.UTF_8);
			buf.position(buf.position() + (IDX_CONV_NAME - cnLen));

			// Start time
			long startTime = buf.getLong();

			// Assistant name
			int anLen = buf.getInt();
			byte[] anBytes = new byte[anLen];
			buf.get(anBytes);
			String assistantName = new String(anBytes, StandardCharsets.UTF_8);
			buf.position(buf.position() + (IDX_ASSISTANT_NAME - anLen));

			// Seq
			long seq = buf.getLong();

			return new Index(convName, startTime, assistantName, seq);
		}
	}

	private void writeSeq(Path indexPath, long seq) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(indexPath.toFile(), "rw")) {
			raf.seek(INDEX_FILE_SIZE - IDX_SEQ);
			ByteBuffer buf = ByteBuffer.allocate(IDX_SEQ).order(ByteOrder.LITTLE_ENDIAN);
			buf.putLong(seq);
			buf.flip();
			raf.getChannel().write(buf);
		}
	}

	/* ---- Fragment file I/O ---- */

	private static String fragmentFileName(long seq) {
		return String.format(FRAG_NAME_FMT, seq);
	}

	void writeFragment(Path dir, long seq, long timestamp, byte[] payload) throws IOException {
		Path target = dir.resolve(fragmentFileName(seq));
		Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
		try (RandomAccessFile raf = new RandomAccessFile(temp.toFile(), "rw")) {
			FileChannel ch = raf.getChannel();
			ByteBuffer buf = ByteBuffer.allocate(FRAG_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
			// Header: 16 bytes of 0xFF
			for (int i = 0; i < 16; i++) {
				buf.put((byte) 0xFF);
			}
			// Timestamp
			buf.putLong(timestamp);
			// Seq
			buf.putLong(seq);
			// Message count = 1
			buf.putShort((short) 1);
			// Lengths table: first entry = payload length
			buf.putInt(payload.length);
			// Ensure full 160-byte header written (zero-padded to end)
			buf.position(FRAG_HEADER_SIZE);
			buf.flip();
			ch.write(buf);
			// Payload
			ch.write(ByteBuffer.wrap(payload));
		}
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
	}

	Path fragmentFile(Path dir, long seq) {
		return dir.resolve(fragmentFileName(seq));
	}

	/**
	 * Check if a fragment file exists for the given sequence number.
	 */
	boolean fragmentExists(Path dir, long seq) {
		return Files.isRegularFile(fragmentFile(dir, seq));
	}

	/**
	 * Read payload from a fragment file, skipping the 160-byte header.
	 * Equivalent to readFragmentPayload(dir, seq, 0).
	 */
	byte[] readFragmentPayload(Path dir, long seq) throws IOException {
		return readFragmentPayload(dir, seq, 0);
	}

	/**
	 * Read a specific variant's payload from a fragment file.
	 * Uses the length table in the header to locate the variant offset.
	 */
	byte[] readFragmentPayload(Path dir, long seq, int variantIndex) throws IOException {
		Path file = fragmentFile(dir, seq);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		int[] lengths = readFragmentLengths(dir, seq);
		if (lengths == null || variantIndex >= lengths.length || lengths[variantIndex] == 0) {
			return new byte[0];
		}
		long offset = FRAG_PAYLOAD_OFFSET;
		for (int i = 0; i < variantIndex; i++) {
			offset += lengths[i];
		}
		byte[] payload = new byte[lengths[variantIndex]];
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			raf.seek(offset);
			raf.readFully(payload);
		}
		return payload;
	}

	/**
	 * Read fragment header info: returns array of message lengths (size = count).
	 */
	int[] readFragmentInfo(Path dir, long seq) throws IOException {
		Path file = fragmentFile(dir, seq);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		int count = readFragmentCount(dir, seq);
		int[] lengths = new int[count];
		for (int i = 0; i < count; i++) {
			lengths[i] = readFragmentLength(dir, seq, i);
		}
		return lengths;
	}

	/**
	 * Read message count from fragment header (offset 32, 2 bytes).
	 */
	private int readFragmentCount(Path dir, long seq) throws IOException {
		Path file = fragmentFile(dir, seq);
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			raf.seek(FRAG_COUNT_OFFSET);
			ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
			raf.getChannel().read(buf);
			buf.flip();
			return buf.getShort() & 0xFFFF;
		}
	}

	/**
	 * Read all lengths from fragment length table (offset 34, up to 10 × 4 bytes).
	 * Returns array of FRAG_MAX_VARIANTS entries (remaining are 0).
	 */
	private int[] readFragmentLengths(Path dir, long seq) throws IOException {
		Path file = fragmentFile(dir, seq);
		int[] lengths = new int[FRAG_MAX_VARIANTS];
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			raf.seek(FRAG_LENGTHS_OFFSET);
			ByteBuffer buf = ByteBuffer.allocate(FRAG_LENGTHS).order(ByteOrder.LITTLE_ENDIAN);
			raf.getChannel().read(buf);
			buf.flip();
			for (int i = 0; i < FRAG_MAX_VARIANTS; i++) {
				lengths[i] = buf.getInt();
			}
		}
		return lengths;
	}

	/**
	 * Read a single length entry at given variant index.
	 */
	private int readFragmentLength(Path dir, long seq, int variantIndex) throws IOException {
		Path file = fragmentFile(dir, seq);
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			raf.seek(FRAG_LENGTHS_OFFSET + variantIndex * FRAG_LENGTH_ENTRY);
			ByteBuffer buf = ByteBuffer.allocate(FRAG_LENGTH_ENTRY).order(ByteOrder.LITTLE_ENDIAN);
			raf.getChannel().read(buf);
			buf.flip();
			return buf.getInt();
		}
	}

	/**
	 * Append a new variant to an existing fragment file.
	 * Updates header: count++, lengths[newIdx], then appends payload at end of file.
	 */
	void appendFragment(Path dir, long seq, byte[] payload) throws IOException {
		Path file = dir.resolve(fragmentFileName(seq));
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
			// Read current count
			raf.seek(FRAG_COUNT_OFFSET);
			ByteBuffer countBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
			raf.getChannel().read(countBuf);
			countBuf.flip();
			short count = countBuf.getShort();

			if (count >= FRAG_MAX_VARIANTS) {
				throw new IOException("Fragment seq=" + seq + " already has " + count + " variants (max " + FRAG_MAX_VARIANTS + ")");
			}

			// Calculate new variant offset: header + sum of existing lengths
			long payloadOffset = FRAG_PAYLOAD_OFFSET;
			for (int i = 0; i < count; i++) {
				raf.seek(FRAG_LENGTHS_OFFSET + i * FRAG_LENGTH_ENTRY);
				ByteBuffer lenBuf = ByteBuffer.allocate(FRAG_LENGTH_ENTRY).order(ByteOrder.LITTLE_ENDIAN);
				raf.getChannel().read(lenBuf);
				lenBuf.flip();
				payloadOffset += lenBuf.getInt();
			}

			// Update header: increment count, write new length
			raf.seek(FRAG_COUNT_OFFSET);
			ByteBuffer updBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
			updBuf.putShort((short) (count + 1));
			updBuf.flip();
			raf.getChannel().write(updBuf);

			raf.seek(FRAG_LENGTHS_OFFSET + count * FRAG_LENGTH_ENTRY);
			updBuf = ByteBuffer.allocate(FRAG_LENGTH_ENTRY).order(ByteOrder.LITTLE_ENDIAN);
			updBuf.putInt(payload.length);
			updBuf.flip();
			raf.getChannel().write(updBuf);

			// Append payload at end of file
			raf.seek(payloadOffset);
			raf.write(payload);
		}
	}

	/* ---- Tools file I/O ---- */

	private void writeTools(Path dir, byte[] data) throws IOException {
		Path target = dir.resolve(TOOLS_FILE);
		Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
		Files.write(temp, data);
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
	}

	private byte[] readTools(Path dir) throws IOException {
		Path file = dir.resolve(TOOLS_FILE);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		return Files.readAllBytes(file);
	}

	/* ---- Request body streaming ---- */

	private void writeRequestBody(HttpURLConnection conn, String modelId, String systemPrompt,
		Path convDir, byte[] toolsBytes, JsonObject samplingParams,
		Map<Long, Integer> variants, Long regenerateSeq) throws IOException {

		StringBuilder sb = new StringBuilder();
		sb.append("{\"model\":\"").append(escapeJsonString(modelId)).append("\",\"stream\":true,\"timings_per_token\":true,\"return_progress\":true,\"messages\":[\n");

		// System prompt
		if (systemPrompt != null && !systemPrompt.isBlank()) {
			sb.append("{\"role\":\"system\",\"content\":\"").append(escapeJsonString(systemPrompt)).append("\"},\n");
		}

		// Stream fragment payloads
		long seq = 0;
		boolean first = true;
		logger.info("[EasyChat] 构建请求体 convDir={} regenerateSeq={} variants={}",
			convDir.toAbsolutePath(), regenerateSeq, variants);
		while (true) {
			byte[] payload = readFragmentPayload(convDir, seq);
			logger.info("[EasyChat] 读取碎片 seq={} found={}", seq, payload != null);
			if (payload == null) break;

			// For regenerate: stop before the AI message being regenerated
			if (regenerateSeq != null && seq == regenerateSeq) {
				break;
			}

			// For AI messages, use specified variant if available
			if (variants != null && variants.containsKey(seq)) {
				int variantIdx = variants.get(seq);
				payload = readFragmentPayload(convDir, seq, variantIdx);
			}

			if (!first) {
				sb.append(',');
			}
			sb.append(new String(payload, StandardCharsets.UTF_8));
			sb.append('\n');
			first = false;
			seq++;
		}

		// Close messages array
		sb.append(']');

		// Tools — parse and inline fields into the main JSON object
		if (toolsBytes != null && toolsBytes.length > 0) {
			JsonObject toolsObj = JsonUtil.tryParseObject(new String(toolsBytes, StandardCharsets.UTF_8));
			if (toolsObj != null) {
				for (String key : toolsObj.keySet()) {
					sb.append(',').append('"').append(key).append('"').append(':').append(JsonUtil.toJson(toolsObj.get(key)));
				}
			}
		}

		// Sampling params from header (applied by ModelSamplingService)
		if (samplingParams != null) {
			JsonObject tempReq = new JsonObject();
			tempReq.addProperty("model", modelId);
			tempReq.add("samplingParams", samplingParams);
			ModelSamplingService.getInstance().handleOpenAI(tempReq);
			// Copy applied fields (exclude model, stream, messages)
			for (String key : tempReq.keySet()) {
				if ("model".equals(key) || "stream".equals(key) || "messages".equals(key) || "samplingParams".equals(key)) {
					continue;
				}
				sb.append(',').append('"').append(key).append('"').append(':').append(JsonUtil.toJson(tempReq.get(key)));
			}
		}

		// Close object
		sb.append('}');

		String requestBody = sb.toString();
		logger.info("[EasyChat] llama.cpp请求体 ({} chars):\n{}", requestBody.length(), requestBody);

		try (OutputStream os = conn.getOutputStream()) {
			os.write(requestBody.getBytes(StandardCharsets.UTF_8));
		}
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
		String finishReason = null;
		JsonObject timings = null;

		boolean hasContent() {
			return content.length() > 0 || reasoningContent.length() > 0 || !toolCalls.isEmpty();
		}
	}

	private boolean proxySseStream(ChannelHandlerContext ctx, HttpURLConnection connection,
		String modelId, StreamAccumulator accumulator) throws IOException {

		Map<Integer, String> toolCallIds = new HashMap<>();
		try (BufferedReader br = new BufferedReader(
			new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

			String line;
			while ((line = br.readLine()) != null) {
				if (!ctx.channel().isActive() || !ctx.channel().isWritable()) {
					logger.info("[EasyChat] 客户端断开，中止流式代理");
					return false;
				}

				if (!line.startsWith("data: ")) {
					// Pass through non-data lines
					writeSseLine(ctx, line);
					continue;
				}

				String data = line.substring(6);
				if ("[DONE]".equals(data)) {
					logger.info("[EasyChat] 流式响应结束");
					writeSseLine(ctx, line);
					return true;
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
				}

				writeSseLine(ctx, line);
			}
		}
		return true;
	}

	private void accumulateDelta(JsonObject json, StreamAccumulator acc, Map<Integer, String> toolCallIds) {
		if (!json.has("choices") || !json.get("choices").isJsonArray()) return;
		JsonArray choices = json.getAsJsonArray("choices");
		if (choices.size() == 0) return;
		JsonObject choice = choices.get(0).getAsJsonObject();

		// Finish reason
		if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
			acc.finishReason = JsonUtil.getJsonString(choice, "finish_reason", null);
		}

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

	private void writeSseLine(ChannelHandlerContext ctx, String line) {
		ByteBuf content = ctx.alloc().buffer();
		content.writeBytes(line.getBytes(StandardCharsets.UTF_8));
		content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
		ctx.writeAndFlush(new DefaultHttpContent(content));
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

	/* ---- Charactor lookup ---- */

	private CharactorDataStruct findCharactorByTitle(String title) {
		if (title == null || title.isBlank()) return null;
		try {
			List<CharactorDataStruct> chars = new CompletionService().listCharactor();
			for (CharactorDataStruct c : chars) {
				if (title.equals(c.getTitle())) {
					return c;
				}
			}
		} catch (Exception e) {
			logger.warn("[EasyChat] 查找角色失败 title={}", title, e);
		}
		return null;
	}

	/* ---- Helpers ---- */

	private Path getFragmentsDir() throws IOException {
		Path dir = LlamaServer.getCachePath().resolve("easy-chat").resolve("fragments");
		if (!Files.exists(dir)) {
			Files.createDirectories(dir);
		}
		return dir;
	}

	private Path convDir(Path fragmentsBase, String conversationId) throws IOException {
		Path dir = fragmentsBase.resolve(conversationId);
		if (!Files.exists(dir)) {
			Files.createDirectories(dir);
		}
		return dir;
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
	 * Delete a message: clear fragment payload.
	 * If variantIndex is not null, only clear that variant (AI message).
	 * If variantIndex is null, clear the entire payload (user message).
	 */
	public void deleteMessage(String conversationId, long seq, Integer variantIndex) throws Exception {
		Path fragmentsBase = getFragmentsDir();
		Path dir = fragmentsBase.resolve(conversationId);
		if (!Files.exists(dir)) {
			throw new IOException("Conversation directory not found: " + conversationId);
		}
		if (variantIndex != null) {
			clearFragmentVariant(dir, seq, variantIndex);
		} else {
			clearFragmentPayload(dir, seq);
		}
	}

	/**
	 * Clear the entire payload of a fragment file.
	 * Replaces the payload with empty JSON content.
	 */
	private void clearFragmentPayload(Path dir, long seq) throws IOException {
		Path file = fragmentFile(dir, seq);
		if (!Files.isRegularFile(file)) {
			logger.warn("[EasyChat] 清空碎片: 文件不存在 seq={}", seq);
			return;
		}
		// Read header to preserve metadata
		byte[] header = new byte[FRAG_HEADER_SIZE];
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			raf.readFully(header);
		}
		// Build empty payload
		byte[] emptyPayload = "{\"role\":\"user\",\"content\":\"\"}".getBytes(StandardCharsets.UTF_8);
		// Rewrite file: header + empty payload
		Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
		try (RandomAccessFile raf = new RandomAccessFile(temp.toFile(), "rw")) {
			FileChannel ch = raf.getChannel();
			// Write header with updated length
			ByteBuffer hdrBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
			// Reset count to 1
			hdrBuf.position(FRAG_COUNT_OFFSET);
			hdrBuf.putShort((short) 1);
			// Reset lengths table
			hdrBuf.position(FRAG_LENGTHS_OFFSET);
			hdrBuf.putInt(emptyPayload.length);
			// Zero out remaining length entries
			for (int i = 1; i < FRAG_MAX_VARIANTS; i++) {
				hdrBuf.putInt(0);
			}
			hdrBuf.position(FRAG_HEADER_SIZE);
			hdrBuf.flip();
			ch.write(hdrBuf);
			ch.write(ByteBuffer.wrap(emptyPayload));
		}
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		logger.info("[EasyChat] 清空碎片 payload 成功 seq={}", seq);
	}

	/**
	 * Clear a specific variant in a fragment file.
	 * Replaces the variant's payload with empty JSON, updates lengths table.
	 */
	private void clearFragmentVariant(Path dir, long seq, int variantIndex) throws IOException {
		Path file = fragmentFile(dir, seq);
		if (!Files.isRegularFile(file)) {
			logger.warn("[EasyChat] 清空变体: 文件不存在 seq={}", seq);
			return;
		}
		// Read header
		byte[] header = new byte[FRAG_HEADER_SIZE];
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			raf.readFully(header);
		}
		ByteBuffer hdrBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
		hdrBuf.position(FRAG_COUNT_OFFSET);
		short count = hdrBuf.getShort();
		if (variantIndex < 0 || variantIndex >= count) {
			throw new IOException("Variant index " + variantIndex + " out of range for seq=" + seq + " (count=" + count + ")");
		}
		// Read all lengths
		int[] lengths = new int[FRAG_MAX_VARIANTS];
		hdrBuf.position(FRAG_LENGTHS_OFFSET);
		for (int i = 0; i < FRAG_MAX_VARIANTS; i++) {
			lengths[i] = hdrBuf.getInt();
		}
		// Read all variant payloads
		byte[][] payloads = new byte[count][];
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			for (int i = 0; i < count; i++) {
				if (lengths[i] == 0) {
					payloads[i] = new byte[0];
					continue;
				}
				long offset = FRAG_PAYLOAD_OFFSET;
				for (int j = 0; j < i; j++) {
					offset += lengths[j];
				}
				payloads[i] = new byte[lengths[i]];
				raf.seek(offset);
				raf.readFully(payloads[i]);
			}
		}
		// Remove the target variant: shift remaining variants down
		int newCount = count - 1;
		for (int i = variantIndex; i < newCount; i++) {
			payloads[i] = payloads[i + 1];
			lengths[i] = lengths[i + 1];
		}
		lengths[newCount] = 0;
		// Update count in header
		hdrBuf.position(FRAG_COUNT_OFFSET);
		hdrBuf.putShort((short) newCount);
		// Rewrite file
		Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
		try (RandomAccessFile raf = new RandomAccessFile(temp.toFile(), "rw")) {
			FileChannel ch = raf.getChannel();
			// Write header
			ByteBuffer outBuf = ByteBuffer.allocate(FRAG_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
			outBuf.put(header);
			outBuf.position(FRAG_LENGTHS_OFFSET);
			for (int i = 0; i < FRAG_MAX_VARIANTS; i++) {
				outBuf.putInt(lengths[i]);
			}
			outBuf.position(FRAG_HEADER_SIZE);
			outBuf.flip();
			ch.write(outBuf);
			// Write compacted payloads
			for (int i = 0; i < newCount; i++) {
				if (payloads[i].length > 0) {
					ch.write(ByteBuffer.wrap(payloads[i]));
				}
			}
		}
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		logger.info("[EasyChat] 删除变体成功 seq={} variantIndex={} count={} -> {}", seq, variantIndex, count, newCount);
	}

	private Path indexPath(Path convDir) {
		return convDir.resolve(INDEX_FILE);
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
	 * Clean up tracked connection on channel inactive.
	 */
	public void channelInactive(ChannelHandlerContext ctx) {
		synchronized (channelConnectionMap) {
			HttpURLConnection conn = channelConnectionMap.remove(ctx);
			if (conn != null) {
				try {
					conn.disconnect();
				} catch (Exception e) {
					// ignore
				}
			}
		}
	}
}
