package edu.m4z.unifiedstorage.core;

import lombok.extern.slf4j.Slf4j;

import edu.m4z.unifiedstorage.config.StorageProperties;
import edu.m4z.unifiedstorage.spi.MultipartUploadHandle;
import edu.m4z.unifiedstorage.spi.MultipartUploadHandle.CompletedPart;
import edu.m4z.unifiedstorage.spi.UnifiedFileSystemProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-provider copy via direct streaming.
 *
 * Strategy:
 * - InputStream on the source side (S3 / GCS / Azure Blob / local)
 * - OutputStream on the destination side with automatic multipart upload
 * - Chunked pipe with configurable chunk size (chunk-size in application.yml)
 * - Zero temporary files, zero full in-memory buffering
 *
 * Invoked by providers on Files.copy(source, target)
 * when source and target belong to different providers.
 *
 * For files larger than 100 GB, streaming is fully sequential by chunks:
 * only one chunk of configurable size is held in memory at a time.
 */
@Slf4j
@Component
public class CrossProviderCopyHandler {

    private final StorageProperties properties;

    public CrossProviderCopyHandler(StorageProperties properties) {
        this.properties = properties;
    }

    /**
     * Copies a file between two distinct providers.
     *
     * @param source         source path (S3, GCS, Azure Blob, or local)
     * @param sourceProvider source provider
     * @param target         target path (S3, GCS, Azure Blob, or local)
     * @param targetProvider target provider
     */
    public void copy(Path source, UnifiedFileSystemProvider sourceProvider,
                     Path target, UnifiedFileSystemProvider targetProvider) throws IOException {

        long chunkSize = properties.getChunkSize();
        log.info("Cross-provider copy: {} → {} (chunk: {} MB)",
                source, target, chunkSize / (1024 * 1024));

        // local → cloud or cloud → local: simple OutputStream is sufficient
        // cloud → cloud: multipart upload on the destination side
        if (isCloudProvider(targetProvider)) {
            copyWithMultipart(source, sourceProvider, target, targetProvider, chunkSize);
        } else {
            copyWithOutputStream(source, sourceProvider, target, targetProvider, chunkSize);
        }
    }

    // ----------------------------------------------------------------
    // Streaming via multipart upload on the destination (cloud → cloud)
    // ----------------------------------------------------------------

    private void copyWithMultipart(Path source, UnifiedFileSystemProvider sourceProvider,
                                   Path target, UnifiedFileSystemProvider targetProvider,
                                   long chunkSize) throws IOException {

        MultipartUploadHandle handle = targetProvider.initiateMultipartUpload(target);
        List<CompletedPart> completedParts = new ArrayList<>();
        int partNumber = 1;

        try (InputStream in = sourceProvider.newInputStream(source, StandardOpenOption.READ)) {
            byte[] buffer = new byte[(int) Math.min(chunkSize, Integer.MAX_VALUE)];
            int bytesRead;
            long partBuffer = 0;
            List<byte[]> currentPartData = new ArrayList<>();

            while ((bytesRead = in.read(buffer)) != -1) {
                byte[] chunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                currentPartData.add(chunk);
                partBuffer += bytesRead;

                if (partBuffer >= chunkSize) {
                    CompletedPart part = uploadPart(handle, partNumber++, currentPartData, partBuffer);
                    completedParts.add(part);
                    currentPartData.clear();
                    partBuffer = 0;
                }
            }

            // Last part (may be smaller than chunkSize)
            if (!currentPartData.isEmpty()) {
                CompletedPart part = uploadPart(handle, partNumber, currentPartData, partBuffer);
                completedParts.add(part);
            }

            handle.complete(completedParts);
            log.info("Cross-provider copy completed: {} parts uploaded", completedParts.size());

        } catch (IOException e) {
            log.error("Cross-provider copy failed, aborting multipart upload", e);
            try {
                handle.abort();
            } catch (IOException abortEx) {
                log.error("Error aborting multipart upload", abortEx);
            }
            throw e;
        }
    }

    // ----------------------------------------------------------------
    // Streaming via simple OutputStream (cloud → local or local → cloud)
    // ----------------------------------------------------------------

    private void copyWithOutputStream(Path source, UnifiedFileSystemProvider sourceProvider,
                                      Path target, UnifiedFileSystemProvider targetProvider,
                                      long chunkSize) throws IOException {

        try (InputStream in = sourceProvider.newInputStream(source, StandardOpenOption.READ);
             OutputStream out = targetProvider.newOutputStream(target,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            byte[] buffer = new byte[(int) Math.min(chunkSize, Integer.MAX_VALUE)];
            long totalBytes = 0;
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }

            log.info("Cross-provider copy completed: {} bytes transferred", totalBytes);
        }
    }

    // ----------------------------------------------------------------
    // Assemble chunks for a part and upload
    // ----------------------------------------------------------------

    private CompletedPart uploadPart(MultipartUploadHandle handle, int partNumber,
                                     List<byte[]> chunks, long totalSize) throws IOException {
        InputStream partStream = new ChunkedListInputStream(chunks);
        return handle.uploadPart(partNumber, partStream, totalSize);
    }

    private boolean isCloudProvider(UnifiedFileSystemProvider provider) {
        return provider != null && !provider.supportsScheme("file");
    }

    // ----------------------------------------------------------------
    // InputStream over a list of byte arrays
    // ----------------------------------------------------------------

    private static class ChunkedListInputStream extends InputStream {
        private final List<byte[]> chunks;
        private int chunkIndex = 0;
        private int posInChunk = 0;

        ChunkedListInputStream(List<byte[]> chunks) {
            this.chunks = chunks;
        }

        @Override
        public int read() throws IOException {
            while (chunkIndex < chunks.size()) {
                byte[] chunk = chunks.get(chunkIndex);
                if (posInChunk < chunk.length) {
                    return chunk[posInChunk++] & 0xFF;
                }
                chunkIndex++;
                posInChunk = 0;
            }
            return -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (chunkIndex >= chunks.size()) return -1;
            byte[] chunk = chunks.get(chunkIndex);
            int available = chunk.length - posInChunk;
            int toRead = Math.min(len, available);
            System.arraycopy(chunk, posInChunk, b, off, toRead);
            posInChunk += toRead;
            if (posInChunk >= chunk.length) {
                chunkIndex++;
                posInChunk = 0;
            }
            return toRead;
        }
    }
}
