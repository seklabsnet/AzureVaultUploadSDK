import crypto from "crypto";
import { ensurePrisma } from "./prisma.js";
import { WebhookPayload } from "./webhook.js";

/**
 * Generate a sortable, globally unique event ID using timestamp + random.
 * Format: evt_{timestamp_hex}_{random_hex}
 * Sortable by timestamp, unique across instances.
 */
export function generateEventId(): string {
  const timestamp = Date.now().toString(16).padStart(12, "0");
  const random = crypto.randomBytes(8).toString("hex");
  return `evt_${timestamp}_${random}`;
}

/**
 * Sign an event using HMAC-SHA256.
 * Key: clientSecretHash (SHA256 of client secret).
 * Message: "{eventId}:{eventType}:{JSON.stringify(payload)}"
 */
export function signEvent(
  eventId: string,
  eventType: string,
  payload: WebhookPayload,
  clientSecretHash: string,
): string {
  const message = `${eventId}:${eventType}:${JSON.stringify(payload)}`;
  return `sha256=${crypto.createHmac("sha256", clientSecretHash).update(message).digest("hex")}`;
}

/**
 * Buffer an event for polling clients.
 * Events expire after 24 hours. Cleanup runs hourly via timer trigger.
 */
export async function bufferEvent(
  appId: string,
  eventId: string,
  eventType: string,
  payload: WebhookPayload,
  signature: string,
): Promise<void> {
  const prisma = await ensurePrisma();
  await prisma.eventBuffer.create({
    data: {
      id: eventId,
      appId,
      eventType,
      payload: payload as any,
      signature,
      expiresAt: new Date(Date.now() + 24 * 60 * 60 * 1000),
    },
  });
}

/**
 * Dispatch an event to all configured channels: EventBuffer (always), Redis (optional), Webhook (optional).
 * All channels are independent — failure in one does not affect others.
 */
export async function dispatchEvent(
  appId: string,
  eventType: string,
  payload: WebhookPayload,
  appConfig: { clientSecretHash: string; webhookUrl?: string | null; redisUrl?: string | null; redisPassword?: string | null },
  logger?: { log: (...args: unknown[]) => void; error: (...args: unknown[]) => void },
): Promise<{ eventId: string; signature: string }> {
  const eventId = generateEventId();
  const signature = signEvent(eventId, eventType, payload, appConfig.clientSecretHash);

  // 1. ALWAYS buffer the event (for polling + replay)
  await bufferEvent(appId, eventId, eventType, payload, signature);
  logger?.log(`[eventBuffer] Buffered ${eventType} for ${appId}: ${eventId}`);

  // 2. Redis Pub/Sub — instant delivery (if customer provided Redis config)
  if (appConfig.redisUrl) {
    publishToRedis(
      appConfig.redisUrl,
      appConfig.redisPassword ?? undefined,
      appId,
      { id: eventId, type: eventType, payload, signature },
      logger,
    );
  }

  // 3. Webhook — backward compatible (if webhookUrl configured)
  // Note: webhook dispatch is handled by the caller (existing code)

  return { eventId, signature };
}

// ── Redis Pub/Sub (fire-and-forget) ──

const redisClients = new Map<string, { publish: (channel: string, message: string) => Promise<void> }>();

async function getRedisPublisher(url: string, password?: string) {
  const cacheKey = url;
  if (redisClients.has(cacheKey)) {
    return redisClients.get(cacheKey)!;
  }

  // Dynamic import to avoid hard dependency on redis package
  try {
    // @ts-ignore — redis is an optional runtime dependency, not in package.json
    const { createClient } = await import("redis");
    const client = createClient({ url, password });
    client.on("error", (err: Error) => {
      console.error(`[eventBuffer] Redis error for ${url}: ${err.message}`);
    });
    await client.connect();
    const publisher = {
      publish: async (channel: string, message: string) => {
        await client.publish(channel, message);
      },
    };
    redisClients.set(cacheKey, publisher);
    return publisher;
  } catch {
    return null;
  }
}

function publishToRedis(
  redisUrl: string,
  redisPassword: string | undefined,
  appId: string,
  event: { id: string; type: string; payload: WebhookPayload; signature: string },
  logger?: { log: (...args: unknown[]) => void; error: (...args: unknown[]) => void },
): void {
  // Fire and forget
  getRedisPublisher(redisUrl, redisPassword)
    .then((client) => {
      if (!client) return;
      return client.publish(`vault:events:${appId}`, JSON.stringify(event));
    })
    .then(() => {
      logger?.log(`[eventBuffer] Redis published ${event.type} to vault:events:${appId}`);
    })
    .catch((err) => {
      logger?.error(`[eventBuffer] Redis publish failed for ${appId}: ${err instanceof Error ? err.message : err}`);
    });
}
