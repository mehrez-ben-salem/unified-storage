package edu.m4z.unifiedstorage.s3;

import lombok.extern.slf4j.Slf4j;

import edu.m4z.unifiedstorage.posix.PosixMetadataService;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Smart OutputStream for S3.
 *
 * Automatically switches between:
 * - Simple PutObject  : if total data stays below the multipart threshold
 * - Multipart Upload  : if data exceeds the threshold (configurable)
 *
 * The calling code sees a plain OutputStream.
 * The decision is transparent and based on the total volume written.
 *
 * Large files (100 GB+):
 * Data is written chunk by chunk (size = partSize).
 * Only one chunk is held in memory at a time.
 *
 * POSIX metadata update after close() is delegated to {@link PosixMetadataService}
 * and respects the {@code unified-storage.posix.enabled} flag.
 */
@Slf4j
public class S3SmartOutputStream extends OutputStream {

    private final S3Path                path;
    private final S3AsyncClient         s3Client;
    private final long                  multipartThreshold;
    private final long                  partSize;
    private final PosixMetadataService  posixService;

    private byte[] buffer;
    private int    bufferPos         = 0;
    private String uploadId;
    private int    partNumber        = 1;
    private final List<CompletedPart> completedParts = new ArrayList<>();
    private boolean multipartStarted = false;
    private long    totalWritten     = 0;
    private boolean closed           = false;

    public S3SmartOutputStream(S3Path path,
                                S3AsyncClient s3Client,
                                long multipartThreshold,
                                long partSize,
                                PosixMetadataService posixService) {
        this.path               = path;
        this.s3Client           = s3Client;
        this.multipartThreshold = multipartThreshold;
        this.partSize           = partSize;
        this.posixService       = posixService;
        this.buffer             = new byte[(int) Math.min(partSize, Integer.MAX_VALUE)];
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
                if (!multipartStarted && totalWritten > multipartThreshold) {
                    startMultipart();
                }
                if (multipartStarted) {
                    flushCurrentPart();
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;

        try {
            if (multipartStarted) {
                if (bufferPos > 0) flushCurrentPart();
                completeMultipart();
                log.info("S3 multipart upload completed: s3://{}/{} ({} parts, {} bytes)",
                        path.getBucket(), path.getS3Key(), partNumber - 1, totalWritten);
            } else {
                putObject();
                log.debug("S3 simple upload: s3://{}/{} ({} bytes)",
                        path.getBucket(), path.getS3Key(), totalWritten);
            }
            posixService.saveOrUpdateFile(path.toUri().toString(), totalWritten);
        } catch (IOException e) {
            if (multipartStarted) abortMultipart();
            throw e;
        }
    }

    private void startMultipart() throws IOException {
        try {
            CreateMultipartUploadResponse resp = s3Client.createMultipartUpload(
                    CreateMultipartUploadRequest.builder()
                            .bucket(path.getBucket()).key(path.getS3Key()).build()
            ).join();
            uploadId = resp.uploadId();
            multipartStarted = true;
            log.debug("Multipart upload started: uploadId={}", uploadId);
        } catch (Exception e) {
            throw new IOException("Failed to start multipart upload: " + path, e);
        }
    }

    private void flushCurrentPart() throws IOException {
        if (bufferPos == 0) return;
        byte[] partData = new byte[bufferPos];
        System.arraycopy(buffer, 0, partData, 0, bufferPos);

        try {
            UploadPartResponse resp = s3Client.uploadPart(
                    UploadPartRequest.builder()
                            .bucket(path.getBucket()).key(path.getS3Key())
                            .uploadId(uploadId).partNumber(partNumber).build(),
                    AsyncRequestBody.fromBytes(partData)
            ).join();

            completedParts.add(CompletedPart.builder()
                    .partNumber(partNumber).eTag(resp.eTag()).build());
            log.debug("Part {} uploaded ({} bytes)", partNumber, bufferPos);
            partNumber++;
            bufferPos = 0;
        } catch (Exception e) {
            throw new IOException("Error uploading part " + partNumber + ": " + path, e);
        }
    }

    private void completeMultipart() throws IOException {
        try {
            s3Client.completeMultipartUpload(
                    CompleteMultipartUploadRequest.builder()
                            .bucket(path.getBucket()).key(path.getS3Key())
                            .uploadId(uploadId).multipartUpload(m -> m.parts(completedParts)).build()
            ).join();
        } catch (Exception e) {
            throw new IOException("Error finalizing multipart upload: " + path, e);
        }
    }

    private void abortMultipart() {
        if (uploadId == null) return;
        try {
            s3Client.abortMultipartUpload(
                    AbortMultipartUploadRequest.builder()
                            .bucket(path.getBucket()).key(path.getS3Key()).uploadId(uploadId).build()
            ).join();
            log.warn("Multipart upload aborted: uploadId={}", uploadId);
        } catch (Exception e) {
            log.error("Error aborting multipart upload", e);
        }
    }

    private void putObject() throws IOException {
        try {
            byte[] data = new byte[bufferPos];
            System.arraycopy(buffer, 0, data, 0, bufferPos);
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(path.getBucket()).key(path.getS3Key()).build(),
                    AsyncRequestBody.fromBytes(data)
            ).join();
        } catch (Exception e) {
            throw new IOException("PutObject failed: " + path, e);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Stream closed: " + path);
    }
}
