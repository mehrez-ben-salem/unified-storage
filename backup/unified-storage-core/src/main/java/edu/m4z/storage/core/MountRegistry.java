package edu.m4z.storage.core;

import edu.m4z.storage.core.fs.StorageBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of mounts and backends.
 * Resolves a local path (e.g. /data/reports/Q4.pdf) to the matching
 * mount + backend. Longest prefix match.
 */
public class MountRegistry {

    private static final Logger log = LoggerFactory.getLogger(MountRegistry.class);

    private final Map<String, StorageBackend> backends = new ConcurrentHashMap<>();
    private final List<MountPoint> mounts = new ArrayList<>();

    public void registerBackend(StorageBackend backend) {
        backends.put(backend.scheme(), backend);
    }

    public void addMount(MountPoint mount) {
        StorageBackend backend = backends.get(mount.scheme());
        if (backend == null) throw new IllegalStateException(
                "No backend for scheme '" + mount.scheme() + "'. Add dependency unified-storage-" + mount.scheme());
        backend.initBucket(mount.bucket(), mount.toConfigMap());
        mounts.add(mount);
        // Keep sorted by path length descending (longest prefix match first)
        mounts.sort(Comparator.comparingInt((MountPoint m) -> m.mountPath().length()).reversed());
        log.info("Mount: {} → {}://{}{}", mount.mountPath(), mount.scheme(), mount.bucket(),
                mount.normalizedPrefix().isEmpty() ? "" : " (prefix=" + mount.normalizedPrefix() + ")");
    }

    /**
     * Find the mount matching this absolute path. Longest prefix match.
     */
    public MountPoint resolve(String absolutePath) {
        for (MountPoint m : mounts) {
            String mp = m.mountPath();
            if (absolutePath.equals(mp) || absolutePath.startsWith(mp + "/")) return m;
        }
        return null;
    }

    /**
     * Get backend for a scheme
     */
    public StorageBackend backendFor(String scheme) {
        return backends.get(scheme);
    }

    /**
     * Strip mount prefix and compute the storage key
     */
    public String toKey(MountPoint mount, String absolutePath) {
        String rel = absolutePath.substring(mount.mountPath().length());
        if (rel.startsWith("/")) rel = rel.substring(1);
        return mount.toKey(rel);
    }

    public boolean hasMounts() {
        return !mounts.isEmpty();
    }
}
