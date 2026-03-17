package edu.m4z.unifiedstorage.core;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Bridge between the Java SPI mechanism (ServiceLoader) and the Spring context.
 *
 * PROBLEM:
 * Java SPI instantiates FileSystemProvider instances via their no-arg constructor,
 * outside the Spring context. @Autowired injection therefore does not work
 * in providers created by the JDK ServiceLoader.
 *
 * SOLUTION:
 * S3 and GCS providers are declared as Spring beans (@Component).
 * The JDK ServiceLoader creates a "shadow" instance via the no-arg constructor,
 * but this instance immediately delegates to the real Spring bean via this static holder.
 *
 * This component stores the Spring context in a static field accessible
 * from any instance (including those created by ServiceLoader).
 *
 * StorageRegistry calls Path.of(URI) → JDK → ServiceLoader → shadow provider
 * → delegation to the real Spring bean via ApplicationContextHolder.
 */
@Component
public class SpringAwareFileSystemProviderBridge implements ApplicationContextAware {

    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        SpringAwareFileSystemProviderBridge.applicationContext = ctx;
    }

    public static ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    public static <T> T getBean(Class<T> beanClass) {
        if (applicationContext == null) {
            throw new IllegalStateException(
                "ApplicationContext not initialized. " +
                "Verify that SpringAwareFileSystemProviderBridge is an active Spring bean.");
        }
        return applicationContext.getBean(beanClass);
    }

    public static boolean isReady() {
        return applicationContext != null;
    }
}
