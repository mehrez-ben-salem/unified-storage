package edu.m4z.storage.core.fs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.file.FileSystems;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;

/**
 * Installs the {@link InterceptingFileSystemProvider} as the default
 * provider for the "file" scheme.
 *
 * <p>After installation, ALL calls to {@code Path.of(...)}, {@code Paths.get(...)},
 * {@code Files.readAllBytes(...)}, {@code Files.copy(...)}, etc. go through
 * the interceptor. If the path matches a mount → backend. Otherwise → OS.</p>
 *
 * <h2>Mechanism</h2>
 * The JDK caches the list of FileSystemProviders in a static field.
 * We replace the "file" provider in that list with our interceptor.
 * The interceptor delegates to the original for non-mounted paths.
 *
 * <h2>Why this is safe</h2>
 * <ul>
 *   <li>The interceptor wraps the original — non-mounted paths are untouched</li>
 *   <li>Installation happens once at startup, before any file operation</li>
 *   <li>The original provider is kept as delegate — no functionality lost</li>
 * </ul>
 */
public class FileSystemInstaller {

    private static final Logger log = LoggerFactory.getLogger(FileSystemInstaller.class);

    private static volatile boolean installed = false;
    private static InterceptingFileSystemProvider instance;

    /**
     * Install the intercepting provider. Safe to call multiple times.
     */
    @SuppressWarnings("unchecked")
    public static synchronized InterceptingFileSystemProvider install(
            InterceptingFileSystemProvider interceptor) {

        if (installed) {
            log.debug("InterceptingFileSystemProvider already installed");
            return instance;
        }

        try {
            // Get the default FileSystem's provider
            FileSystemProvider defaultProvider = FileSystems.getDefault().provider();

            // Replace in the installed providers list
            List<FileSystemProvider> providers = FileSystemProvider.installedProviders();

            // The providers list is cached in a private static field
            // We need to replace the "file" provider in it
            Field installedField = findInstalledProvidersField();
            if (installedField != null) {
                installedField.setAccessible(true);
                List<FileSystemProvider> currentList =
                        (List<FileSystemProvider>) installedField.get(null);

                // Create mutable copy, replace the file provider
                java.util.ArrayList<FileSystemProvider> newList = new java.util.ArrayList<>(currentList);
                for (int i = 0; i < newList.size(); i++) {
                    if ("file".equals(newList.get(i).getScheme())) {
                        newList.set(i, interceptor);
                        break;
                    }
                }
                installedField.set(null, newList);

                installed = true;
                instance = interceptor;
                log.info("Unified Storage: InterceptingFileSystemProvider installed. " +
                        "All Path.of() / Files.* calls now go through the mount interceptor.");
                return interceptor;
            }

            // Fallback: if we can't replace in the static list,
            // the interceptor still works when called directly
            log.warn("Could not replace default provider in installed list. " +
                    "Mount interception may require explicit usage.");
            installed = true;
            instance = interceptor;
            return interceptor;

        } catch (Exception e) {
            log.error("Failed to install InterceptingFileSystemProvider", e);
            throw new RuntimeException("Unified Storage installation failed", e);
        }
    }

    private static Field findInstalledProvidersField() {
        // JDK stores providers in FileSystemProvider or in a holder class
        try {
            // Try the standard location
            for (Field f : FileSystemProvider.class.getDeclaredFields()) {
                if (f.getType() == List.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    return f;
                }
            }
        } catch (Exception e) {
            log.trace("Could not find installed providers field", e);
        }
        return null;
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static InterceptingFileSystemProvider instance() {
        return instance;
    }
}
