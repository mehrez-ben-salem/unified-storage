package edu.m4z.unifiedstorage.posix;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * POSIX metadata stored in the database.
 *
 * Cloud object stores (S3, GCS, Azure Blob) do not natively support POSIX
 * attributes (permissions, owner, group, precise timestamps).
 * This entity emulates them to maintain application compatibility.
 *
 * The lookup key is {@code virtualPath}: the full URI of the file.
 * Example: "s3://my-bucket/data/reports/2024/report.csv"
 *
 * Note: @Data is used instead of manual getters/setters.
 * @EqualsAndHashCode and @ToString are provided by Lombok.
 * The id field uses a generated UUID — Lombok's setter is intentionally
 * not used for it (JPA manages the value via @GeneratedValue).
 */
@Data
@Entity
@Table(
    name = "storage_posix_metadata",
    indexes = {
        @Index(name = "idx_posix_virtual_path", columnList = "virtual_path", unique = true),
        @Index(name = "idx_posix_parent_path",  columnList = "parent_path")
    }
)
public class PosixMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", length = 36)
    private String id;

    /** Full URI of the file or directory. Primary lookup key. */
    @Column(name = "virtual_path", nullable = false, unique = true, length = 2048)
    private String virtualPath;

    /** URI of the parent directory. Used for directory listings. */
    @Column(name = "parent_path", length = 2048)
    private String parentPath;

    /** File or directory name (last component of the path). */
    @Column(name = "name", length = 512)
    private String name;

    @Column(name = "owner", length = 255)
    private String owner;

    @Column(name = "grp", length = 255)
    private String group;

    /**
     * POSIX permissions in octal representation.
     * Example: 0644 → 420 in decimal.
     * Default: rw-r--r-- (0644)
     */
    @Column(name = "permissions", nullable = false)
    private int permissions = 0644;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "modified_at")
    private Instant modifiedAt;

    @Column(name = "accessed_at")
    private Instant accessedAt;

    /** Size in bytes. Updated after each write. */
    @Column(name = "size", nullable = false)
    private long size = 0;

    /** True if this is a virtual directory (S3/GCS prefix). */
    @Column(name = "is_directory", nullable = false)
    private boolean directory = false;

    /** Symlink target, if applicable (limited use on cloud). */
    @Column(name = "link_target", length = 2048)
    private String linkTarget;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt  == null) createdAt  = now;
        if (modifiedAt == null) modifiedAt = now;
        if (accessedAt == null) accessedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        modifiedAt = Instant.now();
    }

    // ----------------------------------------------------------------
    // Factory methods
    // ----------------------------------------------------------------

    public static PosixMetadata forFile(String virtualPath, long size) {
        PosixMetadata meta = new PosixMetadata();
        meta.setVirtualPath(virtualPath);
        meta.setSize(size);
        meta.setDirectory(false);
        meta.setPermissions(0644);
        meta.setParentPath(extractParent(virtualPath));
        meta.setName(extractName(virtualPath));
        return meta;
    }

    public static PosixMetadata forDirectory(String virtualPath) {
        PosixMetadata meta = new PosixMetadata();
        meta.setVirtualPath(virtualPath);
        meta.setDirectory(true);
        meta.setPermissions(0755);
        meta.setParentPath(extractParent(virtualPath));
        meta.setName(extractName(virtualPath));
        return meta;
    }

    private static String extractParent(String virtualPath) {
        // s3://bucket/a/b/c.csv → s3://bucket/a/b
        int lastSlash = virtualPath.lastIndexOf('/');
        return lastSlash > "s3://x".length() ? virtualPath.substring(0, lastSlash) : null;
    }

    private static String extractName(String virtualPath) {
        int lastSlash = virtualPath.lastIndexOf('/');
        return lastSlash >= 0 ? virtualPath.substring(lastSlash + 1) : virtualPath;
    }
}
