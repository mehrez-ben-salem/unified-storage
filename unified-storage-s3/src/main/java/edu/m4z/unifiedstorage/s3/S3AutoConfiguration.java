package edu.m4z.unifiedstorage.s3;

import edu.m4z.unifiedstorage.config.StorageProperties;
import edu.m4z.unifiedstorage.posix.PosixMetadataRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/**
 * Auto-configuration for the S3 provider.
 * Activated only if the S3 SDK is on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(S3AsyncClient.class)
public class S3AutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(S3FileSystemProvider.class)
    public S3FileSystemProvider s3FileSystemProvider(StorageProperties storageProperties) {
        S3FileSystemProvider provider = new S3FileSystemProvider();
        provider.setDefaultChunkSize(storageProperties.getChunkSize());
        return provider;
    }
}
