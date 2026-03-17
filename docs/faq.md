# Frequently Asked Questions

> Common questions, troubleshooting tips, and practical guidance.

---

## General

### Does the business code really need zero changes?

Yes — for applications that use `java.nio.file.Files` and `java.nio.file.Path`. The only change is the value of the injected path property in `application.yml`. The Java code that calls `Files.write()`, `Files.readAllBytes()`, `Files.copy()`, or `Files.newDirectoryStream()` stays identical.

Applications using `java.io.File` directly need a one-line adaptation:

```java
// Before
File file = new File(reportsPath + "/" + filename);

// After — one line, then everything below is unchanged
Path path = Path.of(URI.create(reportsPath + "/" + filename));
```

Applications calling AWS SDK or GCS SDK methods directly are already cloud-native and don't benefit from this library.

### Can I use multiple providers in the same application?

Yes. Declare one mount per storage area; each mount selects its provider independently.

```yaml
unified-storage:
  reports:
    path: s3://company-reports/reports
  archives:
    path: gs://company-archive/archives
  temp:
    path: file:///opt/app/tmp
```

A `Files.copy(reportsPath, archivesPath)` between these mounts triggers the `CrossProviderCopyHandler` automatically.

### What happens if the cloud backend is unreachable at startup?

The application fails to start. This is intentional — a misconfigured or unreachable storage backend should surface immediately, not silently at the first I/O call in production.

### Does this work with Spring Cloud Config or Kubernetes ConfigMap?

Yes. `unified-storage.*` properties are standard Spring `@ConfigurationProperties` and resolve from any Spring property source.

---

## Configuration

### What is the minimum configuration for a local development profile?

```yaml
unified-storage:
  my-mount:
    path: file:///tmp/dev-data
  posix:
    enabled: false
```

No cloud credentials. No JPA datasource. No Docker.

### Do I need to create the S3 bucket or GCS bucket beforehand?

Yes. Unified Storage writes to and reads from existing buckets. Bucket creation and IAM policy configuration are infrastructure responsibilities.

### What is the minimum S3 part size?

AWS S3 requires each part to be at least **5 MB (5,242,880 bytes)**, except for the final part. Setting `part-size` below this causes upload failures. The default 64 MB is well above this limit.

### Can I point two mounts at the same bucket with different prefixes?

Yes.

```yaml
unified-storage:
  reports-2025:
    path: s3://company-data/reports/2025
    region: eu-west-1
  reports-2026:
    path: s3://company-data/reports/2026
    region: eu-west-1
```

---

## Migration

### How do I migrate an application using java.io.FileInputStream?

`FileInputStream` bypasses the NIO2 SPI dispatch. The adaptation is minimal:

```java
// Before
InputStream in = new FileInputStream(reportsPath + "/" + filename);

// After
Path path = Path.of(URI.create(reportsPath + "/" + filename));
InputStream in = Files.newInputStream(path);
```

One line per call site. The surrounding logic is unchanged.

### How do I migrate an application using Apache Commons IO?

Replace Commons IO calls with `java.nio.file.Files` equivalents:

| Commons IO | NIO2 |
|:-----------|:-----|
| `FileUtils.writeByteArrayToFile(file, data)` | `Files.write(path, data)` |
| `FileUtils.readFileToByteArray(file)` | `Files.readAllBytes(path)` |
| `FileUtils.copyFile(src, dst)` | `Files.copy(src, dst)` |
| `FileUtils.listFiles(dir, ...)` | `Files.newDirectoryStream(dir)` |

---

## Testing

### How do I write unit tests without cloud credentials?

Use the `file://` scheme in the test profile:

```yaml
# application-test.yml
unified-storage:
  reports:
    path: file:///tmp/test-reports
  posix:
    enabled: false
```

```java
@SpringBootTest
@ActiveProfiles("test")
class ReportServiceTest {
    @Autowired ReportService service;

    @Test
    void saveAndLoad() throws Exception {
        service.save("q1.csv", "data".getBytes());
        assertArrayEquals("data".getBytes(), service.load("q1.csv"));
    }
}
```

No `MockBean`, no Mockito, no cloud emulator. Real code, real filesystem.

### How do I run integration tests against a real S3 protocol?

Use LocalStack via Testcontainers:

```java
@Testcontainers
@SpringBootTest
@ActiveProfiles("localstack")
class S3IntegrationTest {

    @Container
    static LocalStackContainer localstack =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack"))
            .withServices(LocalStackContainer.Service.S3);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("unified-storage.reports.endpoint",
            () -> localstack.getEndpointOverride(LocalStackContainer.Service.S3).toString());
    }
}
```

---

## Troubleshooting

### Application fails to start with "No provider available for scheme 's3'"

The `unified-storage-s3` module is not on the classpath. Verify the dependency in `pom.xml`:

```bash
mvn dependency:tree | grep unified-storage
```

### Application fails to start with "S3 bucket not configured: my-bucket"

A `Path.of(URI.create("s3://my-bucket/..."))` call is happening during context construction — before `StorageRegistry.initialize()` completes via `@PostConstruct`. Move path resolution to a `@PostConstruct` method or to the actual I/O call.

### Files.write() succeeds but the file is empty on S3

`S3SmartOutputStream.close()` is where `putObject()` or `completeMultipartUpload()` is called. If `close()` is never reached, the file lands empty or not at all. Always use `Files.write()` or try-with-resources:

```java
// close() guaranteed
Files.write(path, content, StandardOpenOption.CREATE);

// close() guaranteed via try-with-resources
try (OutputStream out = Files.newOutputStream(path)) {
    out.write(content);
}

// close() may be skipped on exception — avoid this pattern
OutputStream out = Files.newOutputStream(path);
out.write(content);
out.close();
```

### WARN: Failed to save POSIX metadata for ...

The POSIX metadata write failed — usually because the datasource is unavailable or the table doesn't exist. The storage operation **succeeded**. The warning is non-blocking.

Either fix the datasource configuration, or set `unified-storage.posix.enabled: false`.

### GCS upload appears to succeed but the object isn't visible

GCS Resumable Upload sessions are committed only when `WriteChannel.close()` is called. If the process exits before `GcsResumableOutputStream.close()` completes, no object is created. GCS expires incomplete sessions after 7 days.

Always use `Files.write()` or try-with-resources to guarantee `close()` is called.

### How do I debug provider routing issues?

Set the log level to `DEBUG`:

```yaml
logging:
  level:
    edu.m4z.unifiedstorage: DEBUG
```

This shows mount registration, provider dispatch, and per-chunk events — without logging credential values.
