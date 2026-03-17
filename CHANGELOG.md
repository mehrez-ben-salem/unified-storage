# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- _New features that are not yet released go here._

### Changed
- _Changes to existing functionality go here._

### Fixed
- _Bug fixes go here._

---

## [1.0.0] - 2026-03-17

### Added

**Core (`unified-storage-core`)**

- `UnifiedFileSystemProvider` — abstract SPI base extending `java.nio.file.spi.FileSystemProvider`, adding `registerMount()`, `newRangeInputStream()`, and `initiateMultipartUpload()` to the JDK contract
- `StorageProperties` — `@ConfigurationProperties` root for `unified-storage.*` with `chunkSize` (default 64 MB) and nested `Posix` configuration block
- `MountPointProperties` — provider-agnostic base class (`path`, `multipartThreshold`, `partSize`)
- `StorageRegistry` — `@PostConstruct` mount initialisation; routes URI schemes to providers; dispatches cross-provider copies
- `CrossProviderCopyHandler` — streaming pipe between any two providers; memory-bounded to one chunk at a time; compatible with files > 100 GB
- `SpringAwareFileSystemProviderBridge` — bridges Java SPI (no-arg constructor instanciation) and Spring Boot (dependency injection) by storing `ApplicationContext` in a static field
- `PosixMetadataService` — single control point for all POSIX DB operations, governed by `unified-storage.posix.enabled`; all methods are silent no-ops when disabled
- `PosixMetadata` — JPA entity for the `storage_posix_metadata` table (virtual path, permissions, owner, group, timestamps, size)
- `PosixMetadataRepository` — Spring Data repository with directory listing, recursive deletion, and size update operations
- `MultipartUploadHandle` — SPI interface for in-progress uploads (`uploadPart`, `complete`, `abort`)
- `StorageAutoConfiguration` — wires `PosixMetadataService`, `CrossProviderCopyHandler`, and `StorageRegistry`

**S3 (`unified-storage-s3`)**

- `S3MountProperties` — `region`, `endpoint`, `accessKey`, `secretKey`, `roleArn`
- `S3FileSystemProvider` — full `FileSystemProvider` for `s3://` URIs (read, write, list, delete, copy, move, attributes)
- `S3SmartOutputStream` — automatic `PutObject` vs. `CreateMultipartUpload` switch based on `multipartThreshold`; one part in memory at a time; calls `abortMultipartUpload` on failure
- `S3MultipartUploadHandle` — explicit multipart handle used by `CrossProviderCopyHandler`
- `S3AutoConfiguration` — `@ConditionalOnClass(S3AsyncClient.class)`

**GCS (`unified-storage-gcs`)**

- `GcsMountProperties` — `credentialsJson` (file path, inline JSON, or `secret://` reference), `projectId`; falls back to ADC when `credentialsJson` is null
- `GcsFileSystemProvider` — full `FileSystemProvider` for `gs://` URIs
- `GcsResumableOutputStream` — wraps `WriteChannel` in a standard `OutputStream`; chunk size aligned with `part-size` configuration
- `GcsResumableUploadHandle` — sequential Resumable Upload session for `CrossProviderCopyHandler`
- `GcsAutoConfiguration` — `@ConditionalOnClass(Storage.class)`

**Demo (`unified-storage-demo`)**

- `StorageDemoController` — REST API demonstrating: S3 write/read, GCS write/read, cross-provider S3→GCS copy, directory listing, local I/O
- `StorageService` — pure NIO2 business service with zero cloud SDK imports
- `application-test.yml` — `file://` mounts, H2 in-memory DB, `posix.enabled: false`

---

### Development history

The following changes were made across development sessions leading to 1.0.0 and are documented here for traceability.

#### Provider-specific configuration classes

- Removed provider-specific fields (`region`, `endpoint`, `accessKey`, `secretKey`, `credentialsJson`, `projectId`) from the base `MountPointProperties`
- Created `S3MountProperties` in `unified-storage-s3` and `GcsMountProperties` in `unified-storage-gcs`, each extending the provider-agnostic base
- Providers now cast `MountPointProperties` to their typed subclass via `toS3Props()` / `toGcsProps()` helpers with a safe fallback for test profiles

#### Lombok integration

- Added `lombok 1.18.32` to the parent BOM and all module `pom.xml` files
- Replaced manual getter/setter boilerplate with `@Data` on `StorageProperties`, `MountPointProperties`, `Posix`, `PosixMetadata`, `S3MountProperties`, and `GcsMountProperties`
- Replaced all 9 manual `private static final Logger log = LoggerFactory.getLogger(...)` declarations with `@Slf4j` — eliminates the copy-paste class-name bug silently present before this change

#### POSIX metadata flag

- Added `unified-storage.posix.enabled` boolean (default `true`) to `StorageProperties.Posix`
- Introduced `PosixMetadataService` as the single control point — providers call the service, never the repository directly
- `PosixMetadataRepository` injected `required = false` — no datasource required when `posix.enabled: false`
- Eliminated 11 scattered `if (posixRepo != null)` null checks across providers and output streams

#### Codebase language standardisation

- All Javadoc, inline comments, log messages, and exception messages translated from French to English across all 27 source files

---

<!--
Template for future releases:

## [X.Y.Z] - YYYY-MM-DD

### Added
### Changed
### Deprecated
### Removed
### Fixed
### Security
-->

[Unreleased]: https://github.com/mehrez-ben-salem/unified-storage/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/mehrez-ben-salem/unified-storage/releases/tag/v1.0.0
