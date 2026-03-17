package edu.m4z.unifiedstorage.s3;

import edu.m4z.unifiedstorage.spi.MultipartUploadHandle;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * S3 implementation of the multipart upload handle.
 * Used by {@link edu.m4z.unifiedstorage.core.CrossProviderCopyHandler}
 * for cross-provider copies targeting S3.
 */
public class S3MultipartUploadHandle implements MultipartUploadHandle {

    private final S3AsyncClient client;
    private final S3Path        path;
    private final String        uploadId;

    S3MultipartUploadHandle(S3AsyncClient client, S3Path path, String uploadId) {
        this.client   = client;
        this.path     = path;
        this.uploadId = uploadId;
    }

    @Override
    public String getUploadId() {
        return uploadId;
    }

    @Override
    public CompletedPart uploadPart(int partNumber, InputStream data, long size) throws IOException {
        try {
            byte[] bytes = data.readAllBytes();
            UploadPartResponse resp = client.uploadPart(
                    UploadPartRequest.builder()
                            .bucket(path.getBucket())
                            .key(path.getS3Key())
                            .uploadId(uploadId)
                            .partNumber(partNumber)
                            .build(),
                    AsyncRequestBody.fromBytes(bytes)
            ).join();
            return new CompletedPart(partNumber, resp.eTag());
        } catch (Exception e) {
            throw new IOException("Error uploading part " + partNumber, e);
        }
    }

    @Override
    public void complete(List<CompletedPart> parts) throws IOException {
        try {
            List<software.amazon.awssdk.services.s3.model.CompletedPart> s3Parts = parts.stream()
                    .map(p -> software.amazon.awssdk.services.s3.model.CompletedPart.builder()
                            .partNumber(p.partNumber())
                            .eTag(p.eTag())
                            .build())
                    .toList();

            client.completeMultipartUpload(
                    CompleteMultipartUploadRequest.builder()
                            .bucket(path.getBucket())
                            .key(path.getS3Key())
                            .uploadId(uploadId)
                            .multipartUpload(m -> m.parts(s3Parts))
                            .build()
            ).join();
        } catch (Exception e) {
            throw new IOException("Error finalizing S3 multipart upload", e);
        }
    }

    @Override
    public void abort() throws IOException {
        try {
            client.abortMultipartUpload(
                    AbortMultipartUploadRequest.builder()
                            .bucket(path.getBucket())
                            .key(path.getS3Key())
                            .uploadId(uploadId)
                            .build()
            ).join();
        } catch (Exception e) {
            throw new IOException("Error aborting S3 multipart upload", e);
        }
    }
}
