# storage-demo — Guide de démarrage rapide

Application Spring Boot REST démontrant les 5 scénarios de la lib storages.

## Prérequis

### Option A — LocalStack (S3 local) + PostgreSQL

```bash
# S3 local via LocalStack
docker run -d -p 4566:4566 --name localstack localstack/localstack

# Créer les buckets de démo
aws --endpoint-url=http://localhost:4566 s3 mb s3://demo-reports-bucket
aws --endpoint-url=http://localhost:4566 s3 mb s3://demo-archives-bucket

# PostgreSQL pour les métadonnées POSIX
docker run -d -p 5432:5432 \
  -e POSTGRES_DB=storage_posix \
  -e POSTGRES_USER=storages \
  -e POSTGRES_PASSWORD=storages \
  --name storage-postgres postgres:15
```

### Option B — Tests uniquement (H2 + local)

```bash
mvn test -pl storage-demo -Dspring.profiles.active=test
```

---

## Démarrage

```bash
# Build
mvn clean install -DskipTests

# Démarrage avec LocalStack
cd storage-demo
mvn spring-boot:run -Dspring-boot.run.profiles=localstack

# L'API est disponible sur http://localhost:8080
```

---

## Scénario 1 — Écriture / Lecture S3

```bash
# Upload d'un fichier vers S3
curl -X POST http://localhost:8080/api/storage/s3/rapport-2024.csv \
     -F "file=@/chemin/vers/rapport.csv"

# Réponse :
# {
#   "status": "uploaded",
#   "provider": "S3",
#   "filename": "rapport-2024.csv",
#   "size": 4096,
#   "path": "s3://demo-reports-bucket/data/reports/rapport-2024.csv"
# }

# Téléchargement depuis S3
curl http://localhost:8080/api/storage/s3/rapport-2024.csv \
     --output rapport-recupere.csv
```

---

## Scénario 2 — Écriture / Lecture GCS

```bash
# Upload vers GCS (Resumable Upload automatique)
curl -X POST http://localhost:8080/api/storage/gcs/archive-2024.csv \
     -F "file=@/chemin/vers/archive.csv"

# Téléchargement depuis GCS
curl http://localhost:8080/api/storage/gcs/archive-2024.csv \
     --output archive-recupere.csv
```

---

## Scénario 3 — Copy cross-provider S3 → GCS

```bash
# Copie streaming direct S3 → GCS (zéro fichier temp, compatible 100 Go)
curl -X POST http://localhost:8080/api/storage/copy/s3-to-gcs/rapport-2024.csv

# Réponse :
# {
#   "status": "copied",
#   "filename": "rapport-2024.csv",
#   "source": "S3 → s3://demo-reports-bucket/data/reports",
#   "destination": "GCS → gs://demo-archives-bucket/data/archives",
#   "elapsedMs": 142
# }
```

---

## Scénario 4 — Listing répertoire

```bash
# Listing S3
curl http://localhost:8080/api/storage/s3
# { "provider": "S3", "path": "s3://...", "count": 3, "files": [...] }

# Listing GCS
curl http://localhost:8080/api/storage/gcs

# Listing local
curl http://localhost:8080/api/storage/local
```

---

## Scénario 5 — Fichier local (file://)

```bash
# Même API, provider JDK natif — aucune différence pour le code
curl -X POST http://localhost:8080/api/storage/local/temp.txt \
     -F "file=@/tmp/monFichier.txt"

curl http://localhost:8080/api/storage/local/temp.txt
```

---

## Métadonnées POSIX

```bash
# Attributs POSIX d'un fichier S3 (stockés en PostgreSQL)
curl http://localhost:8080/api/storage/meta/s3/rapport-2024.csv
# {
#   "path": "s3://demo-reports-bucket/data/reports/rapport-2024.csv",
#   "size": 4096,
#   "createdAt": "2024-01-15T10:30:00Z",
#   "modifiedAt": "2024-01-15T10:30:00Z",
#   "regularFile": true,
#   "directory": false
# }
```

---

## Architecture — Transparence totale

```
StorageDemoController
        ↓  @Value("${storages.reports.path}")  → "s3://demo-reports-bucket/data/reports"
StorageService
        ↓  Path.of(URI.create("s3://demo-reports-bucket/data/reports/rapport.csv"))
Java NIO SPI (FileSystemProvider)
        ↓  détecte schéma "s3" → délègue au bean Spring S3FileSystemProvider
S3FileSystemProvider (bean Spring)
        ↓  S3AsyncClient.putObject() / getObject()
Amazon S3 / LocalStack
```

**Le code de StorageService est identique que le fichier soit sur S3, GCS ou local.**
Seul le contenu de `application.yml` change.
