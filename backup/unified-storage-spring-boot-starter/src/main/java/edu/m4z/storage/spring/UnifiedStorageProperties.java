package edu.m4z.storage.spring;

import edu.m4z.storage.core.MountPoint;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * <pre>{@code
 * # application.yml
 * unified:
 *   storage:
 *     mounts:
 *       - path: /data/reports
 *         uri: s3://reports-bucket
 *         region: eu-west-1
 *         access-key: ${S3_ACCESS_KEY}
 *         secret-key: ${S3_SECRET_KEY}
 * }</pre>
 */
@ConfigurationProperties(prefix = "unified.storage")
public class UnifiedStorageProperties {
    private List<MountPoint> mounts = new ArrayList<>();

    public List<MountPoint> getMounts() {
        return mounts;
    }

    public void setMounts(List<MountPoint> mounts) {
        this.mounts = mounts;
    }
}
