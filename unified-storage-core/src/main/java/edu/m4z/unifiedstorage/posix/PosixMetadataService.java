package edu.m4z.unifiedstorage.posix;

import lombok.extern.slf4j.Slf4j;


import java.util.Optional;

/**
 * Gatekeeper for all POSIX metadata operations.
 *
 * All providers and output streams call this service instead of
 * interacting with {@link PosixMetadataRepository} directly.
 * This ensures the {@code unified-storage.posix.enabled} flag is
 * the single point of control for the entire feature.
 *
 * When disabled:
 * - No database interaction occurs.
 * - {@link #find(String)} always returns {@code Optional.empty()}.
 * - Write, delete, update-size and attribute operations are silent no-ops.
 * - JPA datasource configuration becomes fully optional.
 *
 * When enabled:
 * - All operations delegate to the repository.
 * - Repository failures are caught and logged as warnings so that a
 *   metadata issue never breaks a storage operation.
 *
 * Wired as a Spring bean by {@link edu.m4z.unifiedstorage.config.StorageAutoConfiguration}.
 */
@Slf4j
public class PosixMetadataService {

    private final boolean                  enabled;
    private final PosixMetadataRepository  repository;

    /**
     * @param enabled    value of {@code unified-storage.posix.enabled}
     * @param repository JPA repository — may be {@code null} when disabled
     *                   (no datasource required in that case)
     */
    public PosixMetadataService(boolean enabled, PosixMetadataRepository repository) {
        this.enabled    = enabled;
        this.repository = repository;
        if (enabled && repository == null) {
            log.warn("POSIX metadata is enabled but PosixMetadataRepository is not available. "
                   + "Check that a JPA datasource is configured, or set unified-storage.posix.enabled=false.");
        }
        log.info("POSIX metadata management: {}", enabled ? "ENABLED" : "DISABLED");
    }

    /** Returns {@code true} if POSIX metadata management is active. */
    public boolean isEnabled() {
        return enabled && repository != null;
    }

    /**
     * Looks up existing POSIX metadata for the given virtual path.
     *
     * @param virtualPath full URI of the file (e.g. "s3://bucket/path/file.csv")
     * @return metadata if found and POSIX is enabled, empty otherwise
     */
    public Optional<PosixMetadata> find(String virtualPath) {
        if (!isEnabled()) return Optional.empty();
        try {
            return repository.findByVirtualPath(virtualPath);
        } catch (Exception e) {
            log.warn("Failed to read POSIX metadata for {}: {}", virtualPath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Saves or updates POSIX metadata after a file write.
     * Updates size if a record already exists; creates a new file record otherwise.
     *
     * @param virtualPath  full URI of the written file
     * @param writtenBytes total bytes written
     */
    public void saveOrUpdateFile(String virtualPath, long writtenBytes) {
        if (!isEnabled()) return;
        try {
            repository.findByVirtualPath(virtualPath).ifPresentOrElse(
                    meta -> {
                        meta.setSize(writtenBytes);
                        repository.save(meta);
                    },
                    () -> repository.save(PosixMetadata.forFile(virtualPath, writtenBytes))
            );
        } catch (Exception e) {
            log.warn("Failed to save POSIX metadata for {}: {}", virtualPath, e.getMessage());
        }
    }

    /**
     * Creates a directory record in POSIX metadata.
     *
     * @param virtualPath full URI of the directory
     */
    public void saveDirectory(String virtualPath) {
        if (!isEnabled()) return;
        try {
            repository.save(PosixMetadata.forDirectory(virtualPath));
        } catch (Exception e) {
            log.warn("Failed to save POSIX directory metadata for {}: {}", virtualPath, e.getMessage());
        }
    }

    /**
     * Deletes the POSIX metadata record for a single file or directory.
     *
     * @param virtualPath full URI to delete
     */
    public void delete(String virtualPath) {
        if (!isEnabled()) return;
        try {
            repository.deleteByVirtualPath(virtualPath);
        } catch (Exception e) {
            log.warn("Failed to delete POSIX metadata for {}: {}", virtualPath, e.getMessage());
        }
    }

    /**
     * Sets a POSIX attribute (owner, group, permissions) on an existing record.
     * Silently ignored if no record exists or POSIX is disabled.
     *
     * @param virtualPath full URI of the file
     * @param attribute   attribute name: "posix:owner", "posix:group", "posix:permissions"
     * @param value       attribute value (String for owner/group, Integer for permissions)
     */
    public void setAttribute(String virtualPath, String attribute, Object value) {
        if (!isEnabled()) return;
        try {
            repository.findByVirtualPath(virtualPath).ifPresent(meta -> {
                switch (attribute) {
                    case "posix:owner"       -> meta.setOwner(value.toString());
                    case "posix:group"       -> meta.setGroup(value.toString());
                    case "posix:permissions" -> meta.setPermissions((Integer) value);
                    default -> log.debug("Unknown POSIX attribute '{}' — ignored", attribute);
                }
                repository.save(meta);
            });
        } catch (Exception e) {
            log.warn("Failed to set POSIX attribute '{}' on {}: {}", attribute, virtualPath, e.getMessage());
        }
    }
}
