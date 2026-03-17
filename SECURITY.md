# Security Policy

## Supported Versions

| Version | Supported |
|:--------|:----------|
| 1.x | Active support |

---

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public issues.**

If you discover a vulnerability in Unified Storage, please disclose it responsibly.

### How to Report

Open a confidential ticket in the cowork tracker with the label `security` and component `unified-storage`, or send an email to **security@mehrez-ben-salem.com**.

Please include:

1. **Description** of the vulnerability
2. **Steps to reproduce** or a proof-of-concept
3. **Impact assessment** — what an attacker could achieve
4. **Affected versions** (if known)
5. **Suggested fix** (if you have one)

### What to Expect

- **Acknowledgement** within **48 hours**
- **Initial assessment** within **5 business days**
- **Fix or mitigation** within **30 days** for critical/high severity
- **Credit** in the release notes (unless you prefer anonymity)

---

## Security Design Principles

| Principle | Implementation |
|:----------|:---------------|
| **Credentials never logged** | All log statements use parameterised SLF4J patterns — credential fields are never passed to `log.*()` |
| **Credentials not stored** | Cloud SDK clients are built once at startup; credentials exist only in SDK-managed memory |
| **HTTPS by default** | All cloud SDK calls use HTTPS; `http://` endpoints trigger a startup `WARN` |
| **No credentials in URLs** | Credential material is passed exclusively via SDK credential provider chains, never as URL parameters |
| **POSIX data minimisation** | The `storage_posix_metadata` table stores paths and sizes — never file content |
| **Secret resolution decoupled** | `secret://` references are resolved by the Secret Manager Starter before `StorageProperties` is bound — Unified Storage never sees the vault |

For a detailed overview, see [docs/security.md](docs/security.md).

---

## Scope

This policy covers the Unified Storage library itself. It does **not** cover:

- Vulnerabilities in upstream cloud SDKs (AWS SDK v2, Google Cloud Storage SDK)
- Vulnerabilities in the Java runtime or Spring Framework
- Misconfiguration by end users (e.g., using `http://` endpoints in production)
- The vault system referenced by `secret://` credentials

When in doubt, report it — we'd rather investigate and close than miss a real issue.
