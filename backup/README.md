# storages — Unified Storage Library

Bibliothèque de stockage transparent pour Spring Boot / Java 21.
Permet de conteneuriser des applications NFS sans modifier leur code.

## Modules

| Module | Rôle | Dépendance optionnelle |
|---|---|---|
| `storage-core` | Routeur, SPI, POSIX DB, cross-copy | Toujours présent |
| `storage-s3` | Provider Amazon S3 | Ajouté si S3 utilisé |
| `storage-gcs` | Provider Google Cloud Storage | Ajouté si GCS utilisé |

## Utilisation dans une application cliente

### 1. Dépendances Maven

```xml
<!-- Toujours présent -->
<dependency>
    <groupId>edu.m4z</groupId>
    <artifactId>storage-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Selon le provider utilisé -->
<dependency>
    <groupId>edu.m4z</groupId>
    <artifactId>storage-s3</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<dependency>
    <groupId>edu.m4z</groupId>
    <artifactId>storage-gcs</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Configuration application.yml

```yaml
storages:
  chunk-size: 67108864  # 64 MB — taille des chunks multipart et cross-copy

  reports:
    path: s3://bpce-prod/data/reports
    region: eu-west-1
    access-key: secret://cyberark/s3/reports/access-key
    secret-key: secret://cyberark/s3/reports/secret-key

  archives:
    path: gs://bpce-archives/data/archives
    credentials-json: secret://cyberark/gcs/archives/sa-json
    project-id: bpce-gcp-project

  backup:
    path: s3://bpce-backup/data
    region: eu-west-3
    access-key: secret://cyberark/s3/backup/access-key
    secret-key: secret://cyberark/s3/backup/secret-key

  temp:
    path: file:///opt/tmp
    # Pas de credentials — provider local natif JDK

spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/storage_posix
    username: ${POSIX_DB_USER}
    password: ${POSIX_DB_PASS}
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
```

### 3. Code applicatif — RIEN NE CHANGE

```java
@Service
public class ReportService {

    @Value("${storages.reports.path}")
    private String reportsPath;

    public void save(String filename, byte[] data) throws IOException {
        Path path = Path.of(URI.create(reportsPath + "/" + filename));
        Files.createDirectories(path.getParent());
        Files.write(path, data);  // multipart auto si > chunk-size
    }

    public byte[] load(String filename) throws IOException {
        Path path = Path.of(URI.create(reportsPath + "/" + filename));
        return Files.readAllBytes(path);
    }

    public List<String> list() throws IOException {
        Path dir = Path.of(URI.create(reportsPath));
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).toList();
        }
    }
}
```

### 4. Copy cross-provider (S3 → GCS)

```java
// Files.copy() détecte automatiquement le cross-provider
// et utilise le streaming pipe avec multipart côté destination
Path source = Path.of(URI.create("s3://bpce-prod/data/reports/file.csv"));
Path target = Path.of(URI.create("gs://bpce-archives/data/archives/file.csv"));
Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
// Streaming par chunks de chunk-size. Zéro fichier temp. Zéro mémoire complète.
```

### 5. Intégration secret-manager

Les valeurs `secret://` dans `application.yml` sont résolues automatiquement
par la librairie `secret-manager` existante, avant l'injection de `StorageProperties`.
La lib storage ne sait rien des secrets — elle reçoit les valeurs finales directement.

```yaml
# application.yml — la lib secret-manager résout tout ceci au démarrage
storages:
  reports:
    path: s3://bpce-prod/data/reports
    region: eu-west-1
    access-key: secret://cyberark/s3/reports/access-key   # résolu par secret-manager
    secret-key: secret://cyberark/s3/reports/secret-key   # résolu par secret-manager
```

## Schémas URI supportés

| Schéma | Provider | Exemple |
|---|---|---|
| `file://` | JDK natif (local / NFS monté) | `file:///opt/data/reports` |
| `s3://`   | Amazon S3 | `s3://my-bucket/data/reports` |
| `gs://`   | Google Cloud Storage | `gs://my-bucket/data/reports` |

## Gros fichiers (100 Go)

| Provider | Stratégie | Déclencheur |
|---|---|---|
| S3 | Multipart Upload (parts = chunk-size) | Automatique dès dépassement chunk-size |
| GCS | Resumable Upload (chunks = chunk-size) | Toujours (optimal dès 5 MB) |
| Local | Write NIO streaming | Natif JDK |

## POSIX

Les métadonnées POSIX (owner, group, permissions, timestamps) sont stockées
en base PostgreSQL via Flyway (table `storage_posix_metadata`).
Elles sont transparentes pour le code applicatif via `Files.getAttribute()`.

## Packages

```
edu.m4z.storages
├── config      StorageProperties, StorageAutoConfiguration
├── core        StorageRegistry, CrossProviderCopyHandler
├── posix       PosixMetadata, PosixMetadataRepository
├── secret      SecretResolver, PassthroughSecretResolver
├── spi         UnifiedFileSystemProvider, MultipartUploadHandle
├── s3          S3FileSystemProvider, S3FileSystem, S3Path, S3SmartOutputStream, S3MultipartUploadHandle
└── gcs         GcsFileSystemProvider, GcsFileSystem, GcsPath, GcsResumableOutputStream, GcsResumableUploadHandle
```
