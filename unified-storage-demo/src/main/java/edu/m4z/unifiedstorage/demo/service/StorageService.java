package edu.m4z.unifiedstorage.demo.service;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Business service for the UnifiedStorage demo.
 *
 * Uses only the standard java.nio.file API (Files, Path).
 * No knowledge of S3, GCS, or any provider — 100% transparent.
 *
 * Paths are injected from application.yml:
 *   unified-storage.reports.path, unified-storage.archives.path, unified-storage.temp.path
 *
 * Note: @Data is not used here because Spring's @Value field injection requires
 * the fields to remain non-final; Lombok @Data would conflict with @Value semantics.
 */
@Slf4j
@Service
public class StorageService {

    @Value("${unified-storage.reports.path}")
    private String reportsPath;

    @Value("${unified-storage.archives.path}")
    private String archivesPath;

    @Value("${unified-storage.temp.path}")
    private String tempPath;

    // ----------------------------------------------------------------
    // Scenario 1 — S3 write and read
    // ----------------------------------------------------------------

    /**
     * Writes a file to S3.
     * If content exceeds chunk-size, multipart upload is triggered automatically.
     */
    public void writeToS3(String filename, byte[] content) throws IOException {
        Path path = resolvePath(reportsPath, filename);
        Files.createDirectories(path.getParent());
        Files.write(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("File written to S3: {} ({} bytes)", path, content.length);
    }

    /** Reads a file from S3. */
    public byte[] readFromS3(String filename) throws IOException {
        Path path = resolvePath(reportsPath, filename);
        byte[] content = Files.readAllBytes(path);
        log.info("File read from S3: {} ({} bytes)", path, content.length);
        return content;
    }

    // ----------------------------------------------------------------
    // Scenario 2 — GCS write and read
    // ----------------------------------------------------------------

    /**
     * Writes a file to GCS via automatic Resumable Upload.
     */
    public void writeToGcs(String filename, byte[] content) throws IOException {
        Path path = resolvePath(archivesPath, filename);
        Files.createDirectories(path.getParent());
        Files.write(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("File written to GCS: {} ({} bytes)", path, content.length);
    }

    /** Reads a file from GCS. */
    public byte[] readFromGcs(String filename) throws IOException {
        Path path = resolvePath(archivesPath, filename);
        byte[] content = Files.readAllBytes(path);
        log.info("File read from GCS: {} ({} bytes)", path, content.length);
        return content;
    }

    // ----------------------------------------------------------------
    // Scenario 3 — Cross-provider copy S3 → GCS
    // ----------------------------------------------------------------

    /**
     * Copies a file from S3 to GCS.
     *
     * Files.copy() detects the different providers → CrossProviderCopyHandler
     * → streaming pipe by chunks → GCS resumable multipart on the destination.
     * Zero temporary files. Compatible with 100 GB files.
     */
    public void copyS3ToGcs(String filename) throws IOException {
        Path source = resolvePath(reportsPath,  filename);
        Path target = resolvePath(archivesPath, filename);

        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

        log.info("Cross-provider copy S3→GCS: {} → {}", source, target);
    }

    // ----------------------------------------------------------------
    // Scenario 4 — Directory listing
    // ----------------------------------------------------------------

    /** Lists files in the configured S3 directory. */
    public List<String> listS3() throws IOException {
        Path dir = Path.of(URI.create(reportsPath));
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            List<String> entries = new ArrayList<>();
            for (Path p : stream) entries.add(p.getFileName().toString());
            log.info("S3 listing '{}': {} entries", reportsPath, entries.size());
            return entries;
        }
    }

    /** Lists files in the configured GCS directory. */
    public List<String> listGcs() throws IOException {
        Path dir = Path.of(URI.create(archivesPath));
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            List<String> entries = new ArrayList<>();
            for (Path p : stream) entries.add(p.getFileName().toString());
            log.info("GCS listing '{}': {} entries", archivesPath, entries.size());
            return entries;
        }
    }

    // ----------------------------------------------------------------
    // Scenario 5 — Local file (file://)
    // ----------------------------------------------------------------

    /**
     * Writes a local file (file://) — native JDK provider, no provider-specific code.
     */
    public void writeLocal(String filename, byte[] content) throws IOException {
        Path path = resolvePath(tempPath, filename);
        Files.createDirectories(path.getParent());
        Files.write(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Local file written: {} ({} bytes)", path, content.length);
    }

    /** Reads a local file. */
    public byte[] readLocal(String filename) throws IOException {
        Path path = resolvePath(tempPath, filename);
        byte[] content = Files.readAllBytes(path);
        log.info("Local file read: {} ({} bytes)", path, content.length);
        return content;
    }

    /** Lists local files. Creates the directory if it does not exist. */
    public List<String> listLocal() throws IOException {
        Path dir = Path.of(URI.create(tempPath));
        Files.createDirectories(dir);
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).toList();
        }
    }

    /** Returns POSIX metadata for a file. */
    public FileMetadata getMetadata(String mountPath, String filename) throws IOException {
        Path path = resolvePath(mountPath, filename);
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        return new FileMetadata(
                path.toString(),
                attrs.size(),
                attrs.creationTime().toString(),
                attrs.lastModifiedTime().toString(),
                attrs.isRegularFile(),
                attrs.isDirectory()
        );
    }

    // ----------------------------------------------------------------
    // Utility
    // ----------------------------------------------------------------

    private Path resolvePath(String basePath, String filename) {
        String full = basePath.endsWith("/") ? basePath + filename : basePath + "/" + filename;
        return Path.of(URI.create(full));
    }

    // ----------------------------------------------------------------
    // DTO
    // ----------------------------------------------------------------

    public record FileMetadata(
            String  path,
            long    size,
            String  createdAt,
            String  modifiedAt,
            boolean regularFile,
            boolean directory
    ) {}
}
