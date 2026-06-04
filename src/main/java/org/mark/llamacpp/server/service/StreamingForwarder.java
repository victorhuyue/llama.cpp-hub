package org.mark.llamacpp.server.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StreamingForwarder {

    private static final Logger logger = LoggerFactory.getLogger(StreamingForwarder.class);

    private static final Pattern MODEL_PATTERN = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STREAM_PATTERN = Pattern.compile("\"stream\"\\s*:\\s*(true|false)");

    private static final int QUEUE_CAPACITY = 16;
    private static final int MODEL_BUFFER_LIMIT = 1024;
    private static final int CHUNK_PREVIEW_MAX = 120;

    private static final byte[] EOF_MARKER = new byte[0];

    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private volatile IOException failure;

    private final UnifiedBodyBuffer bodyBuffer = new UnifiedBodyBuffer();

    private StringBuilder extractBuffer;
    private String modelName;
    private boolean modelFound;
    private volatile boolean stream;

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
        //logger.info("[chunk#{}] {} 字节: {}", seq, chunk.length, previewChunk(chunk));
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
        long seq = chunkSeq.incrementAndGet();
        //logger.info("[chunk#{} LAST] {} 字节: {}", seq, chunk.length, previewChunk(chunk));
        this.lastChunk = chunk;
    }

    public void complete() {
        closed.compareAndSet(false, true);
        queue.offer(EOF_MARKER);
    }

    public void fail(IOException e) {
        failed.compareAndSet(false, true);
        this.failure = e;
        closed.set(true);
        queue.offer(EOF_MARKER);
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
        if (modelName == null || modelName.isBlank()) {
            if (extractBuffer != null && extractBuffer.length() >= MODEL_BUFFER_LIMIT) {
                throw new ForwarderException(400, "Model field not found within first " + MODEL_BUFFER_LIMIT + " characters of request body", "model");
            }
            throw new ForwarderException(400, "Missing required parameter: model", "model");
        }

        /* 处理最后一个 chunk */
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

        return new TransformResult(modelName, stream, nodeId);
    }

    /**
     * 将 bodyBuffer 中的数据流式转发到目标输出流。
     * 有 nodeId → 纯转发；无 nodeId → 注入采样参数后转发。
     */
    public void streamBody(OutputStream output) throws IOException {
        if (nodeId != null && !nodeId.isBlank()) {
            long written = bodyBuffer.streamTo(output);
            logger.info("[远程代理] nodeId={}, streamed={} bytes", nodeId, written);
        } else {
            String injection = SamplingInjectionBuilder.buildInjectionString(modelName);
            if (!injection.isEmpty()) {
                long injected = bodyBuffer.streamInjected(output, injection);
                logger.info("[注入] model={}, injected={} bytes: ,{}", modelName, injected, injection);
            } else {
                bodyBuffer.streamTo(output);
            }
        }
    }

    void extractFields(byte[] chunk) {
        String chunkStr = new String(chunk, StandardCharsets.UTF_8);
        if (!modelFound) {
            if (extractBuffer == null) {
                extractBuffer = new StringBuilder(MODEL_BUFFER_LIMIT);
            }
            if (extractBuffer.length() < MODEL_BUFFER_LIMIT) {
                extractBuffer.append(chunkStr);
                Matcher m = MODEL_PATTERN.matcher(extractBuffer);
                if (m.find()) {
                    modelName = m.group(1);
                    modelFound = true;
                    bodyBuffer.setModelFound();
                    logger.info("流式转发提取到模型字段: {}", modelName);
                }
            }
        }
        if (!this.stream) {
            Matcher m = STREAM_PATTERN.matcher(chunkStr);
            if (m.find()) {
                this.stream = "true".equals(m.group(1));
            }
        }
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

    static String previewChunk(byte[] chunk) {
        int len = Math.min(chunk.length, CHUNK_PREVIEW_MAX);
        String preview = new String(chunk, 0, len, StandardCharsets.UTF_8);
        if (chunk.length > CHUNK_PREVIEW_MAX) {
            preview += "...(+" + (chunk.length - CHUNK_PREVIEW_MAX) + "bytes)";
        }
        return preview;
    }

    public static class TransformResult {
        private final String modelName;
        private final boolean stream;
        private final String nodeId;

        public TransformResult(String modelName, boolean stream, String nodeId) {
            this.modelName = modelName;
            this.stream = stream;
            this.nodeId = nodeId;
        }

        public String getModelName() {
            return modelName;
        }

        public boolean isStream() {
            return stream;
        }

        public String getNodeId() {
            return nodeId;
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
