import crypto from "crypto";
import type { Logger, PollEvent, PollResponse } from "./types.js";
import type { TokenManager } from "./tokenManager.js";

type EventHandler = (events: PollEvent[]) => Promise<void>;

/**
 * Hybrid transport: adaptive polling (default) + optional Redis Pub/Sub.
 * If Redis is configured and connected, events arrive via Pub/Sub (milliseconds).
 * If Redis is not configured or disconnects, adaptive polling takes over (2-10s).
 */
export class HybridTransport {
  private running = false;
  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  private lastEventAt = 0;
  private lastEventId: string | null = null;
  private redisSubscriber: { unsubscribe: () => Promise<void> } | null = null;
  private redisConnected = false;

  /** SHA256(clientSecret) — used to verify event signatures */
  private readonly verificationKey: string;

  constructor(
    private readonly baseUrl: string,
    private readonly tokenManager: TokenManager,
    private readonly clientSecret: string,
    private readonly redisConfig: { url: string; password?: string } | undefined,
    private readonly logger: Logger,
    private readonly appId: string,
    private onEvents: EventHandler,
    private onConnected: () => void,
    private onDisconnected: (reason: string) => void,
    private onReconnecting: (attempt: number) => void,
    private onError: (error: Error) => void,
    private onSignatureInvalid: (eventId: string, eventType: string) => void,
  ) {
    this.verificationKey = crypto.createHash("sha256").update(clientSecret).digest("hex");
  }

  get isConnected(): boolean {
    return this.running;
  }

  setLastEventId(id: string | null): void {
    this.lastEventId = id;
  }

  async start(): Promise<void> {
    this.running = true;

    // Try Redis first
    if (this.redisConfig) {
      await this.startRedis();
    }

    // Always start polling (as primary or fallback)
    if (!this.redisConnected) {
      this.startPolling();
    }

    this.onConnected();
    this.logger.log(
      `[VaultServerSDK] Transport: ${this.redisConnected ? `Redis Pub/Sub (${this.redisConfig!.url})` : `Adaptive Polling (${this.baseUrl})`}`,
    );
  }

  async stop(): Promise<void> {
    this.running = false;

    if (this.pollTimer) {
      clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }

    if (this.redisSubscriber) {
      await this.redisSubscriber.unsubscribe().catch(() => {});
      this.redisSubscriber = null;
    }
  }

  // ── Redis Pub/Sub ──

  private async startRedis(): Promise<void> {
    if (!this.redisConfig) return;

    try {
      const { createClient } = await import("redis");
      const client = createClient({
        url: this.redisConfig.url,
        password: this.redisConfig.password,
      });

      client.on("error", (err: Error) => {
        this.logger.error(`[VaultServerSDK] Redis error: ${err.message}`);
        if (this.redisConnected) {
          this.redisConnected = false;
          this.logger.warn("[VaultServerSDK] Redis disconnected, falling back to polling");
          this.startPolling();
        }
      });

      client.on("reconnecting", () => {
        this.logger.log("[VaultServerSDK] Redis reconnecting...");
      });

      await client.connect();

      const channel = `vault:events:${this.appId}`;
      await client.subscribe(channel, (message: string) => {
        this.handleRedisMessage(message);
      });

      this.redisConnected = true;
      this.redisSubscriber = {
        unsubscribe: async () => {
          await client.unsubscribe(channel).catch(() => {});
          await client.disconnect().catch(() => {});
        },
      };

      this.logger.log(`[VaultServerSDK] Redis subscribed to ${channel}`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      this.logger.warn(`[VaultServerSDK] Redis connection failed: ${msg}. Using polling.`);
      this.redisConnected = false;
    }
  }

  private handleRedisMessage(message: string): void {
    try {
      const event = JSON.parse(message) as PollEvent;

      // Verify signature
      if (!this.verifySignature(event)) {
        this.onSignatureInvalid(event.id, event.type);
        return;
      }

      // Dedup: skip if already seen
      if (this.lastEventId && event.id <= this.lastEventId) return;

      this.lastEventAt = Date.now();
      this.onEvents([event]).catch((err) => {
        this.logger.error(`[VaultServerSDK] Event handler error:`, err);
      });
    } catch (err) {
      this.logger.error(`[VaultServerSDK] Redis message parse error:`, err);
    }
  }

  // ── Adaptive Polling ──

  private startPolling(): void {
    if (!this.running) return;
    this.schedulePoll();
  }

  private stopPolling(): void {
    if (this.pollTimer) {
      clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }

  private schedulePoll(): void {
    if (!this.running) return;

    const interval = this.getAdaptiveInterval();
    this.pollTimer = setTimeout(() => this.poll(), interval);
  }

  private getAdaptiveInterval(): number {
    const idleTime = Date.now() - this.lastEventAt;

    if (idleTime < 30_000) return 2_000;   // Active: event within last 30s → 2s
    if (idleTime < 300_000) return 5_000;  // Normal: event within last 5min → 5s
    return 10_000;                          // Idle: no events for 5min+ → 10s
  }

  private async poll(): Promise<void> {
    if (!this.running) return;

    try {
      const token = await this.tokenManager.getToken();
      const params = new URLSearchParams();
      if (this.lastEventId) params.set("since", this.lastEventId);

      const url = `${this.baseUrl}/v1/events/poll${params.toString() ? `?${params}` : ""}`;
      const response = await fetch(url, {
        headers: { Authorization: `Bearer ${token}` },
      });

      if (response.status === 401) {
        this.tokenManager.invalidate();
        // Will re-auth on next poll
      }

      if (!response.ok) {
        throw new Error(`Poll failed: HTTP ${response.status}`);
      }

      const result = (await response.json()) as PollResponse;
      const events = result.data.events;

      if (events.length > 0) {
        // Verify signatures and filter invalid
        const validEvents = events.filter((e) => {
          if (!this.verifySignature(e)) {
            this.onSignatureInvalid(e.id, e.type);
            return false;
          }
          return true;
        });

        if (validEvents.length > 0) {
          this.lastEventAt = Date.now();
          await this.onEvents(validEvents);
        }
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      this.logger.error(`[VaultServerSDK] Poll error: ${msg}`);
    }

    // Schedule next poll
    this.schedulePoll();
  }

  // ── Signature Verification ──

  private verifySignature(event: PollEvent): boolean {
    try {
      const expectedMessage = `${event.id}:${event.type}:${JSON.stringify(event.payload)}`;
      const expectedSignature = `sha256=${crypto.createHmac("sha256", this.verificationKey).update(expectedMessage).digest("hex")}`;
      return event.signature === expectedSignature;
    } catch {
      return false;
    }
  }
}
