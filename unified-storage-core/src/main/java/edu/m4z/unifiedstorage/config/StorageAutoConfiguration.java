package edu.m4z.unifiedstorage.config;

import edu.m4z.unifiedstorage.core.CrossProviderCopyHandler;
import edu.m4z.unifiedstorage.core.StorageRegistry;
import edu.m4z.unifiedstorage.posix.PosixMetadataRepository;
import edu.m4z.unifiedstorage.posix.PosixMetadataService;
import edu.m4z.unifiedstorage.spi.UnifiedFileSystemProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Spring Boot auto-configuration for storage-core.
 *
 * secret:// values in application.yml are resolved upstream by the secret-manager library.
 * StorageProperties receives the final values directly — no resolution logic here.
 */
@AutoConfiguration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {

    /**
     * POSIX metadata service — single gatekeeper for all POSIX operations.
     *
     * The repository is injected with required=false so that applications
     * with posix.enabled=false do not need a JPA datasource at all.
     * PosixMetadataService handles the null-repository case internally.
     */
    @Bean
    @ConditionalOnMissingBean(PosixMetadataService.class)
    public PosixMetadataService posixMetadataService(
            StorageProperties properties,
            @Autowired(required = false) PosixMetadataRepository repository) {
        return new PosixMetadataService(properties.getPosix().isEnabled(), repository);
    }

    @Bean
    @ConditionalOnMissingBean(CrossProviderCopyHandler.class)
    public CrossProviderCopyHandler crossProviderCopyHandler(StorageProperties properties) {
        return new CrossProviderCopyHandler(properties);
    }

    /**
     * Central mount registry.
     * The provider list is auto-injected by Spring:
     * all UnifiedFileSystemProvider beans present in the context
     * (S3FileSystemProvider, GcsFileSystemProvider) are collected here.
     */
    @Bean
    @ConditionalOnMissingBean(StorageRegistry.class)
    public StorageRegistry storageRegistry(StorageProperties properties,
                                            List<UnifiedFileSystemProvider> providers,
                                            CrossProviderCopyHandler crossCopyHandler) {
        return new StorageRegistry(properties, providers, crossCopyHandler);
    }
}
