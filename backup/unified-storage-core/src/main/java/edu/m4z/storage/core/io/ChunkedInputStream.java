package edu.m4z.storage.core.io;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.BiFunction;

public class ChunkedInputStream extends InputStream {
    public static final int DEFAULT_CHUNK_SIZE = 8 * 1024 * 1024;
    private final long totalSize;
    private final int chunkSize;
    private final BiFunction<Long, Integer, InputStream> fetcher;
    private long pos;
    private InputStream chunk;
    private boolean closed;

    public ChunkedInputStream(long ts, int cs, BiFunction<Long, Integer, InputStream> f) {
        totalSize = ts;
        chunkSize = cs > 0 ? cs : DEFAULT_CHUNK_SIZE;
        fetcher = f;
    }

    public ChunkedInputStream(long ts, BiFunction<Long, Integer, InputStream> f) {
        this(ts, DEFAULT_CHUNK_SIZE, f);
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int n = read(b, 0, 1);
        return n == -1 ? -1 : b[0] & 0xFF;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (closed) throw new IOException("closed");
        if (totalSize >= 0 && pos >= totalSize) return -1;
        if (chunk == null) {
            chunk = fetchNext();
            if (chunk == null) return -1;
        }
        int n = chunk.read(buf, off, len);
        if (n == -1) {
            chunk.close();
            chunk = fetchNext();
            if (chunk == null) return -1;
            n = chunk.read(buf, off, len);
        }
        if (n > 0) pos += n;
        return n;
    }

    private InputStream fetchNext() throws IOException {
        if (totalSize >= 0 && pos >= totalSize) return null;
        int l = (totalSize >= 0) ? (int) Math.min(chunkSize, totalSize - pos) : chunkSize;
        if (l <= 0) return null;
        try {
            return fetcher.apply(pos, l);
        } catch (Exception e) {
            throw new IOException("chunk@" + pos, e);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            if (chunk != null) chunk.close();
        }
    }
}
