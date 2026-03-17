package edu.m4z.storage.core.io;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Consumer;

public class ChunkedOutputStream extends OutputStream {
    public static final int DEFAULT_CHUNK_SIZE = 8 * 1024 * 1024;
    private final int chunkSize;
    private final Consumer<Chunk> writer;
    private final Runnable onComplete, onAbort;
    private byte[] buffer;
    private int bufPos;
    private int partNum = 1;
    private long totalWritten;
    private boolean closed;

    public ChunkedOutputStream(int cs, Consumer<Chunk> w, Runnable c, Runnable a) {
        chunkSize = cs > 0 ? cs : DEFAULT_CHUNK_SIZE;
        writer = w;
        onComplete = c;
        onAbort = a;
        buffer = new byte[chunkSize];
    }

    public record Chunk(byte[] data, int length, int partNumber, long offset) {
        public byte[] bytes () {
            if (length == data.length) return data;
            byte[] t = new byte[length];
            System.arraycopy(data, 0, t, 0, length);
            return t;
        }
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] d, int off, int len) throws IOException {
        if (closed) throw new IOException("closed");
        int rem = len, src = off;
        while (rem > 0) {
            int sp = chunkSize - bufPos, cp = Math.min(rem, sp);
            System.arraycopy(d, src, buffer, bufPos, cp);
            bufPos += cp;
            src += cp;
            rem -= cp;
            if (bufPos >= chunkSize) flush0();
        }
    }

    private void flush0() throws IOException {
        if (bufPos == 0) return;
        try {
            writer.accept(new Chunk(buffer, bufPos, partNum, totalWritten));
            totalWritten += bufPos;
            partNum++;
            bufPos = 0;
        } catch (Exception e) {
            try {
                if (onAbort != null) onAbort.run();
            } catch (Exception x) {
            }
            throw new IOException("chunk fail", e);
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            if (bufPos > 0) flush0();
            if (onComplete != null) onComplete.run();
        } catch (IOException e) {
            try {
                if (onAbort != null) onAbort.run();
            } catch (Exception x) {
            }
            throw e;
        } finally {
            buffer = null;
        }
    }
}
