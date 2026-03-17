package edu.m4z.unifiedstorage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Root configuration for UnifiedStorage.
 *
 * <pre>
 * unified-storage:
 *   chunk-size: 67108864   # 64 MB global default
 *   reports:
 *     path: s3://my-bucket/data/reports
 *     region: eu-west-1
 *     access-key: secret://cyberark/s3/reports/access-key
 *     secret-key: secret://cyberark/s3/reports/secret-key
 *   archives:
 *     path: gs://another-bucket/data/archives
 *     credentials-json: secret://cyberark/gcs/archives/sa-json
 *   temp:
 *     path: file:///opt/tmp
 * </pre>
 *
 * Mount point keys are logical names (reports, archives, temp...).
 * The provider is automatically selected based on the URI scheme:
 * s3:// → S3FileSystemProvider, gs:// → GcsFileSystemProvider, file:// → JDK default.
 *
 * Provider-specific properties (region, credentials, etc.) are defined
 * in their respective provider config classes (S3MountProperties, GcsMountProperties).
 *
 * Azure Blob support (abfs://, wasbs://) is planned for a future release.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "unified-storage")
public class StorageProperties {

    /**
     * Global chunk size for cross-provider streaming pipe and multipart uploads.
     * Default: 64 MB. Can be overridden per mount via partSize.
     */
    private long chunkSize = 64 * 1024 * 1024L;

    /**
     * POSIX metadata management configuration.
     * When disabled, no database interaction occurs for permissions, ownership,
     * or precise timestamps. The JPA datasource dependency becomes optional.
     */
    private Posix posix = new Posix();

    @Data
    public static class Posix {
        /**
         * Enable or disable POSIX metadata persistence.
         *
         * true  (default) — permissions, owner, group and timestamps are stored
         *                   in the storage_posix_metadata table after each write.
         * false           — all POSIX operations are no-ops; no database required.
         *
         * Example:
         * <pre>
         * unified-storage:
         *   posix:
         *     enabled: false
         * </pre>
         */
        private boolean enabled = true;
    }

    /**
     * Logical mount points map.
     * Key = logical mount name (e.g. reports, archives, temp).
     * Value = mount configuration (provider-specific subclass at runtime).
     */
    private Map<String, MountPointProperties> mounts = new LinkedHashMap<>();

    /**
     * Base class for a logical mount point configuration.
     *
     * Contains only provider-agnostic attributes.
     * Provider-specific attributes are in subclasses:
     * - {@code S3MountProperties}  for s3:// mounts
     * - {@code GcsMountProperties} for gs:// mounts
     * - Azure Blob support planned (abfs:// / wasbs://)
     */
    @Data
    public static class MountPointProperties {

        /** Mount URI: s3://bucket/path, gs://bucket/path, file:///path */
        private String path;

        /**
         * Multipart upload threshold in bytes.
         * If null, inherits global chunk-size.
         * Files larger than this threshold trigger multipart/resumable upload.
         */
        private Long multipartThreshold;

        /**
         * Part size for multipart uploads in bytes.
         * If null, inherits global chunk-size.
         */
        private Long partSize;
    }
}
