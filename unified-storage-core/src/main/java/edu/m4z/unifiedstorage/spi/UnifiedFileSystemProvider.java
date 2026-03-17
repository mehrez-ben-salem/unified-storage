package edu.m4z.unifiedstorage.spi;

import edu.m4z.unifiedstorage.config.StorageProperties.MountPointProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.spi.FileSystemProvider;

/**
 * Extended SPI contract implemented by each storage provider.
 *
 * Extends {@link FileSystemProvider} (standard JDK) to add:
 * - Per-mount initialization with resolved credentials
 * - Range reads for large files
 * - Explicit multipart upload handle
 */
public abstract class UnifiedFileSystemProvider extends FileSystemProvider {

    /**
     * Initializes the provider for a logical mount point.
     * Called by StorageRegistry at startup for each configured mount.
     *
     * The mount properties may be a provider-specific subclass
     * (S3MountProperties, GcsMountProperties) cast from the base type.
     *
     * @param mountName  logical name (e.g. "reports", "archives")
     * @param properties mount properties (path, credentials already resolved)
     */
    public abstract void registerMount(String mountName, MountPointProperties properties);

    /**
     * Partial read (range request) for large files.
     * Avoids loading the entire file into memory.
     *
     * @param path   file path
     * @param offset byte offset
     * @param length number of bytes to read
     */
    public abstract InputStream newRangeInputStream(
            java.nio.file.Path path, long offset, long length) throws IOException;

    /**
     * Initiates an explicit multipart upload.
     * Advanced usage — SmartOutputStream triggers this automatically.
     */
    public abstract MultipartUploadHandle initiateMultipartUpload(
            java.nio.file.Path path) throws IOException;

    /**
     * Returns true if this provider supports the given URI scheme.
     * Example: "s3", "gs", "abfs", "file"
     */
    public boolean supportsScheme(String scheme) {
        return getScheme().equalsIgnoreCase(scheme);
    }
}
