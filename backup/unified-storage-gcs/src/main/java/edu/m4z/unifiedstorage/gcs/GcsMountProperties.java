package edu.m4z.unifiedstorage.gcs;

import edu.m4z.unifiedstorage.config.StorageProperties.MountPointProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * GCS-specific mount point configuration.
 *
 * Extends the provider-agnostic {@link MountPointProperties} with all
 * attributes specific to Google Cloud Storage.
 *
 * Example YAML:
 * <pre>
 * unified-storage:
 *   archives:
 *     path: gs://my-bucket/data/archives
 *     project-id: my-gcp-project
 *     credentials-json: secret://cyberark/gcs/sa-json
 *
 *   # Using Application Default Credentials (Workload Identity / GCE metadata)
 *   logs:
 *     path: gs://logs-bucket/app
 *     project-id: my-gcp-project
 *     # credentials-json omitted → ADC used automatically
 * </pre>
 *
 * If credentials-json is omitted, the provider falls back to
 * Application Default Credentials (Workload Identity, GCE metadata server,
 * GOOGLE_APPLICATION_CREDENTIALS environment variable).
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class GcsMountProperties extends MountPointProperties {

    /**
     * GCP Service Account credentials in JSON format.
     * Accepts either:
     * - An absolute file path: /etc/gcp/sa-key.json
     * - Inline JSON content: { "type": "service_account", ... }
     * - A secret:// reference resolved by the secret-manager library
     *
     * If null, Application Default Credentials (ADC) are used.
     */
    private String credentialsJson;

    /**
     * GCP project ID (e.g. my-project-123).
     * Required when using Service Account credentials.
     * Optional when using ADC on GCE/GKE (inferred from metadata server).
     */
    private String projectId;
}
