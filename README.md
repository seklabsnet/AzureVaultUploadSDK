# AzureVaultUploadSDK

Secure, chunked file upload system for mobile and server applications. Upload files directly to Azure Blob Storage via SAS tokens — no backend proxy, no data through your servers.

## Architecture

```
                                  ┌──────────────────┐
                                  │   Azure Blob     │
                       ┌─────────>│   Storage        │
                       │  SAS     │   (direct upload) │
                       │  upload  └──────────────────┘
                       │
┌──────────────┐  initiate/   ┌──────────────────┐  events   ┌──────────────────┐
│  Client SDK  │  complete    │  VaultSDK Backend │ ────────> │ VaultServerSDK   │
│  (iOS/Android│ ───────────> │  (Azure Functions) │  poll/   │ (Customer Backend)│
│   KMP)       │              │                    │  redis   │                  │
└──────────────┘              └──────────────────┘           └──────────────────┘
```

## Packages

| Package | Path | Description |
|---------|------|-------------|
| **Client SDK** (KMP) | `upload-core/`, `upload-domain/`, `upload-network/`, `upload-platform-*` | Kotlin Multiplatform client SDK for iOS & Android. Handles chunking, retry, progress, pause/cancel. |
| **VaultSDK Backend** | `backend/upload-api/` | Azure Functions API. Upload lifecycle management, SAS token generation, webhook dispatch, event buffering, malware scanning. |
| **VaultServerSDK** | `backend/vault-server-sdk/` | Node.js/TypeScript server SDK. Receive upload events without webhooks. Built-in grant system for secure userId identity. |
| **Image Transform** | `backend/img-transform/` | Azure Function for CDN image transformations (resize, format convert). |

## Client SDK (Mobile)

Kotlin Multiplatform — shared code for iOS and Android.

### Upload Flow

```swift
// iOS
AzureVaultUpload.shared.initialize(config: UploadConfig(
    baseUrl: "https://vault-api.example.com",
    appId: "my-app",
    authProvider: { await TokenStore.shared.getToken() ?? "" },
    cdnBaseUrl: "https://cdn.example.com"
))

let uploadVM = UploadViewModel()
uploadVM.upload(
    fileRef: PlatformFile(url: fileURL),
    entityType: "avatar",
    entityId: userId,
    customMetadata: ["x-upload-grant": grant]
)
```

### Upload Strategies

| File Size | Strategy | Chunk Size | Concurrency |
|-----------|----------|------------|-------------|
| <= 4 MB | SINGLE_SHOT | — | 1 |
| <= 256 MB | CHUNKED | 4 MB | 4 |
| <= 1 GB | CHUNKED | 8 MB | 6 |
| <= 5 GB | CHUNKED | 16 MB | 8 |
| > 5 GB | CHUNKED | 32 MB | 8 |

## VaultSDK Backend

Azure Functions (Node.js v4) API for upload lifecycle management.

### Endpoints

| Method | Route | Description |
|--------|-------|-------------|
| POST | `/v1/auth/token` | Client credentials → JWT (15 min) |
| POST | `/v1/uploads/initiate` | Start upload, get SAS token |
| POST | `/v1/uploads/{uploadId}/complete` | Finalize upload, get fileId |
| GET | `/v1/uploads/{uploadId}/status` | Check upload progress |
| DELETE | `/v1/uploads/{uploadId}` | Cancel upload |
| GET | `/v1/uploads/list` | List uploads (paginated) |
| GET | `/v1/uploads/{fileId}/download-url` | Get fresh download URL |
| GET | `/v1/events/poll` | Poll for upload events (VaultServerSDK) |
| POST | `/v1/admin/apps` | Register new app |
| GET | `/v1/admin/apps` | List registered apps |
| PUT | `/v1/admin/apps/{appId}` | Update app config |

### Event Delivery (Triple Channel)

When an upload completes, VaultSDK Backend dispatches events through three independent channels:

1. **EventBuffer** (always) — PostgreSQL table, polled by VaultServerSDK, 24h retention
2. **Redis Pub/Sub** (optional) — Instant delivery for customers who provide Redis config
3. **Webhook** (optional) — Legacy HTTP POST for backward compatibility

### Setup

```bash
cd backend/upload-api
npm install
npx prisma generate
npx prisma db push
func start
```

## VaultServerSDK (Server)

Node.js/TypeScript package for receiving upload events. No webhooks, no endpoints, no firewall rules.

### Install

```bash
npm install @pallasite/vault-server-sdk
```

### Quick Start

```typescript
import { VaultServerSDK } from '@pallasite/vault-server-sdk';

const vault = new VaultServerSDK({
  appId: 'my-app',
  clientSecret: 'cvlt_my-app_abc123',
  grantSecret: process.env.UPLOAD_GRANT_SECRET!,
});

// Grant endpoint — mobile calls this before upload
app.post('/api/upload/start', auth, (req, res) => {
  const grant = vault.createGrant({
    userId: req.user.id,
    entityType: req.body.entityType,
  });
  res.json({ grant });
});

// Upload completion — SDK verifies grant, provides typed event
vault.on('upload.completed', async (event) => {
  if (!event.grant) return;
  await db.files.create({
    data: {
      fileId: event.fileId,
      userId: event.grant.userId,
      url: event.downloadUrl,
    },
  });
});

await vault.connect();
```

### Transport Modes

| Mode | Config | Latency | Cost |
|------|--------|---------|------|
| **Adaptive Polling** (default) | No extra config | 2-10s | $0 |
| **Redis Pub/Sub** (optional) | `redis: { url, password }` | Milliseconds | $0 (your Redis) |

SDK automatically falls back from Redis to polling if Redis disconnects.

### Features

- Hybrid transport (adaptive polling + optional Redis Pub/Sub)
- Built-in grant system (secure userId identity)
- Typed event callbacks
- Server-to-server API client
- Automatic JWT authentication + token refresh
- Event replay on reconnect (24h buffer)
- One-time grant use enforcement
- Callback error handling

## Security

- **SAS tokens**: User Delegation Key based, no storage account key exposed
- **Upload grants**: Server-signed JWT carries userId through upload pipeline
- **Event signing**: HMAC-SHA256 per event, verified by SDK
- **Malware scanning**: Azure Defender for Storage, auto-quarantine on detection
- **One-time grants**: In-memory tracking + DB unique constraint

## Documentation

| Document | Path |
|----------|------|
| Upload System Spec v2.0 | `centauri-docs/specs/upload-system-spec-v2.0.md` |
| VaultServerSDK Spec v1.0 | `centauri-docs/specs/vault-server-sdk-spec-v1.0.md` |
| Release Checklist | `centauri-docs/release-prep/upload-system-release-checklist.md` |
| PRD v3.0 | `AzureVault_PRD_v3.0_Complete.md` |
