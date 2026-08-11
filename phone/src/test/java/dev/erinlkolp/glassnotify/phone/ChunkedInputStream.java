package dev.erinlkolp.glassnotify.phone;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Delivers at most maxChunk bytes per read() call. Real RFCOMM sockets
 * fragment arbitrarily; this makes that behaviour reproducible in a test.
 */
final class ChunkedInputStream extends InputStream {

    private final ByteArrayInputStream delegate;
    private final int maxChunk;

    ChunkedInputStream(byte[] data, int maxChunk) {
        if (maxChunk < 1) {
            throw new IllegalArgumentException("maxChunk must be >= 1");
        }
        this.delegate = new ByteArrayInputStream(data);
        this.maxChunk = maxChunk;
    }

    @Override
    public int read() throws IOException {
        return delegate.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return delegate.read(b, off, Math.min(len, maxChunk));
    }
}
