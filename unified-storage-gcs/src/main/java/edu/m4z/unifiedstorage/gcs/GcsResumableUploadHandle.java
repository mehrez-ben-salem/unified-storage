package edu.m4z.unifiedstorage.gcs;

import lombok.extern.slf4j.Slf4j;

import com.google.cloud.WriteChannel;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import edu.m4z.unifiedstorage.posix.PosixMetadataService;
import edu.m4z.unifiedstorage.spi.MultipartUploadHandle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

/**
 * GCS Resumable Upload handle.
 *
 * GCS does not have a Multipart Upload API equivalent to S3.
 * The Resumable Upload is the equivalent: an open WriteChannel
 * into which parts are written sequentially.
 *
 * Used by {@link edu.m4z.unifiedstorage.core.CrossProviderCopyHandler}
 * when the copy target is GCS.
 *
 * POSIX metadata update after complete() is delegated to {@link PosixMetadataService}
 * and respects the {@code unified-storage.posix.enabled} flag.
 */
@Slf4j
public class GcsResumableUploadHandle implements MultipartUploadHandle {

    private final GcsPath              path;
    private final WriteChannel         writeChannel;
    private final long                 chunkSize;
    private final PosixMetadataService posixService;
    private final String               sessionId;

    private long    totalWritten = 0;
    private boolean completed    = false;

    public GcsResumableUploadHandle(GcsPath path, Storage storage, long chunkSize,
                                     PosixMetadataService posixService) {
        this.path         = path;
        this.chunkSize    = chunkSize;
        this.posixService = posixService;
        this.sessionId    = UUID.randomUUID().toString();

        BlobInfo blobInfo = BlobInfo.newBuilder(path.getBucket(), path.getBlobName()).build();
        this.writeChannel = storage.writer(blobInfo);
        this.writeChannel.setChunkSize((int) Math.min(chunkSize, Integer.MAX_VALUE));

        log.debug("GCS Resumable session started: sessionId={}", sessionId);
    }

    @Override
    public String getUploadId() {
        return sessionId;
    }

    @Override
    public CompletedPart uploadPart(int partNumber, InputStream data, long size) throws IOException {
        byte[] buffer = new byte[(int) Math.min(chunkSize, Integer.MAX_VALUE)];
        int bytesRead;
        long partBytes = 0;

        while ((bytesRead = data.read(buffer)) != -1) {
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead);
            while (byteBuffer.hasRemaining()) {
                writeChannel.write(byteBuffer);
            }
            partBytes    += bytesRead;
            totalWritten += bytesRead;
        }

        log.debug("GCS part {} written ({} bytes)", partNumber, partBytes);
        return new CompletedPart(partNumber, "gcs-part-" + partNumber);
    }

    @Override
    public void complete(List<CompletedPart> parts) throws IOException {
        if (completed) return;
        completed = true;
        try {
            writeChannel.close();
            log.info("GCS Resumable upload completed: gs://{}/{} ({} bytes)",
                    path.getBucket(), path.getBlobName(), totalWritten);
            posixService.saveOrUpdateFile(path.toUri().toString(), totalWritten);
        } catch (IOException e) {
            throw new IOException("Error finalizing GCS resumable upload: " + path, e);
        }
    }

    @Override
    public void abort() throws IOException {
        if (completed) return;
        try {
            writeChannel.close();
            log.warn("GCS Resumable upload aborted: sessionId={}", sessionId);
        } catch (IOException e) {
            log.error("Error aborting GCS upload", e);
        }
    }
}
