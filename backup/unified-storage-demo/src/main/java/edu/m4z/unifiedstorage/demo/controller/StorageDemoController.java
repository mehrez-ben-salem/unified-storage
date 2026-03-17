package edu.m4z.unifiedstorage.demo.controller;

import edu.m4z.unifiedstorage.demo.service.StorageService;
import edu.m4z.unifiedstorage.demo.service.StorageService.FileMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST controller — demo of the 5 UnifiedStorage scenarios.
 *
 * Base URL: /api/storage
 *
 * Scenario 1 — S3
 *   POST   /api/storage/s3/{filename}              upload file to S3
 *   GET    /api/storage/s3/{filename}              download from S3
 *
 * Scenario 2 — GCS
 *   POST   /api/storage/gcs/{filename}             upload file to GCS
 *   GET    /api/storage/gcs/{filename}             download from GCS
 *
 * Scenario 3 — Cross-provider copy
 *   POST   /api/storage/copy/s3-to-gcs/{filename} streaming copy S3 → GCS
 *
 * Scenario 4 — Directory listing
 *   GET    /api/storage/s3                         list S3 directory
 *   GET    /api/storage/gcs                        list GCS directory
 *   GET    /api/storage/local                      list local directory
 *
 * Scenario 5 — Local (file://)
 *   POST   /api/storage/local/{filename}           write local file
 *   GET    /api/storage/local/{filename}           read local file
 *
 * POSIX metadata
 *   GET    /api/storage/meta/s3/{filename}         POSIX attributes for S3 file
 *   GET    /api/storage/meta/gcs/{filename}        POSIX attributes for GCS file
 */
@RestController
@RequestMapping("/api/storage")
public class StorageDemoController {

    private final StorageService storageService;

    @Value("${unified-storage.reports.path}")
    private String reportsPath;

    @Value("${unified-storage.archives.path}")
    private String archivesPath;

    public StorageDemoController(StorageService storageService) {
        this.storageService = storageService;
    }

    // ================================================================
    // Scenario 1 — S3
    // ================================================================

    /**
     * Uploads a file to S3.
     * Multipart upload is triggered automatically if the file exceeds chunk-size.
     *
     * curl -X POST http://localhost:8080/api/storage/s3/report.csv \
     *      -F "file=@report.csv"
     */
    @PostMapping(value = "/s3/{filename}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadToS3(
            @PathVariable String filename,
            @RequestParam("file") MultipartFile file) throws IOException {

        byte[] content = file.getBytes();
        storageService.writeToS3(filename, content);

        return ResponseEntity.ok(Map.of(
                "status",   "uploaded",
                "provider", "S3",
                "filename", filename,
                "size",     content.length,
                "path",     reportsPath + "/" + filename
        ));
    }

    /**
     * Downloads a file from S3.
     *
     * curl http://localhost:8080/api/storage/s3/report.csv --output report.csv
     */
    @GetMapping("/s3/{filename}")
    public ResponseEntity<byte[]> downloadFromS3(@PathVariable String filename) throws IOException {
        byte[] content = storageService.readFromS3(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(content);
    }

    // ================================================================
    // Scenario 2 — GCS
    // ================================================================

    /**
     * Uploads a file to GCS via automatic Resumable Upload.
     *
     * curl -X POST http://localhost:8080/api/storage/gcs/archive.csv \
     *      -F "file=@archive.csv"
     */
    @PostMapping(value = "/gcs/{filename}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadToGcs(
            @PathVariable String filename,
            @RequestParam("file") MultipartFile file) throws IOException {

        byte[] content = file.getBytes();
        storageService.writeToGcs(filename, content);

        return ResponseEntity.ok(Map.of(
                "status",   "uploaded",
                "provider", "GCS",
                "filename", filename,
                "size",     content.length,
                "path",     archivesPath + "/" + filename
        ));
    }

    /**
     * Downloads a file from GCS.
     *
     * curl http://localhost:8080/api/storage/gcs/archive.csv --output archive.csv
     */
    @GetMapping("/gcs/{filename}")
    public ResponseEntity<byte[]> downloadFromGcs(@PathVariable String filename) throws IOException {
        byte[] content = storageService.readFromGcs(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(content);
    }

    // ================================================================
    // Scenario 3 — Cross-provider copy S3 → GCS
    // ================================================================

    /**
     * Copies a file from S3 to GCS — direct streaming, zero temporary files.
     * 100 GB compatible: only one chunk (chunk-size) is in memory at a time.
     *
     * curl -X POST http://localhost:8080/api/storage/copy/s3-to-gcs/report.csv
     */
    @PostMapping("/copy/s3-to-gcs/{filename}")
    public ResponseEntity<Map<String, Object>> copyS3ToGcs(
            @PathVariable String filename) throws IOException {

        long start = System.currentTimeMillis();
        storageService.copyS3ToGcs(filename);
        long elapsed = System.currentTimeMillis() - start;

        return ResponseEntity.ok(Map.of(
                "status",      "copied",
                "filename",    filename,
                "source",      "S3 → " + reportsPath,
                "destination", "GCS → " + archivesPath,
                "elapsedMs",   elapsed
        ));
    }

    // ================================================================
    // Scenario 4 — Directory listing
    // ================================================================

    /**
     * Lists the configured S3 directory.
     *
     * curl http://localhost:8080/api/storage/s3
     */
    @GetMapping("/s3")
    public ResponseEntity<Map<String, Object>> listS3() throws IOException {
        List<String> files = storageService.listS3();
        return ResponseEntity.ok(Map.of(
                "provider", "S3",
                "path",     reportsPath,
                "count",    files.size(),
                "files",    files
        ));
    }

    /**
     * Lists the configured GCS directory.
     *
     * curl http://localhost:8080/api/storage/gcs
     */
    @GetMapping("/gcs")
    public ResponseEntity<Map<String, Object>> listGcs() throws IOException {
        List<String> files = storageService.listGcs();
        return ResponseEntity.ok(Map.of(
                "provider", "GCS",
                "path",     archivesPath,
                "count",    files.size(),
                "files",    files
        ));
    }

    /**
     * Lists the local directory.
     *
     * curl http://localhost:8080/api/storage/local
     */
    @GetMapping("/local")
    public ResponseEntity<Map<String, Object>> listLocal() throws IOException {
        List<String> files = storageService.listLocal();
        return ResponseEntity.ok(Map.of(
                "provider", "local",
                "count",    files.size(),
                "files",    files
        ));
    }

    // ================================================================
    // Scenario 5 — Local (file://)
    // ================================================================

    /**
     * Writes a local file — native JDK provider, same API as S3/GCS.
     *
     * curl -X POST http://localhost:8080/api/storage/local/test.txt \
     *      -F "file=@test.txt"
     */
    @PostMapping(value = "/local/{filename}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> writeLocal(
            @PathVariable String filename,
            @RequestParam("file") MultipartFile file) throws IOException {

        byte[] content = file.getBytes();
        storageService.writeLocal(filename, content);

        return ResponseEntity.ok(Map.of(
                "status",   "written",
                "provider", "local",
                "filename", filename,
                "size",     content.length
        ));
    }

    /**
     * Reads a local file.
     *
     * curl http://localhost:8080/api/storage/local/test.txt
     */
    @GetMapping("/local/{filename}")
    public ResponseEntity<byte[]> readLocal(@PathVariable String filename) throws IOException {
        byte[] content = storageService.readLocal(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(content);
    }

    // ================================================================
    // POSIX Metadata
    // ================================================================

    /**
     * Returns POSIX metadata for an S3 file.
     *
     * curl http://localhost:8080/api/storage/meta/s3/report.csv
     */
    @GetMapping("/meta/s3/{filename}")
    public ResponseEntity<FileMetadata> metaS3(@PathVariable String filename) throws IOException {
        return ResponseEntity.ok(storageService.getMetadata(reportsPath, filename));
    }

    /**
     * Returns POSIX metadata for a GCS file.
     *
     * curl http://localhost:8080/api/storage/meta/gcs/archive.csv
     */
    @GetMapping("/meta/gcs/{filename}")
    public ResponseEntity<FileMetadata> metaGcs(@PathVariable String filename) throws IOException {
        return ResponseEntity.ok(storageService.getMetadata(archivesPath, filename));
    }

    // ================================================================
    // Error handlers
    // ================================================================

    @ExceptionHandler(java.nio.file.NoSuchFileException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(java.nio.file.NoSuchFileException e) {
        return ResponseEntity.status(404).body(Map.of(
                "error",   "File not found",
                "details", e.getMessage()
        ));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> handleIoError(IOException e) {
        return ResponseEntity.status(500).body(Map.of(
                "error",   "Storage error",
                "details", e.getMessage()
        ));
    }
}
