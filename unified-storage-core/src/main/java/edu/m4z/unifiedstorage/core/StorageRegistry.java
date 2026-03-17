package edu.m4z.unifiedstorage.core;

import lombok.extern.slf4j.Slf4j;

import edu.m4z.unifiedstorage.config.StorageProperties;
import edu.m4z.unifiedstorage.config.StorageProperties.MountPointProperties;
import edu.m4z.unifiedstorage.spi.UnifiedFileSystemProvider;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central mount point registry.
 *
 * Responsibilities:
 * - Register providers per mount at startup
 * - Resolve the correct provider from a URI
 * - Route cross-provider copies
 *
 * The same provider can be registered multiple times (e.g. two S3 buckets).
 * Multiple different providers can coexist (S3 + GCS + Azure Blob).
 */
@Slf4j
@Component
public class StorageRegistry {

    private final StorageProperties properties;
    private final List<UnifiedFileSystemProvider> availableProviders;
    private final CrossProviderCopyHandler crossCopyHandler;

    /** Map: mount-name → instantiated and configured provider */
    private final Map<String, UnifiedFileSystemProvider> mountRegistry = new ConcurrentHashMap<>();

    /** Map: mount-name → resolved base URI */
    private final Map<String, URI> mountBaseUris = new ConcurrentHashMap<>();

    public StorageRegistry(StorageProperties properties,
                           List<UnifiedFileSystemProvider> availableProviders,
                           CrossProviderCopyHandler crossCopyHandler) {
        this.properties = properties;
        this.availableProviders = availableProviders;
        this.crossCopyHandler = crossCopyHandler;
    }

    @PostConstruct
    public void initialize() {
        log.info("Initializing StorageRegistry — {} mount point(s) detected",
                properties.getMounts().size());

        properties.getMounts().forEach((mountName, mountProps) -> {
            // secret:// values are already resolved by the secret-manager library
            // before StorageProperties is injected — no processing needed here.
            URI baseUri = URI.create(mountProps.getPath());
            String scheme = baseUri.getScheme().toLowerCase();

            UnifiedFileSystemProvider provider = selectProvider(scheme, mountName);
            registerFileSystem(provider, baseUri, mountProps);

            if (provider != null) {
                provider.registerMount(mountName, mountProps);
            }
            mountRegistry.put(mountName, provider);
            mountBaseUris.put(mountName, baseUri);

            log.info("Mount '{}' → {} (scheme: {})", mountName, mountProps.getPath(), scheme);
        });
    }

    /**
     * Resolves a Path from a logical mount name and a relative path.
     *
     * @param mountName    logical name (e.g. "reports")
     * @param relativePath path relative to the mount (e.g. "2024/report.csv")
     * @return NIO Path ready to use
     */
    public Path resolvePath(String mountName, String relativePath) {
        URI baseUri = mountBaseUris.get(mountName);
        if (baseUri == null) {
            throw new IllegalArgumentException("Unknown mount: " + mountName +
                    ". Available mounts: " + mountBaseUris.keySet());
        }
        String fullPath = baseUri.toString();
        if (!relativePath.isEmpty()) {
            fullPath = fullPath.endsWith("/") ? fullPath + relativePath : fullPath + "/" + relativePath;
        }
        return Path.of(URI.create(fullPath));
    }

    /**
     * Returns the provider associated with a given URI scheme.
     * Used by CrossProviderCopyHandler.
     */
    public UnifiedFileSystemProvider providerForUri(URI uri) {
        String scheme = uri.getScheme().toLowerCase();
        return availableProviders.stream()
                .filter(p -> p.supportsScheme(scheme))
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException(
                        "No provider found for scheme: " + scheme));
    }

    public CrossProviderCopyHandler getCrossCopyHandler() {
        return crossCopyHandler;
    }

    public long getChunkSize() {
        return properties.getChunkSize();
    }

    // ----------------------------------------------------------------
    // Private
    // ----------------------------------------------------------------

    private UnifiedFileSystemProvider selectProvider(String scheme, String mountName) {
        // "file" → native JDK provider, not in the custom provider list
        if ("file".equals(scheme)) {
            return null; // handled natively by FileSystems.getDefault()
        }
        return availableProviders.stream()
                .filter(p -> p.supportsScheme(scheme))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No provider available for scheme '" + scheme +
                        "' (mount: " + mountName + "). " +
                        "Verify that unified-storage-s3, unified-storage-gcs, or another provider is on the classpath."));
    }

    private void registerFileSystem(UnifiedFileSystemProvider provider, URI baseUri,
                                     MountPointProperties resolved) {
        if (provider == null) return; // local → native JDK
        try {
            // Check if the FileSystem is already open for this bucket
            FileSystems.getFileSystem(URI.create(baseUri.getScheme() + "://" + baseUri.getHost()));
        } catch (Exception e) {
            // Not yet open → let the provider open it via newFileSystem
            // on first access (lazy initialization in the provider)
        }
    }
}
