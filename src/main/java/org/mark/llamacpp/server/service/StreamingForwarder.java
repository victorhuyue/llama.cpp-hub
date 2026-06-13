package org.mark.llamacpp.server.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StreamingForwarder {

    private static final Logger logger = LoggerFactory.getLogger(StreamingForwarder.class);

    private static final int QUEUE_CAPACITY = 16;
    private static final int CHUNK_PREVIEW_MAX = 120;

    private static final byte[] EOF_MARKER = new byte[0];

    /* ---------- 目标字段常量 ---------- */
    private static final TargetField TARGET_MODEL = new TargetField("model", FieldType.STRING);
    private static final TargetField TARGET_ENABLE_THINKING = new TargetField("enable_thinking", FieldType.BOOLEAN);
    private static final TargetField[] ALL_TARGETS = { TARGET_MODEL, TARGET_ENABLE_THINKING };

    /* ---------- 状态机常量 ---------- */
    private static final int STATE_NORMAL         = 0;
    private static final int STATE_KEY_MATCH      = 1;
    private static final int STATE_VALUE_PARSE    = 2;
    private static final int STATE_DONE           = 3;

    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private volatile IOException failure;

    private final UnifiedBodyBuffer bodyBuffer = new UnifiedBodyBuffer();

    /* 提取结果 */
    private String modelName;
    private Boolean enableThinking;

    /* 状态机字段（跨 chunk 持久化） */
    private int state = STATE_NORMAL;
    private int depth;
    private boolean inString;
    private TargetField currentTarget;
    private int keyMatchLen;
    private StringBuilder valueBuf;
    private boolean escapePending;
    private boolean afterColon;
    private boolean inValueString;
    private int boolMatchLen;

    /* nodeId 由外部从请求头设置，不从 body 提取 */
    private volatile String nodeId;

    private volatile byte[] lastChunk;
    private final AtomicLong chunkSeq = new AtomicLong(0);

    public StreamingForwarder() {
    }

    /**
     * 从请求头设置 nodeId。
     */
    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public void offer(byte[] chunk) throws IOException {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        if (closed.get()) {
            throw new IOException("stream closed");
        }
        long seq = chunkSeq.incrementAndGet();
        try {
            bodyBuffer.write(chunk);
            extractFields(chunk);
        } catch (IOException e) {
            fail(e);
            throw e;
        }
        Object marker = seq;
        try {
            while (!closed.get() && !failed.get()) {
                if (queue.offer(marker, 100, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while enqueuing", e);
        }
        if (failed.get() && failure != null) {
            throw failure;
        }
        throw new IOException("stream closed");
    }

    public void offerLast(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        this.lastChunk = chunk;
    }

    public void complete() {
        closed.compareAndSet(false, true);
        try {
            queue.put(EOF_MARKER);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void fail(IOException e) {
        failed.compareAndSet(false, true);
        this.failure = e;
        closed.set(true);
        try {
            queue.put(EOF_MARKER);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 等待所有 chunk 到达（已在 offer() 中写入 bodyBuffer），返回路由信息。
     */
    public TransformResult extract() throws IOException {
        while (true) {
            try {
                Object marker = queue.poll(1, TimeUnit.SECONDS);
                if (marker == EOF_MARKER) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for chunk", e);
            }
        }
        if (failed.get() && failure != null) {
            throw failure;
        }

        /* 处理最后一个 chunk（必须在检查 modelName 之前） */
        byte[] storedLast = this.lastChunk;
        if (storedLast != null && storedLast.length > 0) {
            try {
                bodyBuffer.write(storedLast);
            } catch (IOException e) {
                fail(e);
                throw e;
            }
            extractFields(storedLast);
        }

        if (modelName == null || modelName.isBlank()) {
            throw new ForwarderException(400, "Missing required parameter: model", "model");
        }

        return new TransformResult(modelName, nodeId, enableThinking != null ? enableThinking : false);
    }

    /**
     * 将 bodyBuffer 中的数据流式转发到目标输出流。
     * 有 nodeId → 纯转发；无 nodeId → 注入采样参数后转发。
     */
    public void streamBody(OutputStream output, Boolean clientEnableThinking) throws IOException {
        if (nodeId != null && !nodeId.isBlank()) {
            long written = bodyBuffer.streamTo(output);
            logger.info("[远程代理] nodeId={}, streamed={} bytes", nodeId, written);
        } else {
            String injection = SamplingInjectionBuilder.buildInjectionString(modelName, clientEnableThinking);
            if (!injection.isEmpty()) {
                long injected = bodyBuffer.streamInjected(output, injection);
                logger.info("[注入] model={}, injected={} bytes: {}", modelName, injected, injection);
            } else {
                bodyBuffer.streamTo(output);
            }
        }
    }

    /**
     * 基于状态机的 JSON 字段提取。
     * 逐字节扫描，追踪嵌套深度和字符串状态，提取顶层 "model"（string）和 "enable_thinking"（boolean）字段。
     * 内存 O(1)，不依赖 JSON 总大小。
     */
    void extractFields(byte[] chunk) {
        if (state == STATE_DONE) {
            return;
        }

        //logger.debug("[状态机] === chunk: {} 字节, preview={}", chunk.length, previewChunk(chunk));

        for (int i = 0; i < chunk.length; i++) {
            byte b = chunk[i];
            int prevState = state;

            switch (state) {

                case STATE_DONE:
                    return;

                /* ===== 主状态：逐字节扫描 JSON 结构 ===== */
                default:
                case STATE_NORMAL: {
                    if (escapePending) {
                        escapePending = false;
                        break;
                    }
                    if (b == '\\') {
                        escapePending = true;
                        break;
                    }
                    if (b == '"') {
                        if (!inString) {
                            inString = true;
                            /* 前瞻检查：是否为顶层目标字段 key */
                            if (depth == 1) {
                                TargetField matched = findMatchingTarget(chunk, i + 1);
                                if (matched != null) {
                                    currentTarget = matched;
                                    keyMatchLen = 0;
                                    state = STATE_KEY_MATCH;
                                    //logger.debug("[状态机] pos={} 匹配到 {} key 开头", i, matched.name());
                                    break;
                                }
                            }
                        } else {
                            inString = false;
                        }
                        break;
                    }
                    if (inString) {
                        break;
                    }
                    if (b == '{') {
                        depth++;
                        break;
                    }
                    if (b == '}') {
                        depth--;
                        if (depth < 0) depth = 0;
                        if (depth == 0) {
                            state = STATE_DONE;
                            //logger.debug("[状态机] pos={} 顶层 JSON 结束", i);
                            return;
                        }
                        break;
                    }
                    break;
                }

                /* ===== 消耗目标 key 剩余字符（前瞻已确认匹配） ===== */
                case STATE_KEY_MATCH: {
                    keyMatchLen++;
                    if (keyMatchLen == currentTarget.keyBytes().length) {
                        /* key 匹配完成，准备解析 value */
                        afterColon = false;
                        inValueString = false;
                        valueBuf = null;
                        boolMatchLen = 0;
                        state = STATE_VALUE_PARSE;
                        //logger.debug("[状态机] pos={} {} key 匹配完成，解析 value", i, currentTarget.name());
                    }
                    break;
                }

                /* ===== 解析字段的 value ===== */
                case STATE_VALUE_PARSE: {
                    if (currentTarget.type() == FieldType.STRING) {
                        handleStringValue(b, prevState);
                    } else {
                        handleBooleanValue(b, prevState, chunk, i);
                    }
                    break;
                }
            }

            //if (prevState != state) {
                //logger.debug("[状态机] {} -> {}", stateName(prevState), stateName(state));
            //}
        }
    }

    /**
     * 解析字符串类型的 value（用于 model 字段）。
     */
    void handleStringValue(byte b, int prevState) {
        if (escapePending) {
            escapePending = false;
            if (valueBuf != null) {
                valueBuf.append((char) b);
            }
            return;
        }
        if (b == '\\') {
            escapePending = true;
            return;
        }
        if (b == '"') {
            if (!afterColon) {
                /* 不应到达：key 已在 KEY_MATCH 中消耗完 */
                return;
            }
            if (!inValueString) {
                /* value 的打开引号 */
                inValueString = true;
                return;
            }
            /* value 的关闭引号 —— 提取完成 */
            String val = (valueBuf == null) ? "" : valueBuf.toString();
            if (currentTarget == TARGET_MODEL) {
                modelName = val;
            }
            bodyBuffer.setModelFound();
            //logger.info("[状态机] *** 提取到 {}={}", currentTarget.name(), val);
            resetToNormal();
            return;
        }
        if (b == ':') {
            afterColon = true;
            return;
        }
        if (isWhitespace(b)) {
            return;
        }
        /* value 字符 */
        if (valueBuf == null) {
            valueBuf = new StringBuilder(32);
        }
        valueBuf.append((char) b);
    }

    /**
     * 解析布尔类型的 value（用于 enable_thinking 字段）。
     */
    void handleBooleanValue(byte b, int prevState, byte[] chunk, int pos) {
        if (escapePending) {
            escapePending = false;
            return;
        }
        if (b == '\\') {
            escapePending = true;
            return;
        }
        if (b == ':') {
            afterColon = true;
            return;
        }
        if (isWhitespace(b)) {
            return;
        }
        if (b == '"') {
            if (!afterColon) {
                return;
            }
            /* 布尔值不应出现在引号中，重置 */
            resetToNormal();
            return;
        }

        /* 累积布尔值字符 */
        if (!afterColon) {
            return;
        }

        if (b == 't' && boolMatchLen == 0) {
            boolMatchLen++;
            return;
        }
        if (b == 'r' && boolMatchLen == 1) {
            boolMatchLen++;
            return;
        }
        if (b == 'u' && boolMatchLen == 2) {
            boolMatchLen++;
            return;
        }
        if (b == 'e' && boolMatchLen == 3) {
            boolMatchLen++;
            enableThinking = true;
            //logger.info("[状态机] *** 提取到 enable_thinking=true");
            resetToNormal();
            return;
        }
        if (b == 'f' && boolMatchLen == 0) {
            boolMatchLen++;
            return;
        }
        if (b == 'a' && boolMatchLen == 1) {
            boolMatchLen++;
            return;
        }
        if (b == 'l' && boolMatchLen == 2) {
            boolMatchLen++;
            return;
        }
        if (b == 's' && boolMatchLen == 3) {
            boolMatchLen++;
            return;
        }
        if (b == 'e' && boolMatchLen == 4) {
            boolMatchLen++;
            enableThinking = false;
            //logger.info("[状态机] *** 提取到 enable_thinking=false");
            resetToNormal();
            return;
        }

        /* 不匹配任何布尔字面量，重置 */
        resetToNormal();
    }

    /**
     * 重置状态机到 NORMAL，准备扫描下一个目标字段。
     */
    void resetToNormal() {
        state = STATE_NORMAL;
        currentTarget = null;
        keyMatchLen = 0;
        valueBuf = null;
        afterColon = false;
        inValueString = false;
        boolMatchLen = 0;
    }

    /**
     * 检查 chunk 中从 offset 开始是否匹配任意目标字段的 key。
     * 匹配成功返回对应的 TargetField，否则返回 null。
     * chunk 数据不足时返回 null（等下个 chunk 再试）。
     */
    static TargetField findMatchingTarget(byte[] chunk, int offset) {
        for (TargetField target : ALL_TARGETS) {
            if (matchesKey(chunk, offset, target.keyBytes())) {
                return target;
            }
        }
        return null;
    }

    static String stateName(int s) {
        return switch (s) {
            case STATE_NORMAL -> "NORMAL";
            case STATE_KEY_MATCH -> "KEY_MATCH";
            case STATE_VALUE_PARSE -> "VALUE_PARSE";
            case STATE_DONE -> "DONE";
            default -> "UNKNOWN(" + s + ")";
        };
    }

    static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }

    /**
     * 检查 chunk 中从 offset 开始是否完整匹配 key。
     * chunk 数据不足时返回 false（等下个 chunk 再试）。
     */
    static boolean matchesKey(byte[] chunk, int offset, byte[] key) {
        if (offset + key.length > chunk.length) {
            return false;
        }
        for (int k = 0; k < key.length; k++) {
            if (chunk[offset + k] != key[k]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 关闭并清理 bodyBuffer 资源。
     */
    public void close() throws IOException {
        bodyBuffer.close();
    }

    String getModelName() {
        return modelName;
    }

    Boolean getEnableThinking() {
        return enableThinking;
    }

    static String previewChunk(byte[] chunk) {
        int len = Math.min(chunk.length, CHUNK_PREVIEW_MAX);
        String preview = new String(chunk, 0, len, StandardCharsets.UTF_8);
        if (chunk.length > CHUNK_PREVIEW_MAX) {
            preview += "...(+" + (chunk.length - CHUNK_PREVIEW_MAX) + "bytes)";
        }
        return preview;
    }

    /**
     * 目标字段定义。
     */
    static class TargetField {
        private final String name;
        private final FieldType type;
        private final byte[] keyBytes;

        TargetField(String name, FieldType type) {
            this.name = name;
            this.type = type;
            this.keyBytes = name.getBytes(StandardCharsets.US_ASCII);
        }

        String name() { return name; }
        FieldType type() { return type; }
        byte[] keyBytes() { return keyBytes; }
    }

    /**
     * 字段类型。
     */
    enum FieldType {
        STRING, BOOLEAN
    }

    public static class TransformResult {
        private final String modelName;
        private final String nodeId;
        private final Boolean enableThinking;

        public TransformResult(String modelName, String nodeId) {
            this(modelName, nodeId, null);
        }

        public TransformResult(String modelName, String nodeId, Boolean enableThinking) {
            this.modelName = modelName;
            this.nodeId = nodeId;
            this.enableThinking = enableThinking;
        }

        public String getModelName() {
            return modelName;
        }

        public String getNodeId() {
            return nodeId;
        }

        public Boolean getEnableThinking() {
            return enableThinking;
        }
    }

    public static class ForwarderException extends IOException {
        private static final long serialVersionUID = 1L;
		private final int httpStatus;
        private final String param;

        public ForwarderException(int httpStatus, String message, String param) {
            super(message);
            this.httpStatus = httpStatus;
            this.param = param;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public String getParam() {
            return param;
        }
    }
}
