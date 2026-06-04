package org.mark.llamacpp.server.channel;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.ReferenceCountUtil;

/**
 * 拦截 EasyChat conversation/save 端点，流式处理超大请求体。
 * 请求体先写入磁盘临时文件，再通过字节索引提取小字段、流式复制大字段，避免全量加载到内存。
 */
public class EasyChatHandler extends ChannelInboundHandlerAdapter {

	private static final Logger logger = LoggerFactory.getLogger(EasyChatHandler.class);
	private static final String PATH_CONVERSATION_SAVE = "/api/easy-chat/conversation/save";
	private static final String STATE_REVISION_KEY = "_revision";
	private static final String BASE_REVISION_FIELD = "baseRevision";
	private static final String REVISION_CONFLICT_CODE = "STATE_REVISION_CONFLICT";
	private static final int COPY_BUFFER_SIZE = 8192;

	private static final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
	private static final Object SAVE_LOCK = new Object();

	/**
	 * Per-channel request state to avoid race conditions on concurrent requests.
	 */
	private static final class RequestState {
		final Path tempFile;
		final ChannelHandlerContext ctx;

		RequestState(Path tempFile, ChannelHandlerContext ctx) {
			this.tempFile = tempFile;
			this.ctx = ctx;
		}
	}

	private final Map<Channel, RequestState> channelStates = new ConcurrentHashMap<>();

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (!(msg instanceof HttpObject)) {
			ctx.fireChannelRead(msg);
			return;
		}

		HttpObject httpObject = (HttpObject) msg;
		Channel channel = ctx.channel();

		if (httpObject instanceof HttpRequest request) {
			String uri = request.uri();
			int qidx = uri.indexOf('?');
			String path = qidx >= 0 ? uri.substring(0, qidx) : uri;
			if (!PATH_CONVERSATION_SAVE.equals(path) || request.method() != HttpMethod.POST) {
				ctx.fireChannelRead(msg);
				return;
			}
			Path tf = Files.createTempFile(LlamaServer.getCachePath().resolve("easy-chat"), "conv-save-", ".tmp");
			channelStates.put(channel, new RequestState(tf, ctx));
			ReferenceCountUtil.release(msg);
			return;
		}

		RequestState state = channelStates.get(channel);
		if (state == null) {
			ctx.fireChannelRead(msg);
			return;
		}

		try {
			if (httpObject instanceof HttpContent content) {
				ByteBuf data = content.content();
				if (data.isReadable()) {
					try (OutputStream os = Files.newOutputStream(state.tempFile,
							java.nio.file.StandardOpenOption.APPEND)) {
						data.readBytes(os, data.readableBytes());
					}
				}
				if (httpObject instanceof LastHttpContent) {
					RequestState removed = channelStates.remove(channel);
					Path tf = removed != null ? removed.tempFile : state.tempFile;
					ChannelHandlerContext pctx = (removed != null ? removed : state).ctx;
					this.processSaveRequest(pctx, tf);
				}
			}
		} catch (IOException e) {
			logger.info("接收 EasyChat conversation save 请求体失败", e);
			this.cleanupState(channel);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("接收请求体失败: " + e.getMessage()));
		} finally {
			ReferenceCountUtil.release(msg);
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		this.cleanupState(ctx.channel());
		if (cause instanceof TooLongFrameException) {
			String body = "{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"请求体过大，最大允许 16 MB\"}";
			DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
					HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
			resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
			resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, resp.content().readableBytes());
			ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
			return;
		}
		ctx.fireExceptionCaught(cause);
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		this.cleanupState(ctx.channel());
		super.channelInactive(ctx);
	}

	private void cleanupState(Channel channel) {
		RequestState state = channelStates.remove(channel);
		if (state != null && state.tempFile != null) {
			try {
				Files.deleteIfExists(state.tempFile);
			} catch (IOException ignored) {
			}
		}
	}

	private void processSaveRequest(ChannelHandlerContext ctx, Path tempFile) {
		worker.execute(() -> {
			try {
				synchronized (SAVE_LOCK) {
					this.executeSave(ctx, tempFile);
				}
			} catch (Exception e) {
				logger.info("EasyChat conversation save 处理失败", e);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存会话失败: " + e.getMessage()));
			} finally {
				try {
					Files.deleteIfExists(tempFile);
				} catch (IOException ignored) {
				}
			}
		});
	}

	private void executeSave(ChannelHandlerContext ctx, Path tempFile) throws Exception {
		// Index the request body JSON
		JsonIndex bodyIndex = JsonIndex.index(tempFile);

		// Read baseRevision
		String baseRevision = this.readStringField(tempFile, bodyIndex, BASE_REVISION_FIELD, "");
		baseRevision = baseRevision == null ? "" : baseRevision.trim();

		// Get conversation field range
		JsonIndex.MemberRange convRange = bodyIndex.getMember("conversation");
		if (convRange == null) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少 conversation 字段"));
			return;
		}

		// Index the conversation object
		try (InputStream convStream = new BufferedInputStream(
				this.rangeStream(tempFile, convRange.valueStart, convRange.valueEnd), 65536)) {
			JsonIndex convIndex = JsonIndex.index(convStream);

			// Read conversation.id
			String convId = this.readStringIndexedField(tempFile, convRange, convIndex, "id", "");
			convId = convId == null ? "" : convId.trim();

			// Determine target file path
			Path stateDir = this.getStateDirPath();
			Path stateFile = this.getStateFilePath(stateDir);
			Path conversationDir = this.getConversationDirPath(stateDir);

			// Check revision
			JsonObject currentState = this.readJsonObjectIfExists(stateFile);
			this.assertRevisionMatches(stateFile, currentState, baseRevision);

			// Copy conversation JSON to target file (streaming)
			String storageKey = this.normalizeStorageKey(convId);
			Path conversationFile = this.getConversationFilePath(conversationDir, storageKey);
			this.copyRangeToFile(tempFile, convRange.valueStart, convRange.valueEnd, conversationFile);

			// Count messages
			int messageCount = this.countMessages(tempFile, convRange, convIndex);

			// Build summary from raw JSON fields
			JsonObject summary = this.buildSummaryFromIndex(tempFile, convRange, convIndex, convId, storageKey,
					messageCount);

			// Update state file
			JsonObject storedState = this.readJsonObjectIfExists(stateFile);
			JsonArray summaries = this.getConversationArray(storedState);
			summaries = this.normalizeConversationSummaries(summaries);
			this.upsertConversationSummary(summaries, summary);
			storedState.add("conversations", summaries);
			String revision = UUID.randomUUID().toString();
			storedState.addProperty(STATE_REVISION_KEY, revision);
			this.writeJsonFile(stateFile, storedState);

			// Delete stale conversation files
			this.deleteStaleConversationFiles(conversationDir, summaries);

			// Send response
			Map<String, Object> data = new HashMap<>();
			data.put("saved", true);
			data.put("revision", revision);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		}
	}

	/* ---- JSON indexing helpers ---- */

	private String readStringField(Path sourceFile, JsonIndex bodyIndex, String fieldName, String fallback)
			throws IOException {
		JsonIndex.MemberRange range = bodyIndex.getMember(fieldName);
		if (range == null)
			return fallback;
		if (range.valueStart >= range.valueEnd)
			return fallback;
		// Skip surrounding quotes
		long start = range.valueStart + 1;
		long end = range.valueEnd - 1;
		if (start >= end)
			return fallback;
		return this.readStringRange(sourceFile, start, end);
	}

	private String readStringIndexedField(Path sourceFile, JsonIndex.MemberRange parentRange, JsonIndex parentIndex,
			String fieldName, String fallback) throws IOException {
		JsonIndex.MemberRange range = parentIndex.getMember(fieldName);
		if (range == null)
			return fallback;
		// range positions are relative to the parent object start
		long absStart = parentRange.valueStart + range.valueStart;
		long absEnd = parentRange.valueStart + range.valueEnd;
		if (absStart >= absEnd)
			return fallback;
		// Skip surrounding quotes
		long contentStart = absStart + 1;
		long contentEnd = absEnd - 1;
		if (contentStart >= contentEnd)
			return fallback;
		return this.readStringRange(sourceFile, contentStart, contentEnd);
	}

	private String readStringRange(Path sourceFile, long start, long end) throws IOException {
		int len = (int) (end - start);
		if (len <= 0)
			return "";
		byte[] bytes = new byte[len];
		try (RandomAccessFile raf = new RandomAccessFile(sourceFile.toFile(), "r")) {
			raf.seek(start);
			raf.readFully(bytes);
		}
		// Unescape JSON string
		return unescapeJsonString(bytes);
	}

	private static String unescapeJsonString(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length);
		int i = 0;
		while (i < bytes.length) {
			char c = (char) bytes[i];
			if (c == '\\') {
				if (i + 1 < bytes.length) {
					i++;
					char esc = (char) bytes[i];
					switch (esc) {
					case '"':
						sb.append('"');
						break;
					case '\\':
						sb.append('\\');
						break;
					case '/':
						sb.append('/');
						break;
					case 'b':
						sb.append('\b');
						break;
					case 'f':
						sb.append('\f');
						break;
					case 'n':
						sb.append('\n');
						break;
					case 'r':
						sb.append('\r');
						break;
					case 't':
						sb.append('\t');
						break;
					case 'u':
						if (i + 4 < bytes.length) {
							String hex = new String(bytes, i + 1, 4);
							sb.append((char) Integer.parseInt(hex, 16));
							i += 4;
						}
						break;
					default:
						sb.append(esc);
						break;
					}
				}
			} else {
				sb.append(c);
			}
			i++;
		}
		return sb.toString();
	}

	private InputStream rangeStream(Path sourceFile, long start, long end) throws IOException {
		final RandomAccessFile raf = new RandomAccessFile(sourceFile.toFile(), "r");
		raf.seek(start);
		final long remaining = end - start;
		return new InputStream() {
			long left = remaining;

			@Override
			public int read() throws IOException {
				if (left <= 0)
					return -1;
				int b = raf.read();
				if (b != -1)
					left--;
				return b;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				if (left <= 0)
					return -1;
				int toRead = (int) Math.min(len, left);
				int n = raf.read(b, off, toRead);
				if (n > 0)
					left -= n;
				return n;
			}

			@Override
			public void close() throws IOException {
				raf.close();
			}
		};
	}

	private int countMessages(Path sourceFile, JsonIndex.MemberRange convRange, JsonIndex convIndex)
			throws IOException {
		JsonIndex.MemberRange msgsRange = convIndex.getMember("messages");
		if (msgsRange == null)
			return 0;
		long absStart = convRange.valueStart + msgsRange.valueStart;
		long absEnd = convRange.valueStart + msgsRange.valueEnd;
		// Count top-level objects in the array using buffered read
		try (RandomAccessFile raf = new RandomAccessFile(sourceFile.toFile(), "r")) {
			raf.seek(absStart);
			byte[] buf = new byte[65536];
			// Skip the opening [
			int ch = raf.read();
			if (ch != '[')
				return 0;
			int count = 0;
			int depth = 0;
			boolean inString = false;
			boolean escaped = false;
			long pos = absStart + 1;
			long limit = absEnd;
			long remaining = limit - pos;
			while (remaining > 0) {
				int toRead = (int) Math.min(buf.length, remaining);
				int n = raf.read(buf, 0, toRead);
				if (n == -1)
					break;
				for (int i = 0; i < n; i++) {
					ch = buf[i] & 0xFF;
					pos++;
					remaining--;
					if (escaped) {
						escaped = false;
						continue;
					}
					if (inString) {
						if (ch == '\\') {
							escaped = true;
						} else if (ch == '"') {
							inString = false;
						}
						continue;
					}
					if (ch == '"') {
						inString = true;
						continue;
					}
					if (ch == '{') {
						depth++;
						if (depth == 1)
							count++;
					} else if (ch == '}') {
						depth--;
					}
				}
			}
			return count;
		}
	}

	private JsonObject buildSummaryFromIndex(Path sourceFile, JsonIndex.MemberRange convRange, JsonIndex convIndex,
			String convId, String storageKey, int messageCount) throws IOException {
		JsonObject summary = new JsonObject();
		summary.addProperty("id", convId);
		summary.addProperty("storageKey", storageKey);
		summary.addProperty("messageCount", messageCount);

		// Copy other fields from conversation (skip messages)
		for (Map.Entry<String, JsonIndex.MemberRange> entry : convIndex.getMembers().entrySet()) {
			String name = entry.getKey();
			if ("messages".equals(name))
				continue;
			JsonIndex.MemberRange range = entry.getValue();
			long absStart = convRange.valueStart + range.valueStart;
			long absEnd = convRange.valueStart + range.valueEnd;
			int len = (int) (absEnd - absStart);
			if (len <= 0)
				continue;
			byte[] bytes = new byte[len];
			try (RandomAccessFile raf = new RandomAccessFile(sourceFile.toFile(), "r")) {
				raf.seek(absStart);
				raf.readFully(bytes);
			}
			try {
				JsonElement el = JsonUtil.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonElement.class);
				if (el != null) {
					summary.add(name, el);
				}
			} catch (Exception ignored) {
			}
		}
		return summary;
	}

	/* ---- File I/O helpers ---- */

	private void copyRangeToFile(Path sourceFile, long start, long end, Path targetFile) throws IOException {
		Path parent = targetFile.getParent();
		if (parent != null && !Files.exists(parent)) {
			Files.createDirectories(parent);
		}
		Path tempTarget = targetFile.resolveSibling(targetFile.getFileName().toString() + ".tmp");
		try (RandomAccessFile src = new RandomAccessFile(sourceFile.toFile(), "r");
				OutputStream dst = Files.newOutputStream(tempTarget)) {
			src.seek(start);
			long remaining = end - start;
			byte[] buffer = new byte[COPY_BUFFER_SIZE];
			while (remaining > 0) {
				int toRead = (int) Math.min(buffer.length, remaining);
				int n = src.read(buffer, 0, toRead);
				if (n == -1)
					break;
				dst.write(buffer, 0, n);
				remaining -= n;
			}
		}
		try {
			Files.move(tempTarget, targetFile, StandardCopyOption.REPLACE_EXISTING,
					java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Files.move(tempTarget, targetFile, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/* ---- State management helpers (mirrored from EasyChatController) ---- */

	private Path getStateDirPath() throws Exception {
		Path dir = LlamaServer.getCachePath().resolve("easy-chat").toAbsolutePath().normalize();
		if (!Files.exists(dir)) {
			Files.createDirectories(dir);
		}
		return dir;
	}

	private Path getStateFilePath(Path stateDir) {
		return stateDir.resolve("state.json").toAbsolutePath().normalize();
	}

	private Path getConversationDirPath(Path stateDir) {
		return stateDir.resolve("conversations").toAbsolutePath().normalize();
	}

	private Path getConversationFilePath(Path conversationDir, String storageKey) {
		return conversationDir.resolve(storageKey + ".json").toAbsolutePath().normalize();
	}

	private String normalizeStorageKey(String conversationId) {
		String source = conversationId == null ? "" : conversationId.trim();
		if (!source.isEmpty() && source.matches("[A-Za-z0-9_-]+")) {
			return source;
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(source.getBytes(StandardCharsets.UTF_8));
	}

	private String resolveStorageKey(String storedKey, String conversationId) {
		String key = storedKey == null ? "" : storedKey.trim();
		if (!key.isEmpty() && key.matches("[A-Za-z0-9_-]+")) {
			return key;
		}
		String source = conversationId == null ? "" : conversationId.trim();
		if (!source.isEmpty() && source.matches("[A-Za-z0-9_-]+")) {
			return source;
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(source.getBytes(StandardCharsets.UTF_8));
	}

	private void assertRevisionMatches(Path stateFile, JsonObject currentState, String baseRevision) throws Exception {
		if (!Files.exists(stateFile))
			return;
		String currentRevision = this.resolveStateRevision(stateFile, currentState);
		if (baseRevision.isEmpty() || !baseRevision.equals(currentRevision)) {
			throw new IllegalStateException(REVISION_CONFLICT_CODE);
		}
	}

	private String resolveStateRevision(Path stateFile, JsonObject state) throws Exception {
		String revision = JsonUtil.getJsonString(state, STATE_REVISION_KEY, "");
		if (revision != null && !revision.isBlank())
			return revision.trim();
		if (!Files.exists(stateFile))
			return "";
		long modifiedAt = Files.getLastModifiedTime(stateFile).toMillis();
		long size = Files.size(stateFile);
		return "legacy-" + Long.toUnsignedString(modifiedAt, 36) + "-" + Long.toUnsignedString(size, 36);
	}

	private JsonObject readJsonObjectIfExists(Path file) throws Exception {
		if (file == null || !Files.exists(file))
			return null;
		String json = Files.readString(file, StandardCharsets.UTF_8);
		if (json == null || json.trim().isEmpty())
			return new JsonObject();
		JsonElement element = JsonUtil.fromJson(json, JsonElement.class);
		if (element == null || !element.isJsonObject())
			return null;
		return element.getAsJsonObject();
	}

	private void writeJsonFile(Path file, JsonElement json) throws Exception {
		Path parent = file.getParent();
		if (parent != null && !Files.exists(parent)) {
			Files.createDirectories(parent);
		}
		Path tempFile = file.resolveSibling(file.getFileName().toString() + ".tmp");
		Files.writeString(tempFile, JsonUtil.toJson(json), StandardCharsets.UTF_8);
		try {
			Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING,
					java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private JsonArray getConversationArray(JsonObject state) {
		if (state == null || !state.has("conversations") || !state.get("conversations").isJsonArray()) {
			return new JsonArray();
		}
		return state.getAsJsonArray("conversations");
	}

	private JsonArray normalizeConversationSummaries(JsonArray conversations) {
		JsonArray normalized = new JsonArray();
		for (int i = 0; i < conversations.size(); i++) {
			JsonElement element = conversations.get(i);
			if (element == null || !element.isJsonObject())
				continue;
			JsonObject summary = element.getAsJsonObject();
			if (summary.has("messages") && summary.get("messages").isJsonArray()) {
				// Already has messages, needs normalization (shouldn't happen in state file)
				JsonObject n = summary.deepCopy();
				String id = JsonUtil.getJsonString(n, "id", "");
				n.remove("messages");
				n.addProperty("storageKey", this.normalizeStorageKey(id));
				n.addProperty("messageCount", n.getAsJsonArray("messages").size());
				normalized.add(n);
			} else {
				normalized.add(summary.deepCopy());
			}
		}
		return normalized;
	}

	private void upsertConversationSummary(JsonArray summaries, JsonObject summary) {
		String id = JsonUtil.getJsonString(summary, "id", "");
		if (id.isBlank())
			return;
		for (int i = 0; i < summaries.size(); i++) {
			JsonElement element = summaries.get(i);
			if (element == null || !element.isJsonObject())
				continue;
			JsonObject current = element.getAsJsonObject();
			if (id.equals(JsonUtil.getJsonString(current, "id", ""))) {
				summaries.set(i, summary);
				return;
			}
		}
		summaries.add(summary);
	}

	private void deleteStaleConversationFiles(Path conversationDir, JsonArray summaries) throws Exception {
		if (!Files.exists(conversationDir))
			return;
		Set<String> expectedFiles = new HashSet<>();
		for (int i = 0; i < summaries.size(); i++) {
			JsonElement element = summaries.get(i);
			if (element == null || !element.isJsonObject())
				continue;
			JsonObject summary = element.getAsJsonObject();
			String id = JsonUtil.getJsonString(summary, "id", "");
			// Use stored storageKey from summary, fallback to id
			String storedKey = JsonUtil.getJsonString(summary, "storageKey", null);
			String storageKey = this.resolveStorageKey(storedKey, id);
			expectedFiles.add(storageKey + ".json");
		}
		try (Stream<Path> stream = Files.list(conversationDir)) {
			stream.filter(Files::isRegularFile).forEach(path -> {
				String fileName = path.getFileName().toString();
				if (!fileName.endsWith(".json"))
					return;
				if (!expectedFiles.contains(fileName)) {
					try {
						Files.deleteIfExists(path);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			});
		}
	}

	/**
	 * Minimal JSON object indexer - scans top-level members and records byte
	 * offsets.
	 */
	static final class JsonIndex {
		private final Map<String, MemberRange> members = new LinkedHashMap<>();

		public static JsonIndex index(Path file) throws IOException {
			try (InputStream is = new BufferedInputStream(Files.newInputStream(file), 65536)) {
				return index(is);
			}
		}

		public static JsonIndex index(InputStream is) throws IOException {
			TrackingInput in = new TrackingInput(is);
			int first = in.readNonWhitespace();
			if (first != '{') {
				throw new IOException("Expected JSON object");
			}
			JsonIndex result = new JsonIndex();
			for (;;) {
				int next = in.readNonWhitespace();
				if (next == '}')
					break;
				if (next != '"')
					throw new IOException("Invalid JSON field");
				long memberStart = in.position() - 1;
				String fieldName = readJsonString(in);
				int colon = in.readNonWhitespace();
				if (colon != ':')
					throw new IOException("Invalid JSON separator");
				int valueFirst = in.readNonWhitespace();
				if (valueFirst == -1)
					throw new IOException("Unexpected EOF");
				long valueStart = in.position() - 1;
				skipValue(in, valueFirst);
				long valueEnd = in.position();
				result.members.put(fieldName, new MemberRange(memberStart, valueStart, valueEnd));
				int sep = in.readNonWhitespace();
				if (sep == ',')
					continue;
				if (sep == '}')
					break;
				throw new IOException("Invalid JSON terminator");
			}
			return result;
		}

		private static String readJsonString(TrackingInput in) throws IOException {
			StringBuilder sb = new StringBuilder();
			for (;;) {
				int ch = in.read();
				if (ch == -1)
					throw new IOException("Unexpected EOF in string");
				if (ch == '"')
					return sb.toString();
				if (ch == '\\') {
					int esc = in.read();
					if (esc == -1)
						throw new IOException("Unexpected EOF in escape");
					if (esc == 'u') {
						String hex = "";
						for (int i = 0; i < 4; i++) {
							int h = in.read();
							if (h == -1)
								throw new IOException("Unexpected EOF in unicode");
							hex += (char) h;
						}
						sb.append((char) Integer.parseInt(hex, 16));
					} else {
						switch (esc) {
						case '"':
							sb.append('"');
							break;
						case '\\':
							sb.append('\\');
							break;
						case '/':
							sb.append('/');
							break;
						case 'b':
							sb.append('\b');
							break;
						case 'f':
							sb.append('\f');
							break;
						case 'n':
							sb.append('\n');
							break;
						case 'r':
							sb.append('\r');
							break;
						case 't':
							sb.append('\t');
							break;
						default:
							sb.append((char) esc);
							break;
						}
					}
				} else {
					sb.append((char) ch);
				}
			}
		}

		private static void skipValue(TrackingInput in, int first) throws IOException {
			if (first == '"') {
				readJsonString(in);
			} else if (first == '{' || first == '[') {
				int depth = 1;
				boolean inStr = false;
				boolean escaped = false;
				for (;;) {
					int ch = in.read();
					if (ch == -1)
						throw new IOException("Unexpected EOF");
					if (escaped) {
						escaped = false;
						continue;
					}
					if (inStr) {
						if (ch == '\\') {
							escaped = true;
						} else if (ch == '"') {
							inStr = false;
						}
						continue;
					}
					if (ch == '"') {
						inStr = true;
						continue;
					}
					if (ch == '{' || ch == '[')
						depth++;
					else if (ch == '}' || ch == ']') {
						depth--;
						if (depth == 0)
							return;
					}
				}
			} else {
				// primitive: number, bool, null
				StringBuilder sb = new StringBuilder();
				sb.append((char) first);
				for (;;) {
					int ch = in.read();
					if (ch == -1 || Character.isWhitespace(ch) || ch == ',' || ch == '}' || ch == ']') {
						if (ch != -1)
							in.unread(ch);
						return;
					}
					sb.append((char) ch);
				}
			}
		}

		public MemberRange getMember(String name) {
			return members.get(name);
		}

		public Map<String, MemberRange> getMembers() {
			return members;
		}

		static final class MemberRange {
			final long memberStart;
			final long valueStart;
			final long valueEnd;

			MemberRange(long memberStart, long valueStart, long valueEnd) {
				this.memberStart = memberStart;
				this.valueStart = valueStart;
				this.valueEnd = valueEnd;
			}
		}

		private static final class TrackingInput {
			private final PushbackInputStream input;
			private long position;

			TrackingInput(InputStream input) {
				this.input = new PushbackInputStream(input, 8);
				this.position = 0;
			}

			int read() throws IOException {
				int ch = input.read();
				if (ch != -1)
					position++;
				return ch;
			}

			void unread(int ch) throws IOException {
				if (ch == -1)
					return;
				input.unread(ch);
				position--;
			}

			int readNonWhitespace() throws IOException {
				for (;;) {
					int ch = read();
					if (ch == -1)
						return -1;
					if (!Character.isWhitespace(ch))
						return ch;
				}
			}

			long position() {
				return position;
			}
		}
	}
}
