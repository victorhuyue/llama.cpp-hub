package org.mark.llamacpp.server.service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 统一请求体缓冲：内存 -> 32KB未找到model落盘 -> 流式注入/转发到OutputStream。
 * 替代原 bodyBuffer + deferredOutput，消除多份内存拷贝。
 */
public class UnifiedBodyBuffer {

    private static final int SPOOL_THRESHOLD = 32 * 1024;

    private final ByteArrayOutputStream memoryBuffer = new ByteArrayOutputStream();
    private FileOutputStream fileOutput;
    private File spoolFile;

    private long totalBytes = 0;
    private boolean spilled;
    private boolean allowSpill = true;

    private boolean closed;

    /**
     * model 已找到时调用，关闭落盘允许。
     */
    public void setModelFound() {
        this.allowSpill = false;
    }

    /**
     * 写入一个 chunk。
     */
    public void write(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return;
        }

        synchronized (this) {
            if (this.spilled) {
                this.fileOutput.write(data);
                this.totalBytes += data.length;
                return;
            }

            this.memoryBuffer.write(data);
            this.totalBytes += data.length;

            if (!this.spilled && this.allowSpill && this.totalBytes > SPOOL_THRESHOLD) {
                spoolToFile();
                this.spilled = true;
            }
        }
    }

    /**
     * 流式转发到目标输出流（纯转发，不做任何修改）。
     * @return 写入的字节数
     */
    public long streamTo(OutputStream output) throws IOException {
        InputStream source = null;

        synchronized (this) {
            if (this.spoolFile != null) {
                source = Files.newInputStream(this.spoolFile.toPath());
            } else {
                byte[] memData = this.memoryBuffer.toByteArray();
                source = new java.io.ByteArrayInputStream(memData);
            }
        }

        try {
            byte[] buf = new byte[8192];
            long written = 0;
            int n;
            while ((n = source.read(buf)) > 0) {
                output.write(buf, 0, n);
                written += n;
            }
            return written;
        } finally {
            if (source != null && this.spoolFile != null) {
                source.close();
            }
        }
    }

    /**
     * 流式转发到目标输出流，在最后一个 } 前注入指定字符串。
     * 从 body 末尾向前扫描找最后一个非字符串内的 }，不依赖写阶段的状态追踪。
     * @param injectionStr 要注入的 JSON 片段（不含前导逗号）
     * @return 注入的字节数（"," + injectionStr + "}" 的增量）
     */
    public long streamInjected(OutputStream output, String injectionStr) throws IOException {
        long closeBracePos = findLastClosingBrace();
        if (closeBracePos < 0) {
            throw new IOException("No closing brace found in body");
        }

        byte[] injectionBytes = ("," + injectionStr).getBytes(StandardCharsets.UTF_8);
        InputStream source = null;

        synchronized (this) {
            if (this.spoolFile != null) {
                source = Files.newInputStream(this.spoolFile.toPath());
            }
        }

        try {
            if (source != null) {
                try {
                    long p = 0;
                    byte[] buf = new byte[8192];
                    while (p < closeBracePos) {
                        int toRead = (int) Math.min(buf.length, closeBracePos - p);
                        int n = source.read(buf, 0, toRead);
                        if (n <= 0) break;
                        output.write(buf, 0, n);
                        p += n;
                    }
                    output.write(injectionBytes);
                    output.write('}');
                } finally {
                    source.close();
                }
            } else {
                byte[] memData = this.memoryBuffer.toByteArray();
                output.write(memData, 0, (int) closeBracePos);
                output.write(injectionBytes);
                output.write('}');
            }

            return injectionBytes.length + 1;
        } finally {
            if (source != null) {
                source.close();
            }
        }
    }

    /**
     * 从 body 末尾向前扫描，找到最后一个非字符串内的 } 的位置。
     * 读取尾部最多 1MB 到内存进行扫描，避免加载整个 body。
     */
    private long findLastClosingBrace() throws IOException {
        long bodyLen = this.totalBytes;
        if (bodyLen <= 0) {
            return -1;
        }

        byte[] tail;
        synchronized (this) {
            if (this.spoolFile != null) {
                long tailLen = Math.min(bodyLen, 1024 * 1024);
                tail = new byte[(int) tailLen];
                int toRead = (int) tailLen;
                long startPos = bodyLen - tailLen;
                try (RandomAccessFile raf = new RandomAccessFile(this.spoolFile, "r")) {
                    raf.seek(startPos);
                    int total = 0;
                    while (total < toRead) {
                        int n = raf.read(tail, total, toRead - total);
                        if (n <= 0) break;
                        total += n;
                    }
                }
            } else {
                tail = this.memoryBuffer.toByteArray();
            }
        }

        // 从后向前扫描，跳过字符串内的 }
        boolean inString = false;
        boolean escaped = false;
        for (int i = tail.length - 1; i >= 0; i--) {
            byte b = tail[i];
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (b == '\\') {
                    escaped = true;
                } else if (b == '"') {
                    inString = false;
                }
            } else {
                if (b == '"') {
                    inString = true;
                    escaped = false;
                } else if (b == '}') {
                    long absPos;
                    if (this.spoolFile != null && tail.length < bodyLen) {
                        absPos = (bodyLen - tail.length) + i;
                    } else {
                        absPos = i;
                    }
                    return absPos;
                }
            }
        }
        return -1;
    }

    public long getTotalBytes() {
        return this.totalBytes;
    }

    /**
     * 关闭并清理资源。
     */
    public synchronized void close() throws IOException {
        if (this.closed) {
            return;
        }
        this.closed = true;
        IOException failure = null;
        try {
            if (this.fileOutput != null) {
                this.fileOutput.close();
                this.fileOutput = null;
            }
        } catch (IOException e) {
            failure = e;
        } finally {
            if (this.spoolFile != null) {
                Files.deleteIfExists(this.spoolFile.toPath());
                this.spoolFile = null;
            }
            this.memoryBuffer.reset();
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void spoolToFile() throws IOException {
        this.spoolFile = File.createTempFile("llama-body-", ".json");
        this.fileOutput = new FileOutputStream(this.spoolFile);
        this.memoryBuffer.writeTo(this.fileOutput);
        this.fileOutput.flush();
        this.memoryBuffer.reset();
    }
}
