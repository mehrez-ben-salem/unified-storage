# Contributing to Unified Storage

Thank you for considering a contribution. Every bug report, feature idea, documentation fix, or code improvement matters.

---

## Table of Contents

- [How Can I Contribute?](#how-can-i-contribute)
- [Reporting Bugs](#reporting-bugs)
- [Suggesting Features](#suggesting-features)
- [Development Setup](#development-setup)
- [Pull Request Process](#pull-request-process)
- [Coding Standards](#coding-standards)
- [Adding a New Provider](#adding-a-new-provider)

---

## How Can I Contribute?

| Contribution | Where to Start |
|:-------------|:---------------|
| Found a bug | [Open a bug report](#reporting-bugs) |
| Have an idea | [Suggest a feature](#suggesting-features) |
| Improve docs | Fork → edit → PR (no issue needed for typos) |
| New storage provider | See [Adding a New Provider](#adding-a-new-provider) |
| Write tests | Always welcome — especially integration tests |

---

## Reporting Bugs

Open an issue with:

1. **What you expected** to happen
2. **What actually happened** (include stack traces if applicable)
3. **Steps to reproduce** — minimal example preferred
4. **Environment**: Java version, Spring Boot version, provider (S3/GCS), OS

> **Security vulnerabilities** should be reported privately. See [SECURITY.md](SECURITY.md).

---

## Suggesting Features

Open an issue tagged `enhancement` with:

1. **The problem** you're trying to solve
2. **Your proposed solution** — even a rough sketch is helpful
3. **Alternatives** you considered

---

## Development Setup

### Prerequisites

- Java 21
- Maven 3.8+
- Docker (for LocalStack and Fake GCS Server integration tests)
- IntelliJ IDEA or Eclipse with the **Lombok plugin** installed

### Build

```bash
git clone https://github.com/mehrez-ben-salem/unified-storage.git
cd unified-storage
mvn clean install -DskipTests
```

### Run the demo locally

```bash
cd unified-storage-demo
mvn spring-boot:run -Dspring-boot.run.profiles=test
# Uses file:// mounts, H2 in-memory DB, no cloud credentials needed
```

### Run tests

```bash
mvn test              # unit tests only
mvn verify -P integration  # unit + integration tests (requires Docker)
```

---

## Pull Request Process

1. **Create a branch** from `main`:
   ```bash
   git checkout -b feature/my-improvement
   ```

2. **Make your changes** — keep commits focused.

3. **Add tests** for any new functionality.

4. **Verify everything passes**:
   ```bash
   mvn clean verify
   ```

5. **Update `CHANGELOG.md`** under `[Unreleased]`.

6. **Submit a pull request** against `main` with a clear title and description.

### Commit Convention

```
feat: add Azure Blob provider
fix: abort multipart upload on close() failure
docs: update S3 configuration examples
refactor: extract toS3Props() cast helper
test: add cross-provider copy integration test
```

---

## Coding Standards

### Language

All comments, Javadoc, log messages, and exception messages must be in **English**. No French.

### Lombok

| Use | For |
|:----|:----|
| `@Data` | Configuration/properties classes and JPA entities |
| `@EqualsAndHashCode(callSuper = true)` | Classes extending `MountPointProperties` |
| `@Slf4j` | **Every** class that logs — never declare `Logger` manually |

```java
// Correct
@Slf4j
public class MyProvider extends UnifiedFileSystemProvider { }

// Never
private static final Logger log = LoggerFactory.getLogger(MyProvider.class);
```

### Logging

- Parameterised SLF4J patterns only: `log.info("Mount '{}' ready", mountName)`
- `INFO` for lifecycle events (mount registration, upload completion)
- `DEBUG` for per-chunk or per-part events
- **Never log credential values** — keys, tokens, JSON, passwords

### Error handling

- Storage operations must throw `IOException` on failure
- POSIX metadata failures must never propagate — catch, log at `WARN`, continue
- Always call `abortMultipartUpload()` in the `catch` block of a multipart write

### Thread safety

- All mutable state in providers must use `ConcurrentHashMap`
- State is initialised once in `registerMount()` and thereafter read-only
- No `synchronized` methods

---

## Adding a New Provider

See [docs/custom-provider.md](docs/custom-provider.md) for the full walkthrough.

The short version:

1. Create a Maven module `unified-storage-<name>`
2. Define `<Name>MountProperties extends MountPointProperties`
3. Implement `<Name>FileSystemProvider extends UnifiedFileSystemProvider`
4. Register via `META-INF/services/java.nio.file.spi.FileSystemProvider`
5. Write a Spring Boot `@AutoConfiguration` class
6. Add integration tests with a local emulator

Do not modify `unified-storage-core` unless the new provider requires a capability the SPI contract does not yet cover.
