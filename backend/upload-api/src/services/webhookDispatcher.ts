import crypto from "crypto";
import { ensurePrisma } from "../shared/prisma.js";

const MAX_RETRIES = 3;
const RETRY_DELAYS = [1000, 2000, 4000]; // 1s, 2s, 4s — exponential backoff
const TIMEOUT_MS = 10_000;

interface WebhookBody {
  event: "upload.completed";
  uploadId: string;
  fileId: string;
  appId: string;
  fileName: string;
  fileSize: number;
  mimeType: string | null;
  entityType: string;
  entityId: string;
  completedAt: string;
}

function signPayload(body: string, secret: string): string {
  return crypto.createHmac("sha256", secret).update(body).digest("hex");
}

/**
 * Fire-and-forget webhook call after upload completion.
 * POST to app's webhookUrl with upload details.
 * Retry 3 times with exponential backoff on failure.
 * Log success/failure but never block the response.
 */
export function dispatchWebhook(
  appId: string,
  uploadId: string,
  fileId: string,
  metadata: {
    fileName: string;
    fileSize: number;
    mimeType: string | null;
    entityType: string;
    entityId: string;
    completedAt: Date;
  },
): void {
  // Fire and forget — don't await
  doDispatch(appId, uploadId, fileId, metadata).catch(() => {
    // Swallow — already logged inside
  });
}

async function doDispatch(
  appId: string,
  uploadId: string,
  fileId: string,
  metadata: {
    fileName: string;
    fileSize: number;
    mimeType: string | null;
    entityType: string;
    entityId: string;
    completedAt: Date;
  },
): Promise<void> {
  const prisma = await ensurePrisma();

  // Read AppConfig from DB to get webhookUrl
  const appConfig = await prisma.appConfig.findUnique({
    where: { appId },
  });

  // If webhookUrl is null or not set, skip silently
  if (!appConfig?.webhookUrl) {
    return;
  }

  const payload: WebhookBody = {
    event: "upload.completed",
    uploadId,
    fileId,
    appId,
    fileName: metadata.fileName,
    fileSize: metadata.fileSize,
    mimeType: metadata.mimeType,
    entityType: metadata.entityType,
    entityId: metadata.entityId,
    completedAt: metadata.completedAt.toISOString(),
  };

  // Derive signing key from clientSecretHash
  const signingKey = appConfig.clientSecretHash;

  await sendWithRetry(appConfig.webhookUrl, payload, signingKey, 0);
}

async function sendWithRetry(
  url: string,
  payload: WebhookBody,
  signingKey: string,
  attempt: number,
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
      },
      body,
      signal: controller.signal,
    });

    clearTimeout(timeout);

    if (response.ok) {
      console.log(`[webhookDispatcher] Delivered upload.completed to ${url} (attempt ${attempt + 1})`);
      return;
    }

    // Non-retryable client errors (4xx except 429)
    if (response.status >= 400 && response.status < 500 && response.status !== 429) {
      console.error(`[webhookDispatcher] Rejected by ${url}: HTTP ${response.status} (not retrying)`);
      return;
    }

    throw new Error(`HTTP ${response.status}`);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);

    if (attempt < MAX_RETRIES) {
      const delay = RETRY_DELAYS[attempt] ?? 4000;
      console.log(`[webhookDispatcher] Retry ${attempt + 1}/${MAX_RETRIES} for ${url} in ${delay}ms (${msg})`);
      await sleep(delay);
      return sendWithRetry(url, payload, signingKey, attempt + 1);
    }

    console.error(`[webhookDispatcher] Failed after ${MAX_RETRIES} retries to ${url}: ${msg}`);
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
