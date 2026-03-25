// ── Configuration ──

export interface VaultServerSDKConfig {
  /** Application ID (from app registration) */
  appId: string;
  /** Client secret (cvlt_{appId}_{random}) */
  clientSecret: string;
  /** Secret for signing/verifying upload grants (min 32 chars) */
  grantSecret: string;

  /** VaultSDK Backend base URL */
  baseUrl?: string;
  /** Optional Redis config for instant event delivery */
  redis?: {
    /** Redis URL (rediss:// for TLS, redis:// for plain) */
    url: string;
    /** Redis password */
    password?: string;
  };
  /** Max reconnect attempts (default: Infinity) */
  maxReconnectAttempts?: number;
  /** Base delay for reconnection backoff in ms (default: 1000) */
  reconnectBaseDelay?: number;
  /** Max delay for reconnection backoff in ms (default: 30000) */
  reconnectMaxDelay?: number;
  /** File path to persist lastEventId across restarts (default: null = in-memory only) */
  stateFile?: string;
  /** Custom logger (default: console) */
  logger?: Logger;
}

export interface Logger {
  log: (...args: unknown[]) => void;
  warn: (...args: unknown[]) => void;
  error: (...args: unknown[]) => void;
}

// ── Grant ──

export interface CreateGrantOptions {
  /** User ID from your auth system */
  userId: string;
  /** Entity type (avatar, document, etc.) */
  entityType: string;
  /** Optional extra data (available in callback as event.grant.data) */
  data?: Record<string, unknown>;
}

export interface GrantPayload {
  /** User ID (from createGrant) */
  userId: string;
  /** Entity type (from createGrant) */
  entityType: string;
  /** Unique grant identifier (jti) */
  grantId: string;
  /** Extra data (from createGrant) */
  data: Record<string, unknown> | null;
}

// ── Events ──

export interface BaseEvent {
  eventId: string;
  uploadId: string;
  appId: string;
  fileName: string;
  entityType: string;
  entityId: string;
  metadata: Record<string, string> | null;
}

export interface UploadCompletedEvent extends BaseEvent {
  fileId: string;
  fileSize: number;
  mimeType: string;
  status: "COMPLETED";
  downloadUrl: string;
  isPublic: boolean;
  completedAt: Date;
  grant: GrantPayload | null;
}

export interface UploadFailedEvent extends BaseEvent {
  fileSize: number;
  status: "FAILED";
  reason: "malware" | "timeout" | "error" | "quota_exceeded";
  errorMessage: string;
  grant: GrantPayload | null;
}

export interface UploadCancelledEvent extends BaseEvent {
  status: "CANCELLED";
  grant: GrantPayload | null;
}

export interface UploadProcessedEvent {
  eventId: string;
  uploadId: string;
  fileId: string;
  appId: string;
  blurHash: string | null;
  processingStatus: "COMPLETED" | "FAILED";
}

export interface CallbackErrorEvent {
  eventId: string;
  eventType: string;
  error: Error;
}

export interface GrantEvent {
  eventId: string;
  uploadId: string;
  reason: string;
}

// ── Event Map ──

export interface VaultEventMap {
  "upload.completed": UploadCompletedEvent;
  "upload.failed": UploadFailedEvent;
  "upload.cancelled": UploadCancelledEvent;
  "upload.processed": UploadProcessedEvent;
  connected: void;
  disconnected: string;
  reconnecting: number;
  error: Error;
  "callback.error": CallbackErrorEvent;
  "grant.missing": GrantEvent;
  "grant.invalid": GrantEvent;
  "grant.replayed": GrantEvent;
  "event.signature_invalid": { eventId: string; eventType: string };
  "replay.gap": { lastEventId: string; earliestBuffered: string };
}

// ── API Client Types ──

export interface InitiateUploadOptions {
  fileName: string;
  fileSize: number;
  mimeType: string;
  entityType: string;
  entityId: string;
  isPublic?: boolean;
  metadata?: Record<string, unknown>;
}

export interface InitiateUploadResult {
  uploadId: string;
  blobUrl: string;
  sasToken: string;
  strategy: "SINGLE_SHOT" | "CHUNKED";
  maxBlockSize: number;
  chunkCount?: number;
  expiresAt: string;
}

export interface UploadStatusResult {
  uploadId: string;
  status: string;
  progress: number;
  fileId?: string;
  downloadUrl?: string;
  blurHash?: string;
}

export interface DownloadUrlResult {
  downloadUrl: string;
  contentType: string;
  fileSize: number;
  expiresAt?: string;
  blurHash?: string;
}

export interface ListUploadsOptions {
  page?: number;
  limit?: number;
  status?: string;
  entityType?: string;
}

export interface ListUploadsResult {
  uploads: Array<{
    id: string;
    fileName: string;
    fileSize: number;
    mimeType: string;
    entityType: string;
    entityId: string;
    status: string;
    progress: number;
    fileId?: string;
    downloadUrl?: string;
    blurHash?: string;
    isPublic: boolean;
    createdAt: string;
    completedAt?: string;
  }>;
  total: number;
  page: number;
  limit: number;
}

// ── Poll Response ──

export interface PollEvent {
  id: string;
  type: string;
  timestamp: string;
  payload: Record<string, unknown>;
  signature: string;
}

export interface PollResponse {
  success: boolean;
  data: {
    events: PollEvent[];
    hasMore: boolean;
  };
}

// ── Auth ──

export interface AuthTokenResponse {
  success: boolean;
  data: {
    access_token: string;
    token_type: string;
    expires_in: number;
  };
}
