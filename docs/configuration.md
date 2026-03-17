# Configuration Guide

> Complete reference for all `unified-storage.*` properties, mount types, and credential options.

---

## Table of Contents

- [Core Properties](#core-properties)
- [Mount Types](#mount-types)
  - [Amazon S3 / S3-Compatible](#amazon-s3--s3-compatible)
  - [Google Cloud Storage](#google-cloud-storage)
  - [Local Filesystem](#local-filesystem)
- [POSIX Metadata](#posix-metadata)
- [Credential Resolution](#credential-resolution)
- [Environment Variables](#environment-variables)
- [Per-Environment Strategy](#per-environment-strategy)

---

## Core Properties

| Property | Default | Description |
|:---------|:--------|:------------|
| `unified-storage.chunk-size` | `67108864` | Global chunk size in bytes for multipart uploads and cross-provider streaming. Applies to all mounts unless overridden by `part-size`. |
| `unified-storage.posix.enabled` | `true` | Master switch for POSIX metadata persistence. Set `false` to disable entirely — no JPA datasource required. |

---

## Mount Types

Mount points are declared as named entries under `unified-storage`. The `path` URI scheme determines which provider is activated.

### Common properties (all mounts)

| Property | Required | Default | Description |
|:---------|:---------|:--------|:------------|
| `path` | Yes | — | Full URI. Scheme determines the provider: `s3://`, `gs://`, `file:///`. |
| `multipart-threshold` | No | inherits `chunk-size` | Files larger than this (bytes) trigger multipart upload on S3. Ignored for GCS — Resumable Upload is always used. |
| `part-size` | No | inherits `chunk-size` | Size of each part in multipart uploads (bytes). Must be ≥ 5 MB for S3. |

---

### Amazon S3 / S3-Compatible

```yaml
unified-storage:
  reports:
    path: s3://my-bucket/data/reports
    region: eu-west-1
    access-key: ${S3_ACCESS_KEY}
    secret-key: ${S3_SECRET_KEY}
```

| Property | Required | Default | Description |
|:---------|:---------|:--------|:------------|
| `region` | No | `us-east-1` | AWS region. Ignored when `endpoint` is set. |
| `endpoint` | No | — | Custom endpoint for S3-compatible stores. Enables path-style access automatically. |
| `access-key` | No | — | AWS Access Key ID. If absent, `DefaultCredentialsProvider` is used. Supports `secret://`. |
| `secret-key` | No | — | AWS Secret Access Key. Same fallback and `secret://` support. |
| `role-arn` | No | — | IAM Role ARN for AssumeRole. |

**S3-compatible stores (Scality, MinIO, LocalStack):**

```yaml
unified-storage:
  # Scality on-premise
  reports:
    path: s3://bpce-bucket/reports
    region: eu-west-1
    endpoint: https://s3.scality.bpce.internal
    access-key: secret://cyberark/conjur/s3/access-key
    secret-key: secret://cyberark/conjur/s3/secret-key

  # MinIO — local development
  dev-store:
    path: s3://dev-bucket/data
    endpoint: http://localhost:9000
    access-key: minioadmin
    secret-key: minioadmin

  # LocalStack — integration tests
  test-store:
    path: s3://test-bucket/data
    endpoint: http://localhost:4566
    access-key: test
    secret-key: test
```

---

### Google Cloud Storage

```yaml
unified-storage:
  archives:
    path: gs://my-bucket/data/archives
    project-id: my-gcp-project
    credentials-json: secret://cyberark/conjur/gcs/sa-json
```

| Property | Required | Default | Description |
|:---------|:---------|:--------|:------------|
| `credentials-json` | No | — | GCP Service Account credentials. Accepts: absolute file path, inline JSON, or `secret://` reference. If absent, Application Default Credentials (ADC) are used. |
| `project-id` | No | — | GCP project ID. Required with Service Account credentials. Optional on GCE/GKE (inferred from metadata server). |

**Credential formats:**

```yaml
# File path
credentials-json: /etc/gcp/sa-key.json

# Inline JSON
credentials-json: '{"type":"service_account","project_id":"my-proj",...}'

# Secret Manager reference (recommended for production)
credentials-json: secret://cyberark/conjur/gcs/sa-json
```

**Workload Identity (GKE) — no credentials needed:**

```yaml
unified-storage:
  gcs-logs:
    path: gs://bpce-logs/app
    project-id: bpce-gcp-prod-123456
    # credentials-json omitted → Workload Identity used automatically
```

---

### Local Filesystem

```yaml
unified-storage:
  temp:
    path: file:///opt/app/tmp
```

No additional properties. Uses the JDK built-in provider. Ideal for development and test profiles.

---

## POSIX Metadata

| Property | Default | Description |
|:---------|:--------|:------------|
| `unified-storage.posix.enabled` | `true` | Persist file permissions, ownership, and timestamps in `storage_posix_metadata`. Set `false` if no JPA datasource is available or POSIX attributes are not needed. |

When disabled:
- All POSIX operations are silent no-ops
- No database table required
- `readAttributes()` returns cloud-native timestamps (S3 `lastModified`, GCS blob timestamps)

```yaml
unified-storage:
  posix:
    enabled: false
```

---

## Credential Resolution

### secret:// references

Properties that accept `secret://` references are resolved by the Dev Factory **Secret Manager Starter** before Spring binds `StorageProperties`. Unified Storage receives the final plaintext value and has no knowledge of the vault backend.

```yaml
access-key: secret://cyberark/conjur/s3/reports/access-key
# ↑ resolved to the actual key before the property is injected
```

### S3 — DefaultCredentialsProvider resolution order

When `access-key` and `secret-key` are absent:

| Priority | Source |
|:---------|:-------|
| 1 | `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` environment variables |
| 2 | `aws.accessKeyId` / `aws.secretAccessKey` system properties |
| 3 | `~/.aws/credentials` file |
| 4 | ECS task IAM role |
| 5 | EC2 instance profile |
| 6 | EKS IRSA (recommended for OpenShift / GKE via OIDC) |

### GCS — Application Default Credentials resolution order

When `credentials-json` is absent:

| Priority | Source |
|:---------|:-------|
| 1 | `GOOGLE_APPLICATION_CREDENTIALS` environment variable |
| 2 | Workload Identity (GKE) — token injected by node agent |
| 3 | GCE metadata server — attached service account |
| 4 | `gcloud auth application-default login` (development only) |

---

## Environment Variables

All properties resolve via Spring Boot's relaxed binding:

| Property | Environment Variable |
|:---------|:--------------------|
| `unified-storage.chunk-size` | `UNIFIED_STORAGE_CHUNK_SIZE` |
| `unified-storage.posix.enabled` | `UNIFIED_STORAGE_POSIX_ENABLED` |
| `unified-storage.reports.access-key` | `UNIFIED_STORAGE_REPORTS_ACCESS_KEY` |
| `unified-storage.reports.secret-key` | `UNIFIED_STORAGE_REPORTS_SECRET_KEY` |

**Never commit credentials to source control.** Always use environment variables, Kubernetes Secrets, or `secret://` references.

---

## Per-Environment Strategy

Define mount names once in `application.yml` and override values per environment.

```yaml
# application.yml — base
unified-storage:
  chunk-size: 67108864
  posix:
    enabled: true
  reports:
    path: ${REPORTS_PATH}
    region: ${REPORTS_REGION:eu-west-1}
    access-key: ${REPORTS_ACCESS_KEY:}
    secret-key: ${REPORTS_SECRET_KEY:}
```

```yaml
# application-dev.yml
unified-storage:
  reports:
    path: file:///tmp/dev-reports
  posix:
    enabled: false
```

```yaml
# application-test.yml
unified-storage:
  reports:
    path: file:///tmp/test-reports
  posix:
    enabled: false
```

```yaml
# application-prod.yml (or Kubernetes ConfigMap)
unified-storage:
  reports:
    path: s3://bpce-reports/data/reports
    endpoint: https://s3.scality.bpce.internal
    access-key: secret://cyberark/conjur/s3/reports/access-key
    secret-key: secret://cyberark/conjur/s3/reports/secret-key
```
