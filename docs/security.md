# Security Model

> How Unified Storage protects credentials at every stage — from YAML resolution to cloud client construction.

---

## Table of Contents

- [Threat Model](#threat-model)
- [Credential Management](#credential-management)
- [Secret Resolution via secret://](#secret-resolution-via-secret)
- [Transport Security](#transport-security)
- [POSIX Metadata — GDPR Surface](#posix-metadata--gdpr-surface)
- [Audit Trail](#audit-trail)
- [Compliance Considerations](#compliance-considerations)

---

## Threat Model

| Threat | Mitigation |
|:-------|:-----------|
| Credentials exposed in logs | All log statements use parameterised SLF4J patterns — credential fields are never passed to `log.*()` |
| Credentials exposed via heap dump | Cloud SDK client objects hold credentials; mitigate with container-level memory protection |
| Credentials embedded in URLs | Material is passed exclusively via SDK credential provider chains, never as URL parameters or query strings |
| Data written to wrong bucket | Mount names and URIs are validated at startup; no dynamic bucket selection at runtime |
| POSIX table SQL injection | All queries use Spring Data JPA prepared statements — no string concatenation |
| Plain HTTP endpoint in production | Startup `WARN` emitted; ops must validate intentionality |
| Orphaned S3 multipart uploads | `abortMultipartUpload()` called in the `catch` block of `close()` |

---

## Credential Management

### Design principle

Unified Storage never stores credentials. It receives already-resolved values from Spring's property injection pipeline. Credentials exist only as in-memory objects inside the cloud SDK's credential provider chain — never serialised, never logged, never written to disk.

### S3 — credential resolution order

When `access-key` and `secret-key` are present, `StaticCredentialsProvider` is used.

When absent, `DefaultCredentialsProvider` resolves in order:

| Priority | Source |
|:---------|:-------|
| 1 | `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` environment variables |
| 2 | `aws.accessKeyId` / `aws.secretAccessKey` system properties |
| 3 | `~/.aws/credentials` file |
| 4 | ECS task IAM role |
| 5 | EC2 instance profile |
| 6 | EKS IRSA (recommended — pod identity, no long-lived keys) |

### GCS — credential resolution order

When `credentials-json` is present, `ServiceAccountCredentials` are parsed.

When absent, Application Default Credentials resolve in order:

| Priority | Source |
|:---------|:-------|
| 1 | `GOOGLE_APPLICATION_CREDENTIALS` environment variable |
| 2 | Workload Identity (GKE — recommended) |
| 3 | GCE metadata server |
| 4 | `gcloud auth application-default login` (development only) |

### Recommended IAM policies

**S3 — least privilege:**

```json
{
  "Effect": "Allow",
  "Action": [
    "s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket",
    "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"
  ],
  "Resource": [
    "arn:aws:s3:::bpce-reports-bucket",
    "arn:aws:s3:::bpce-reports-bucket/data/reports/*"
  ]
}
```

`AbortMultipartUpload` and `ListMultipartUploadParts` are required for `S3SmartOutputStream` to clean up failed uploads.

**GCS — least privilege:**

Assign `roles/storage.objectAdmin` scoped to the specific bucket. Do not grant project-level permissions.

---

## Secret Resolution via secret://

Properties prefixed with `secret://` are resolved by the Dev Factory **Secret Manager Starter** before Spring binds `StorageProperties`. Unified Storage receives the final plaintext value and has no awareness of the vault backend.

```yaml
access-key: secret://cyberark/conjur/path/to/key
# ↑ resolved to the actual key value before this property reaches Unified Storage
```

Rotating a credential requires only a Conjur/Vault update — not a redeployment. However, because cloud SDK clients are built once at startup, a new client is only constructed after an application restart.

---

## Transport Security

All cloud SDK calls use HTTPS by default. The `endpoint` property accepts both `http://` and `https://`. Using `http://` with a non-localhost hostname triggers a startup warning:

```
WARN  Mount 'reports' uses plain HTTP endpoint 'http://scality.internal' —
      verify this is intentional (internal network only)
```

For on-premise Scality endpoints with internal CA certificates, add the CA to the JVM truststore used by the application container. The cloud SDKs use the JVM default truststore for TLS validation.

---

## POSIX Metadata — GDPR Surface

### What is stored

The `storage_posix_metadata` table stores virtual paths (full URIs), file sizes, and timestamps. It does **not** store file content.

### GDPR implications

If `virtual_path` contains personal identifiers — client IDs, user IDs, IBAN fragments — the POSIX table falls within the application's GDPR data retention scope.

Recommended practices:
- Apply the same retention period to `storage_posix_metadata` as to the files themselves
- Note that `PosixMetadataService.delete()` is called automatically on every `Files.delete()`, so file deletion cascades to the metadata record

To eliminate this surface entirely:

```yaml
unified-storage:
  posix:
    enabled: false
```

When disabled, no data is written to the database and no JPA datasource is required.

---

## Audit Trail

### Operational audit — structured logs

Every mount registration, write completion, and cross-provider copy produces a structured `INFO` log. Combined with MDC enrichment (traceId, mountName, provider), each log line is traceable end-to-end through the SIEM pipeline.

### Data audit — POSIX metadata table

When `posix.enabled: true`, every write updates `modified_at` in `storage_posix_metadata`. This provides a point-in-time audit of when each file was last written.

```sql
-- Files modified after a specific date
SELECT virtual_path, size, owner, modified_at
FROM storage_posix_metadata
WHERE modified_at > '2026-01-01'
  AND virtual_path LIKE 's3://bpce-reports/%'
ORDER BY modified_at DESC;
```

### Cloud-native server-side logs

Object stores maintain server-side access logs independently. Enable S3 Server Access Logging or GCS Data Access Logs at the bucket level for infrastructure-level audit independent of the application.

---

## Compliance Considerations

Unified Storage's design supports compliance with:

| Framework | How |
|:----------|:----|
| **DORA Art. 9** — ICT risk management | Centralised library: single point to audit, patch, and update across 150+ applications |
| **DORA Art. 9** — Resilience | GCS Resumable Upload survives network interruptions; S3 multipart aborts cleanly on failure |
| **DORA Art. 10** — Backup and recovery | Object stores provide native versioning and cross-region replication at the bucket level |
| **DORA Art. 17** — Incident management | Structured logs with `traceId`, `mountName`, `provider` MDC keys for fast correlation |
| **GDPR / RGPD** | POSIX metadata contains only paths and sizes; `posix.enabled: false` eliminates it entirely |
| **PCI DSS** | No plaintext credential storage; HTTPS enforced; no credentials in logs |

> **Note:** Unified Storage is a library, not a certified product. Compliance depends on how it is deployed and configured within your broader infrastructure.
