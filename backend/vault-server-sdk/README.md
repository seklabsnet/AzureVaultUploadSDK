# @pallasite/vault-server-sdk

Server-side SDK for receiving real-time upload events from AzureVaultUploadSDK. No webhooks, no endpoints, no firewall rules.

## Install

```bash
npm install @pallasite/vault-server-sdk
```

## Quick Start

```typescript
import { VaultServerSDK } from '@pallasite/vault-server-sdk';

const vault = new VaultServerSDK({
  appId: process.env.VAULT_APP_ID,
  clientSecret: process.env.VAULT_CLIENT_SECRET,
  grantSecret: process.env.UPLOAD_GRANT_SECRET,
});

// Grant endpoint — mobile calls this before upload
app.post('/api/upload/start', auth, (req, res) => {
  const grant = vault.createGrant({
    userId: req.user.id,
    entityType: req.body.entityType,
  });
  res.json({ grant });
});

// Upload completion — grant is verified by SDK, event.grant is safe
vault.on('upload.completed', async (event) => {
  if (!event.grant) return;
  await db.files.create({
    data: {
      fileId: event.fileId,
      userId: event.grant.userId,
      entityType: event.grant.entityType,
      url: event.downloadUrl,
    },
  });
});

await vault.connect();
```

## How It Works

```
Mobile App                Your Backend              VaultSDK Backend       Azure Blob
──────────                ────────────              ────────────────       ──────────

POST /api/upload/start ──>  vault.createGrant()
                         <── { grant: "eyJ..." }

SDK.upload(file, metadata: { "x-upload-grant": grant })
                              ──────────────────────> initiate, SAS token
                                                             │
PUT blob (direct upload) ────────────────────────────────────────────────> Azure Blob
                                                             │
POST /complete ──────────────────────────────────────> commit, buffer event
                                                             │
                            GET /v1/events/poll  <───────────┘
                            (SDK polls automatically)

                            vault.on('upload.completed'):
                              event.grant.userId ✓
                              event.fileId ✓
                              → save to DB
```

Your backend never exposes an endpoint. SDK polls outbound. Grant ensures you know **who** uploaded **what**.

## Configuration

```typescript
const vault = new VaultServerSDK({
  // Required
  appId: 'my-app',                              // From app registration
  clientSecret: 'cvlt_my-app_abc123',            // From app registration
  grantSecret: 'min-32-chars-secret',            // Your own secret for grant signing

  // Optional — Transport
  baseUrl: 'https://vault-api.example.com/api',  // VaultSDK Backend URL
  redis: {                                        // For instant delivery (ms vs 2-10s)
    url: 'rediss://my-redis:6380',
    password: 'redis-password',
  },

  // Optional — Behavior
  stateFile: './vault-state.json',                // Persist lastEventId across restarts
  maxReconnectAttempts: Infinity,
  reconnectBaseDelay: 1000,                       // ms
  reconnectMaxDelay: 30000,                       // ms
  logger: customLogger,                           // Default: console
});
```

## Transport Modes

| Mode | Config | Latency | Cost |
|------|--------|---------|------|
| **Adaptive Polling** (default) | No extra config | 2-10s | $0 |
| **Redis Pub/Sub** (optional) | `redis: { url, password }` | Milliseconds | $0 (your Redis) |

SDK auto-falls back from Redis to polling if Redis disconnects.

### Adaptive Polling Intervals

| State | Interval | When |
|-------|----------|------|
| Active | 2s | Event received within last 30s |
| Normal | 5s | Event received within last 5 min |
| Idle | 10s | No events for 5+ min |

## Grant System

Grants solve the identity problem: when an upload completes, how does your backend know **which user** uploaded the file?

```typescript
// 1. Your backend creates a grant (server-side, userId is trusted)
const grant = vault.createGrant({
  userId: req.user.id,       // From your auth system
  entityType: 'avatar',
  data: { role: 'pilot' },   // Optional extra data
});

// 2. Mobile passes grant as metadata to upload SDK
uploadVM.upload(file, "avatar", userId, customMetadata: { "x-upload-grant": grant })

// 3. Upload completes → SDK verifies grant → callback receives verified identity
vault.on('upload.completed', (event) => {
  event.grant.userId;      // "user_123" — verified, safe
  event.grant.entityType;  // "avatar" — verified, safe
  event.grant.data;        // { role: "pilot" } — from createGrant
});
```

Grants are signed JWTs (HS256). SDK verifies signature + enforces one-time use. No Redis, no nonce tables — SDK handles it.

## Events

```typescript
vault.on('upload.completed', async (event) => {
  event.fileId;           // Unique file identifier
  event.downloadUrl;      // CDN or SAS URL
  event.fileName;         // Original file name
  event.fileSize;         // Bytes
  event.mimeType;         // MIME type
  event.entityType;       // From upload metadata
  event.entityId;         // From upload metadata
  event.isPublic;         // Public or private file
  event.completedAt;      // Date
  event.grant;            // { userId, entityType, grantId, data } | null
});

vault.on('upload.failed', async (event) => {
  event.uploadId;
  event.reason;           // 'malware' | 'timeout' | 'error' | 'quota_exceeded'
  event.errorMessage;
  event.grant;            // | null
});

vault.on('upload.cancelled', async (event) => {
  event.uploadId;
  event.grant;            // | null
});

vault.on('upload.processed', async (event) => {
  event.fileId;
  event.blurHash;         // BlurHash string for images | null
});

// Lifecycle
vault.on('connected', () => {});
vault.on('disconnected', (reason) => {});
vault.on('reconnecting', (attempt) => {});
vault.on('error', (err) => {});

// Monitoring
vault.on('callback.error', ({ eventId, eventType, error }) => {});
vault.on('grant.missing', ({ eventId, uploadId }) => {});
vault.on('grant.invalid', ({ eventId, uploadId }) => {});
```

## API Client

Server-to-server operations with automatic JWT auth:

```typescript
const upload = await vault.api.initiateUpload({
  fileName: 'report.pdf',
  fileSize: 1048576,
  mimeType: 'application/pdf',
  entityType: 'document',
  entityId: 'doc-123',
});

const status = await vault.api.getUploadStatus('upload-id');
await vault.api.cancelUpload('upload-id');
const download = await vault.api.getDownloadUrl('file-id');
const list = await vault.api.listUploads({ status: 'COMPLETED', limit: 20 });
```

## NestJS Integration

```typescript
@Injectable()
export class VaultService implements OnModuleInit, OnModuleDestroy {
  private vault: VaultServerSDK;

  constructor(private config: ConfigService, private prisma: PrismaService) {
    this.vault = new VaultServerSDK({
      appId: this.config.get('VAULT_APP_ID'),
      clientSecret: this.config.get('VAULT_CLIENT_SECRET'),
      grantSecret: this.config.get('UPLOAD_GRANT_SECRET'),
    });
  }

  createGrant(userId: string, entityType: string) {
    return this.vault.createGrant({ userId, entityType });
  }

  async onModuleInit() {
    this.vault.on('upload.completed', async (event) => {
      if (!event.grant) return;
      await this.prisma.user.update({
        where: { id: event.grant.userId },
        data: { avatarUrl: event.downloadUrl },
      });
    });
    await this.vault.connect();
  }

  async onModuleDestroy() {
    await this.vault.disconnect();
  }
}
```

## Callback Error Handling

SDK wraps callbacks in try-catch. If your callback throws:
1. SDK emits `callback.error` event
2. `lastEventId` advances — event is considered delivered
3. SDK continues processing

Implement your own retry/dead-letter inside the callback:

```typescript
vault.on('upload.completed', async (event) => {
  try {
    await db.files.create({ fileId: event.fileId, ... });
  } catch (err) {
    await deadLetterQueue.push({ event, error: err });
  }
});
```

## Multi-Instance Deployments

If you run multiple backend instances, each runs its own SDK and receives all events. Use a DB unique constraint to prevent duplicate processing:

```sql
ALTER TABLE files ADD CONSTRAINT files_file_id_unique UNIQUE (file_id);
```

## Security

| Layer | Protection |
|-------|-----------|
| Transport | TLS (outbound HTTPS only) |
| Auth | JWT Bearer token, auto-refreshed every ~13 min |
| Scope | Events scoped per appId — no cross-app leakage |
| Event signing | HMAC-SHA256 per event, verified by SDK |
| Grant signing | HS256 JWT with your grantSecret |
| One-time grant | In-memory tracking (per-instance) + DB unique constraint |
| Replay protection | Monotonic event IDs (ULID-based) |
| No inbound surface | SDK makes outbound calls only — no DDoS attack vector |

## Spec

Full technical specification: [`centauri-docs/specs/vault-server-sdk-spec-v1.0.md`](../../../centauri-docs/specs/vault-server-sdk-spec-v1.0.md)
