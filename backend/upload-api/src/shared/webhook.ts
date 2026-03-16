import crypto from "crypto";

const MAX_RETRIES = 3;
const RETRY_DELAYS = [1000, 3000, 10000]; // 1s, 3s, 10s
const TIMEOUT_MS = 10_000;

export interface WebhookPayload {
  event: "upload.completed" | "upload.failed" | "upload.cancelled";
  timestamp: string;
  data: {
    uploadId: string;
    fileId: string | null;
    appId: string;
    fileName: string;
    fileSize: number;
    mimeType: string | null;
    entityType: string;
    entityId: string;
    status: string;
    downloadUrl: string | null;
    isPublic: boolean;
    completedAt: string | null;
  };
}

function signPayload(payload: string, secret: string): string {
  return crypto.createHmac("sha256", secret).update(payload).digest("hex");
}

/**
 * Dispatches a webhook to the app's configured URL.
 * Fire-and-forget with retry — does not block the caller.
 * Signs payload with HMAC-SHA256 using the app's client secret hash as key.
 */
export function dispatchWebhook(
  webhookUrl: string,
  payload: WebhookPayload,
  signingKey: string,
  logger?: { log: (...args: unknown[]) => void; error: (...args: unknown[]) => void },
): void {
  // Fire and forget — don't await
  sendWithRetry(webhookUrl, payload, signingKey, 0, logger).catch(() => {
    // Swallow — already logged inside
  });
}

async function sendWithRetry(
  url: string,
  payload: WebhookPayload,
  signingKey: string,
  attempt: number,
  logger?: { log: (...args: unknown[]) => void; error: (...args: unknown[]) => void },
): Promise<void> {
  const body = JSON.stringify(payload);
  const signature = signPayload(body, signingKey);

  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);

    const response = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Webhook-Signature": `sha256=${signature}`,
        "X-Webhook-Event": payload.event,
        "X-Webhook-Timestamp": payload.timestamp,
      },
      body,
      signal: controller.signal,
    });

    clearTimeout(timeout);

    if (response.ok) {
      logger?.log(`[webhook] Delivered ${payload.event} to ${url} (attempt ${attempt + 1})`);
      return;
    }

    // Non-retryable client errors (4xx except 429)
    if (response.status >= 400 && response.status < 500 && response.status !== 429) {
      logger?.error(`[webhook] Rejected by ${url}: HTTP ${response.status} (not retrying)`);
      return;
    }

    throw new Error(`HTTP ${response.status}`);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);

    if (attempt < MAX_RETRIES) {
      const delay = RETRY_DELAYS[attempt] ?? 10000;
      logger?.log(`[webhook] Retry ${attempt + 1}/${MAX_RETRIES} for ${url} in ${delay}ms (${msg})`);
      await sleep(delay);
      return sendWithRetry(url, payload, signingKey, attempt + 1, logger);
    }

    logger?.error(`[webhook] Failed after ${MAX_RETRIES} retries to ${url}: ${msg}`);
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
