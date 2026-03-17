# Architecture Guide

> A deep dive into how Unified Storage is structured, how data flows, and why each design decision was made.

---

## Table of Contents

- [Design Philosophy](#design-philosophy)
- [Module Structure](#module-structure)
- [Data Flow](#data-flow)
- [Java NIO2 SPI — The Foundation](#java-nio2-spi--the-foundation)
- [Spring Boot Integration Bridge](#spring-boot-integration-bridge)
- [Large File Handling](#large-file-handling)
- [Cross-Provider Copy](#cross-provider-copy)
- [POSIX Metadata Layer](#posix-metadata-layer)
- [Configuration Hierarchy](#configuration-hierarchy)
- [Observability](#observability)

---

## Design Philosophy

Unified Storage is built around three core principles:

1. **Zero business code changes** — developers should not need to learn a new API, import a cloud SDK, or add conditionals for local vs. cloud. The application writes `Files.write()` and the bytes go where the YAML says.

2. **Provider independence** — switching from S3 to GCS (or adding Azure Blob) should require changing a URI in `application.yml`, not refactoring application code.

3. **Fail-safe non-critical paths** — POSIX metadata failures must never break a storage operation. Only actual I/O errors propagate to the application.

---

## Module Structure

```
unified-storage/
    unified-storage-core/    # The engine — SPI contracts, registry, cross-copy, POSIX
    unified-storage-s3/      # S3 and S3-compatible providers
    unified-storage-gcs/     # Google Cloud Storage provider
    unified-storage-demo/    # Full Spring Boot demo application
```

`unified-storage-core` has no cloud SDK dependency. Each provider module depends only on its own SDK. An application using only S3 has no GCS SDK on its classpath.

---

## Data Flow

### Write (startup resolution + runtime)

```mermaid
flowchart TD
    A["@Value(&quot;${unified-storage.reports.path}&quot;)\nreportsPath = 's3://my-bucket/reports'"] --> B["Path.of(URI.create(reportsPath + '/' + name))"]
    B --> C["Files.write(path, content)"]
    C --> D["FileSystemProvider.provider(path)\nJDK resolves scheme 's3'"]
    D --> E["S3FileSystemProvider.newOutputStream()"]
    E --> F["S3SmartOutputStream\nbuffer accumulates data"]
    F --> G{buffer > multipartThreshold?}
    G -- No --> H["putObject()\nsingle API call"]
    G -- Yes --> I["createMultipartUpload()\nuploadPart(1..n)\ncompleteMultipartUpload()"]
    H --> J["PosixMetadataService.saveOrUpdateFile()\nif posix.enabled = true"]
    I --> J
```

### Cross-provider copy

```mermaid
flowchart TD
    A["Files.copy(s3Path, gcsPath)"] --> B["CrossProviderCopyHandler.copy()"]
    B --> C["InputStream\nS3AsyncClient.getObject()"]
    B --> D["GcsResumableUploadHandle\nWriteChannel opened"]
    C --> E["Streaming loop\nread chunk — 64 MB at a time"]
    D --> E
    E --> F["handle.uploadPart()\nwrite chunk to GCS"]
    F --> G{EOF?}
    G -- No --> E
    G -- Yes --> H["handle.complete()\nWriteChannel.close()"]
```

---

## Java NIO2 SPI — The Foundation

Java NIO2 uses a service-loader mechanism to discover `FileSystemProvider` implementations. Each provider declares itself at:

```
META-INF/services/java.nio.file.spi.FileSystemProvider
```

The JDK reads this file once at startup. From that point, any `Path` whose URI scheme matches a registered provider routes to it — automatically, transparently.

This is the same mechanism used by Apache Hadoop (`hdfs://`), Google Jimfs (in-memory filesystem), and JDBC `DriverManager`. It is a JDK standard, not a library-specific extension.

**What makes this powerful for migration:** existing application code never changes. The URI scheme in `application.yml` is the only switch.

---

## Spring Boot Integration Bridge

Java SPI and Spring Boot have a fundamental tension: SPI requires a no-arg constructor and instanciates providers **outside** the Spring context. `@Autowired` fields are not injected in SPI-created instances.

Unified Storage resolves this with `SpringAwareFileSystemProviderBridge`:

```mermaid
sequenceDiagram
    participant JDK as JDK ServiceLoader
    participant Shadow as S3FileSystemProvider (shadow)
    participant Bridge as SpringAwareFileSystemProviderBridge
    participant Bean as S3FileSystemProvider (Spring bean)

    Note over JDK,Shadow: At JVM startup — SPI registration
    JDK->>Shadow: new S3FileSystemProvider() via no-arg constructor
    Note over Shadow: posixService = null

    Note over Bean: Spring context starts
    Bean->>Bridge: setApplicationContext(ctx)

    Note over JDK,Bean: At runtime — Files.write(s3Path, data)
    JDK->>Shadow: newOutputStream(path)
    Shadow->>Shadow: springBean() — posixService is null
    Shadow->>Bridge: getBean(S3FileSystemProvider.class)
    Bridge-->>Shadow: returns fully-injected Bean
    Shadow->>Bean: newOutputStream(path)
    Bean-->>JDK: S3SmartOutputStream
```

Every provider method goes through `springBean()`. All operations always execute on the fully-injected instance.

The `required = false` on `posixService` is intentional: when `posix.enabled: false`, no `PosixMetadataRepository` bean is created (no datasource required). `PosixMetadataService` handles the null-repository case internally.

---

## Large File Handling

### S3 — S3SmartOutputStream

The stream maintains a single in-memory buffer of `partSize` bytes.

```mermaid
flowchart TD
    A["write(data)"] --> B["Fill buffer"]
    B --> C{Buffer full?}
    C -- No --> A
    C -- Yes --> D{totalWritten > multipartThreshold\nand not yet multipart?}
    D -- Yes --> E["createMultipartUpload()"]
    E --> F["uploadPart(partNumber++)\nclear buffer"]
    D -- No --> G{Multipart active?}
    G -- Yes --> F
    G -- No --> A
    F --> A
    A --> H["close()"]
    H --> I{Multipart started?}
    I -- Yes --> J["flush remaining buffer\ncompleteMultipartUpload()"]
    I -- No --> K["putObject()\nfull content in one call"]
    J --> L["PosixMetadataService.saveOrUpdateFile()"]
    K --> L
```

**Memory:** at most `partSize` bytes (default 64 MB) in memory regardless of file size.

**Failure safety:** if `close()` throws, `abortMultipartUpload()` is called before re-throwing. This releases the in-progress upload on S3 and avoids orphaned storage charges.

### GCS — GcsResumableOutputStream

GCS does not have a multipart API equivalent to S3. The library uses the **GCS Resumable Upload** protocol via `WriteChannel`. Data flows through `ByteBuffer` slices; the SDK manages chunk boundaries and HTTP sessions internally. On network failure, the SDK resumes from the last acknowledged byte.

### Range reads

Applications that process only a portion of a large file can avoid loading the full file:

```java
InputStream in = provider.newRangeInputStream(path, offset, length);
```

- S3: `Range: bytes=offset-(offset+length-1)` on `GetObject`
- GCS: `ReadChannel.seek(offset)` + `ReadChannel.limit(end)`

---

## Cross-Provider Copy

When `Files.copy(source, target)` is called with paths on two different providers, `CrossProviderCopyHandler` streams bytes directly between them.

```mermaid
flowchart TD
    A["copy(source, target)"] --> B{Is target a cloud provider?}
    B -- Yes --> C["Open InputStream on source\nOpen MultipartUploadHandle on target"]
    B -- No --> D["Open InputStream on source\nOpen OutputStream on target\nPipe with chunkSize buffer"]
    C --> E["Read chunk — chunkSize bytes"]
    E --> F["handle.uploadPart()"]
    F --> G{EOF?}
    G -- No --> E
    G -- Yes --> H["handle.complete()"]
    D --> I["Pipe until EOF"]
```

**Memory profile:** `chunkSize` bytes maximum (default 64 MB). A 200 GB copy uses 64 MB of heap.

**Failure:** `handle.abort()` is called in the `catch` block. Partial data is discarded on the target.

---

## POSIX Metadata Layer

Cloud object stores have no concept of Unix permissions, file ownership, or sub-second access timestamps. The `PosixMetadataService` emulates these via the `storage_posix_metadata` table.

### Single control point

```mermaid
flowchart TD
    S3["S3FileSystemProvider"] --> SVC["PosixMetadataService\nisEnabled()"]
    GCS["GcsFileSystemProvider"] --> SVC
    OUT_S3["S3SmartOutputStream"] --> SVC
    OUT_GCS["GcsResumableOutputStream"] --> SVC
    HANDLE["GcsResumableUploadHandle"] --> SVC

    SVC --> CHECK{posix.enabled\nAND repository != null?}
    CHECK -- No --> NOP["silent no-op\nreturn / Optional.empty()"]
    CHECK -- Yes --> REPO["PosixMetadataRepository\nJPA prepared statements"]
    REPO --> DB[("storage_posix_metadata")]
```

Providers call the service. They never call the repository directly. The flag is the single, non-negotiable control point.

### When disabled

- All methods are silent no-ops
- `find()` always returns `Optional.empty()`
- No JPA datasource required
- `readAttributes()` falls back to cloud-native timestamps (S3 `lastModified`, GCS blob timestamps)

### Database schema

```sql
CREATE TABLE storage_posix_metadata (
    id           VARCHAR(36)   PRIMARY KEY,
    virtual_path VARCHAR(2048) NOT NULL UNIQUE,  -- e.g. s3://bucket/path/file.csv
    parent_path  VARCHAR(2048),
    name         VARCHAR(512),
    owner        VARCHAR(255),
    grp          VARCHAR(255),
    permissions  INT           NOT NULL DEFAULT 420,  -- 0644 octal
    created_at   TIMESTAMP,
    modified_at  TIMESTAMP,
    accessed_at  TIMESTAMP,
    size         BIGINT        NOT NULL DEFAULT 0,
    is_directory BOOLEAN       NOT NULL DEFAULT FALSE,
    link_target  VARCHAR(2048)
);
```

---

## Configuration Hierarchy

```mermaid
classDiagram
    class StorageProperties {
        long chunkSize = 67108864
        Posix posix
        Map~String, MountPointProperties~ mounts
    }
    class Posix {
        boolean enabled = true
    }
    class MountPointProperties {
        String path
        Long multipartThreshold
        Long partSize
    }
    class S3MountProperties {
        String region
        String endpoint
        String accessKey
        String secretKey
        String roleArn
    }
    class GcsMountProperties {
        String credentialsJson
        String projectId
    }

    StorageProperties --> Posix
    StorageProperties --> MountPointProperties
    MountPointProperties <|-- S3MountProperties
    MountPointProperties <|-- GcsMountProperties
```

---

## Observability

### @Slf4j

All classes use `@Slf4j` (Lombok). The generated `Logger` is byte-for-byte identical to a hand-written `LoggerFactory.getLogger(ClassName.class)` — but eliminates the copy-paste class-name mistake that causes misleading log output.

### Key log events

| Level | Message |
|:------|:--------|
| `INFO` | `POSIX metadata management: ENABLED / DISABLED` |
| `INFO` | `Mount 'reports' → s3://bucket (scheme: s3)` |
| `INFO` | `S3 multipart upload completed: s3://... (N parts, X bytes)` |
| `INFO` | `GCS Resumable upload completed: gs://... (X bytes)` |
| `INFO` | `Cross-provider copy completed: N parts uploaded` |
| `WARN` | `Failed to save POSIX metadata for ...: ...` (non-blocking) |

### MDC and thread boundaries

SLF4J `Logger` and `MDC` are part of the same API — fully compatible, no adapter needed.

```java
try (var m = MDC.putCloseable("mountName", "reports")) {
    Files.write(path, content);
    // log lines from S3SmartOutputStream will include mountName
}
```

**Important:** `S3AsyncClient` executes `CompletableFuture` callbacks on its own thread pool. MDC entries set on the HTTP thread are not propagated automatically. Capture and restore explicitly if needed:

```java
Map<String, String> ctx = MDC.getCopyOfContextMap();
s3Client.putObject(req, body).thenApply(res -> {
    if (ctx != null) MDC.setContextMap(ctx);
    try {
        log.debug("Upload complete");
        return res;
    } finally {
        MDC.clear();
    }
});
```

GCS writes use `WriteChannel.write()` synchronously on the calling thread — no thread boundary issue.
