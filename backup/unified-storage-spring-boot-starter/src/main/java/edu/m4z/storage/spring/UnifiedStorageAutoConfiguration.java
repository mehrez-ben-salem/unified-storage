package edu.m4z.storage.spring;

import edu.m4z.storage.core.MountPoint;
import edu.m4z.storage.core.MountRegistry;
import edu.m4z.storage.core.fs.FileSystemInstaller;
import edu.m4z.storage.core.fs.InterceptingFileSystemProvider;
import edu.m4z.storage.core.fs.StorageBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.FileSystems;
import java.util.ServiceLoader;

/**
 * At startup:
 * <ol>
 *   <li>Discovers {@link StorageBackend}s via SPI (S3, GCS, Azure, NFS)</li>
 *   <li>Reads mounts from application.yml</li>
 *   <li>Installs {@link InterceptingFileSystemProvider} as the default provider</li>
 * </ol>
 * <p>
 * After this, ALL calls to {@code Path.of(...)}, {@code Files.readAllBytes(...)},
 * {@code Files.copy(...)} are intercepted. Mounted paths → cloud backends.
 * Non-mounted paths → normal OS filesystem.
 *
 * <b>The developer code is 100% unchanged. No import, no bean, no annotation.</b>
 */
@AutoConfiguration
@EnableConfigurationProperties(UnifiedStorageProperties.class)
public class UnifiedStorageAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(UnifiedStorageAutoConfiguration.class);

    @Bean
    public MountRegistry unifiedStorageMountRegistry(UnifiedStorageProperties properties) {
        MountRegistry registry = new MountRegistry();

        // Discover backends via SPI
        int count = 0;
        for (StorageBackend backend : ServiceLoader.load(StorageBackend.class)) {
            registry.registerBackend(backend);
            count++;
        }
        if (count == 0) {
            log.warn("No StorageBackend on classpath. Add unified-storage-s3/gcs/azure/nfs.");
            return registry;
        }

        // Register mounts from application.yml
        if (properties.getMounts() == null || properties.getMounts().isEmpty()) {
            log.info("No unified.storage.mounts configured.");
            return registry;
        }

        for (MountPoint mount : properties.getMounts()) {
            registry.addMount(mount);
        }

        // Install the intercepting provider
        InterceptingFileSystemProvider interceptor = new InterceptingFileSystemProvider(
                FileSystems.getDefault().provider(), registry);
        FileSystemInstaller.install(interceptor);

        log.info("Unified Storage: {} backends, {} mounts. " +
                        "Path.of() / Files.* now intercepted for mounted paths.",
                count, properties.getMounts().size());

        return registry;
    }
}
