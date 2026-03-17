package edu.m4z.storage.core.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/**
 * SPI interface for storage backends.
 * <p>
 * Each cloud provider (S3, GCS, Azure, NFS) implements this interface.
 * The implementation is discovered via {@link java.util.ServiceLoader}
 * and uses the cloud SDK transparently.
 * </p>
 */
public interface StorageBackend {

    /**
     * The URI scheme: "s3", "gs", "azblob", "nfs"
     */
    String scheme();

    /**
     * Initialize a client for a specific bucket with the given config
     */
    void initBucket(String bucket, java.util.Map<String, String> config);

    InputStream newInputStream(String bucket, String key) throws IOException;

    OutputStream newOutputStream(String bucket, String key) throws IOException;

    BasicFileAttributes readAttributes(String bucket, String key) throws IOException;

    boolean exists(String bucket, String key);

    void delete(String bucket, String key) throws IOException;

    List<String> list(String bucket, String prefix) throws IOException;

    void createDirectory(String bucket, String key) throws IOException;

    void copy(String srcBucket, String srcKey, String dstBucket, String dstKey) throws IOException;
}
