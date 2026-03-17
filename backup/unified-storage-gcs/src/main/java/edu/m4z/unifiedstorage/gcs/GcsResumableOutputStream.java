package edu.m4z.unifiedstorage.gcs;

import lombok.extern.slf4j.Slf4j;

import com.google.cloud.WriteChannel;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import edu.m4z.unifiedstorage.posix.PosixMetadataService;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * OutputStream for GCS using Resumable Upload.
 *
 * GCS Resumable Upload is recommended for any file larger than 5 MB.
 * It supports resume on network failure and is optimal for large files.
 *
 * The write chunk size is configurable (chunkSize).
 * Only one chunk is held in memory at a time: compatible with 100 GB files.
 *
 * POSIX metadata update after close() is delegated to {@link PosixMetadataService}
 * and respects the {@code unified-storage.posix.enabled} flag.
 */
@Slf4j
public class GcsResumableOutputStream extends OutputStream {

    private final GcsPath              path;
    private final WriteChannel         writeChannel;
    private final long                 chunkSize;
    private final PosixMetadataService posixService;

    private final byte[] buffer;
    private int     bufferPos    = 0;
    private long    totalWritten = 0;
    private boolean closed       = false;

    public GcsResumableOutputStream(GcsPath path,
                                     Storage storage,
                                     long chunkSize,
                                     PosixMetadataService posixService) {
        this.path         = path;
        this.chunkSize    = chunkSize;
        this.posixService = posixService;
        this.buffer       = new byte[(int) Math.min(chunkSize, Integer.MAX_VALUE)];

        BlobInfo blobInfo = BlobInfo.newBuilder(path.getBucket(), path.getBlobName()).build();
        this.writeChannel = storage.writer(blobInfo);

        try {
            this.writeChannel.setChunkSize((int) Math.min(chunkSize, Integer.MAX_VALUE));
        } catch (Exception e) {
            log.warn("Failed to set GCS chunk size: {}", e.getMessage());
        }

        log.debug("GCS Resumable upload started: gs://{}/{}", path.getBucket(), path.getBlobName());
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        int remaining = len;
        int srcOffset = off;

        while (remaining > 0) {
            int spaceInBuffer = buffer.length - bufferPos;
            int toWrite = Math.min(remaining, spaceInBuffer);

            System.arraycopy(b, srcOffset, buffer, bufferPos, toWrite);
            bufferPos    += toWrite;
            totalWritten += toWrite;
            srcOffset    += toWrite;
            remaining    -= toWrite;

            if (bufferPos >= buffer.length) {
                flushBuffer();
            }
        }
    }

    @Override
    public void flush() throws IOException {
        // Do not force intermediate flush on GCS — the SDK manages chunks internally
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;

        if (bufferPos > 0) flushBuffer();

        try {
            writeChannel.close();
            log.info("GCS Resumable upload completed: gs://{}/{} ({} bytes)",
                    path.getBucket(), path.getBlobName(), totalWritten);
            posixService.saveOrUpdateFile(path.toUri().toString(), totalWritten);
        } catch (IOException e) {
            log.error("Error finalizing GCS upload: {}", path, e);
            throw e;
        }
    }

    private void flushBuffer() throws IOException {
        if (bufferPos == 0) return;
        try {
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, bufferPos);
            while (byteBuffer.hasRemaining()) {
                writeChannel.write(byteBuffer);
            }
            log.debug("GCS chunk written: {} bytes (total: {} bytes)", bufferPos, totalWritten);
            bufferPos = 0;
        } catch (IOException e) {
            throw new IOException("Error writing GCS chunk: " + path, e);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Stream closed: " + path);
    }
}
