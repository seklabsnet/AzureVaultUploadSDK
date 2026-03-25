import type {
  VaultServerSDKConfig,
  VaultEventMap,
  CreateGrantOptions,
  PollEvent,
  Logger,
  UploadCompletedEvent,
  UploadFailedEvent,
  UploadCancelledEvent,
  UploadProcessedEvent,
} from "./types.js";
import { TokenManager } from "./tokenManager.js";
import { GrantManager } from "./grantManager.js";
import { HybridTransport } from "./hybridTransport.js";
import { APIClient } from "./apiClient.js";
import { StatePersistence } from "./statePersistence.js";

const DEFAULT_BASE_URL = "https://vault-api.pallasite.com/api";

type EventCallback<T> = (event: T) => void | Promise<void>;

/**
 * VaultServerSDK — Server-side SDK for receiving upload events from VaultSDK Backend.
 *
 * Features:
 * - Hybrid transport: adaptive polling (default) + optional Redis Pub/Sub
 * - Built-in grant system (createGrant + auto-verify)
 * - Typed event callbacks
 * - Server-to-server API client
 * - Automatic authentication + token refresh
 * - State persistence for crash recovery
 */
export class VaultServerSDK {
  private readonly config: Required<
    Pick<VaultServerSDKConfig, "appId" | "clientSecret" | "grantSecret" | "baseUrl">
  > & VaultServerSDKConfig;
  private readonly logger: Logger;
  private readonly tokenManager: TokenManager;
  private readonly grantManager: GrantManager;
  private readonly statePersistence: StatePersistence;
  private readonly transport: HybridTransport;

  /** Typed API client for server-to-server operations */
  public readonly api: APIClient;

  /** Whether the SDK is actively polling/subscribed */
  public get isConnected(): boolean {
    return this.transport.isConnected;
  }

  // Event listeners
  private listeners = new Map<string, Set<EventCallback<unknown>>>();

  constructor(config: VaultServerSDKConfig) {
    if (!config.appId) throw new Error("VaultServerSDK: appId is required");
    if (!config.clientSecret) throw new Error("VaultServerSDK: clientSecret is required");
    if (!config.grantSecret) throw new Error("VaultServerSDK: grantSecret is required");
    if (config.grantSecret.length < 32) throw new Error("VaultServerSDK: grantSecret must be at least 32 characters");

    const baseUrl = config.baseUrl ?? DEFAULT_BASE_URL;
    this.config = { ...config, appId: config.appId, clientSecret: config.clientSecret, grantSecret: config.grantSecret, baseUrl };
    this.logger = config.logger ?? console;

    this.tokenManager = new TokenManager(baseUrl, config.appId, config.clientSecret, this.logger);
    this.grantManager = new GrantManager(config.grantSecret, this.logger);
    this.statePersistence = new StatePersistence(config.stateFile ?? null, this.logger);
    this.api = new APIClient(baseUrl, this.tokenManager);

    this.transport = new HybridTransport(
      baseUrl,
      this.tokenManager,
      config.clientSecret,
      config.redis,
      this.logger,
      config.appId,
      (events) => this.handleEvents(events),
      () => this.emit("connected", undefined as never),
      (reason) => this.emit("disconnected", reason),
      (attempt) => this.emit("reconnecting", attempt),
      (error) => this.emit("error", error),
      (eventId, eventType) => this.emit("event.signature_invalid", { eventId, eventType }),
    );
  }

  // ── Grant ──

  /**
   * Create a signed upload grant.
   * Call this from your grant endpoint (e.g., POST /api/upload/start).
   * The grant is passed as metadata to the upload SDK and returns verified in the callback.
   */
  createGrant(options: CreateGrantOptions): string {
    return this.grantManager.createGrant(options);
  }

  // ── Event Listeners ──

  /**
   * Register a callback for an event type.
   * Callbacks are typed — event payload matches the event type.
   */
  on<K extends keyof VaultEventMap>(event: K, callback: EventCallback<VaultEventMap[K]>): void {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set());
    }
    this.listeners.get(event)!.add(callback as EventCallback<unknown>);
  }

  off<K extends keyof VaultEventMap>(event: K, callback: EventCallback<VaultEventMap[K]>): void {
    this.listeners.get(event)?.delete(callback as EventCallback<unknown>);
  }

  private emit<K extends keyof VaultEventMap>(event: K, data: VaultEventMap[K]): void {
    const callbacks = this.listeners.get(event);
    if (!callbacks) return;
    for (const cb of callbacks) {
      try {
        cb(data);
      } catch {
        // Lifecycle events — swallow errors
      }
    }
  }

  // ── Connection ──

  /**
   * Start listening for upload events.
   * Establishes polling or Redis connection based on config.
   */
  async connect(): Promise<void> {
    // Load persisted state
    const lastEventId = await this.statePersistence.load();
    this.transport.setLastEventId(lastEventId);

    // Start transport
    await this.transport.start();
  }

  /**
   * Gracefully stop listening.
   */
  async disconnect(): Promise<void> {
    await this.transport.stop();
    this.emit("disconnected", "manual disconnect");
  }

  // ── Internal Event Processing ──

  private async handleEvents(events: PollEvent[]): Promise<void> {
    for (const rawEvent of events) {
      const eventType = rawEvent.type as keyof VaultEventMap;
      const payload = rawEvent.payload as Record<string, unknown>;

      // Extract and verify grant from metadata
      const metadata = payload.metadata as Record<string, string> | null;
      const grantToken = metadata?.["x-upload-grant"];

      const grant = this.grantManager.verifyGrant(grantToken, rawEvent.id, payload.uploadId as string);

      // Emit grant events for monitoring
      if (!grantToken) {
        this.emit("grant.missing", { eventId: rawEvent.id, uploadId: payload.uploadId as string, reason: "no x-upload-grant in metadata" });
      } else if (!grant && grantToken) {
        this.emit("grant.invalid", { eventId: rawEvent.id, uploadId: payload.uploadId as string, reason: "signature invalid or expired" });
      }

      // Build typed event — cast from unknown payload fields
      const s = (key: string) => (payload[key] as string) ?? "";
      const n = (key: string) => (payload[key] as number) ?? 0;
      const b = (key: string) => (payload[key] as boolean) ?? false;

      let typedEvent: unknown;

      switch (eventType) {
        case "upload.completed":
          typedEvent = {
            eventId: rawEvent.id,
            uploadId: s("uploadId"),
            fileId: s("fileId"),
            appId: s("appId"),
            fileName: s("fileName"),
            fileSize: n("fileSize"),
            mimeType: s("mimeType"),
            entityType: s("entityType"),
            entityId: s("entityId"),
            status: "COMPLETED" as const,
            downloadUrl: s("downloadUrl"),
            isPublic: b("isPublic"),
            metadata,
            completedAt: payload.completedAt ? new Date(payload.completedAt as string) : new Date(),
            grant,
          } as UploadCompletedEvent;
          break;

        case "upload.failed":
          typedEvent = {
            eventId: rawEvent.id,
            uploadId: s("uploadId"),
            appId: s("appId"),
            fileName: s("fileName"),
            fileSize: n("fileSize"),
            entityType: s("entityType"),
            entityId: s("entityId"),
            status: "FAILED" as const,
            reason: s("errorMessage").includes("Malware") ? "malware" as const : "error" as const,
            errorMessage: s("errorMessage") || "Unknown error",
            metadata,
            grant,
          } as UploadFailedEvent;
          break;

        case "upload.cancelled":
          typedEvent = {
            eventId: rawEvent.id,
            uploadId: s("uploadId"),
            appId: s("appId"),
            fileName: s("fileName"),
            entityType: s("entityType"),
            entityId: s("entityId"),
            status: "CANCELLED" as const,
            metadata,
            grant,
          } as UploadCancelledEvent;
          break;

        case "upload.processed":
          typedEvent = {
            eventId: rawEvent.id,
            uploadId: s("uploadId"),
            fileId: s("fileId"),
            appId: s("appId"),
            blurHash: (payload.blurHash as string) ?? null,
            processingStatus: (payload.processingStatus as string as "COMPLETED" | "FAILED") ?? "COMPLETED",
          } as UploadProcessedEvent;
          break;

        default:
          this.logger.warn(`[VaultServerSDK] Unknown event type: ${eventType}`);
          continue;
      }

      // Deliver to callbacks with error handling
      const callbacks = this.listeners.get(eventType);
      if (callbacks) {
        for (const cb of callbacks) {
          try {
            await cb(typedEvent);
          } catch (err) {
            this.emit("callback.error", {
              eventId: rawEvent.id,
              eventType: rawEvent.type,
              error: err instanceof Error ? err : new Error(String(err)),
            });
          }
        }
      }

      // Advance lastEventId (event considered delivered regardless of callback success)
      this.transport.setLastEventId(rawEvent.id);
      await this.statePersistence.setLastEventId(rawEvent.id);
    }
  }
}
