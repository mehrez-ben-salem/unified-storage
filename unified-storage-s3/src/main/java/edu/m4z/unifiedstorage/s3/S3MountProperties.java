package edu.m4z.unifiedstorage.s3;

import edu.m4z.unifiedstorage.config.StorageProperties.MountPointProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * S3-specific mount point configuration.
 *
 * Extends the provider-agnostic {@link MountPointProperties} with all
 * attributes specific to Amazon S3 and S3-compatible object stores (MinIO, Ceph, etc.).
 *
 * Example YAML:
 * <pre>
 * unified-storage:
 *   reports:
 *     path: s3://my-bucket/data/reports
 *     region: eu-west-1
 *     access-key: secret://cyberark/s3/access-key
 *     secret-key: secret://cyberark/s3/secret-key
 *
 *   # MinIO (S3-compatible)
 *   local-store:
 *     path: s3://my-bucket/data
 *     endpoint: http://localhost:9000
 *     access-key: minioadmin
 *     secret-key: minioadmin
 * </pre>
 *
 * If access-key and secret-key are omitted, the provider falls back to
 * {@code DefaultCredentialsProvider} (IAM role, IRSA, environment variables).
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class S3MountProperties extends MountPointProperties {

    /**
     * AWS region (e.g. eu-west-1, us-east-1).
     * Required unless using a custom endpoint.
     */
    private String region;

    /**
     * Custom endpoint URL for S3-compatible stores (MinIO, Ceph, LocalStack).
     * When set, path-style access is automatically enabled.
     * Example: http://localhost:4566 (LocalStack), http://minio:9000
     */
    private String endpoint;

    /**
     * AWS Access Key ID.
     * If null, falls back to DefaultCredentialsProvider (IAM role / IRSA).
     * Supports secret:// resolution via the secret-manager library.
     */
    private String accessKey;

    /**
     * AWS Secret Access Key.
     * If null, falls back to DefaultCredentialsProvider.
     * Supports secret:// resolution via the secret-manager library.
     */
    private String secretKey;

    /**
     * IAM Role ARN for AssumeRole.
     * Optional — used when the application needs to assume a specific role
     * instead of using its own credentials directly.
     * Example: arn:aws:iam::123456789:role/S3ReadWriteRole
     */
    private String roleArn;
}
