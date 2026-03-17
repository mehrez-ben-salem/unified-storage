package edu.m4z.unifiedstorage.gcs;

import com.google.cloud.storage.Storage;
import edu.m4z.unifiedstorage.config.StorageProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the GCS provider.
 * Activated only if the Google Cloud Storage SDK is on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(Storage.class)
public class GcsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(GcsFileSystemProvider.class)
    public GcsFileSystemProvider gcsFileSystemProvider(StorageProperties storageProperties) {
        GcsFileSystemProvider provider = new GcsFileSystemProvider();
        provider.setDefaultChunkSize(storageProperties.getChunkSize());
        return provider;
    }
}
