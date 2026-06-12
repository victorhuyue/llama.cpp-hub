package org.mark.llamacpp.server.service;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes to two output streams simultaneously.
 * Used to log request content to file while forwarding to the target.
 */
final class TeeOutputStream extends OutputStream {
    private final OutputStream primary;
    private final OutputStream secondary;

    TeeOutputStream(OutputStream primary, OutputStream secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public void write(int b) throws IOException {
        primary.write(b);
        secondary.write(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        primary.write(b);
        secondary.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        primary.write(b, off, len);
        secondary.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        primary.flush();
        secondary.flush();
    }

    @Override
    public void close() throws IOException {
        secondary.close();
    }
}
