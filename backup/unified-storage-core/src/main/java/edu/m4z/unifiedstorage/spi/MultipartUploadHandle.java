package edu.m4z.unifiedstorage.spi;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Handle for an in-progress multipart upload.
 *
 * Lifecycle:
 * <pre>
 *   handle = provider.initiateMultipartUpload(path)
 *   part1  = handle.uploadPart(1, stream1, size1)
 *   part2  = handle.uploadPart(2, stream2, size2)
 *   handle.complete(List.of(part1, part2))
 *   // on error:
 *   handle.abort()
 * </pre>
 *
 * Used internally by SmartOutputStream.
 * Accessible for advanced use cases (parallel upload, retry on error).
 */
public interface MultipartUploadHandle {

    /** Upload identifier (S3 upload ID or GCS session URI). */
    String getUploadId();

    /**
     * Uploads one part.
     *
     * @param partNumber part number (starts at 1)
     * @param data       data stream for this part
     * @param size       exact byte size of the stream
     * @return descriptor of the completed part (eTag or equivalent)
     */
    CompletedPart uploadPart(int partNumber, InputStream data, long size) throws IOException;

    /**
     * Finalizes the multipart upload.
     *
     * @param parts list of parts in order (ascending part number)
     */
    void complete(List<CompletedPart> parts) throws IOException;

    /**
     * Aborts the upload and releases cloud-side resources.
     */
    void abort() throws IOException;

    /** Descriptor of a completed part. */
    record CompletedPart(int partNumber, String eTag) {}
}
