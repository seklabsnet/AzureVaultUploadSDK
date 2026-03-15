import { RateLimitError } from "../shared/errors.js";

export { RateLimitError } from "../shared/errors.js";

export interface AppConfig {
  maxRequestsPerUser: number;
  maxRequestsPerApp: number;
  windowMs: number;
}

/** Sliding window entries: key -> list of request timestamps */
const windows = new Map<string, number[]>();

/** Interval handle for periodic cleanup */
let cleanupInterval: ReturnType<typeof setInterval> | null = null;
const CLEANUP_INTERVAL_MS = 60_000;

function ensureCleanupScheduled(): void {
  if (cleanupInterval) return;

  cleanupInterval = setInterval(() => {
    const now = Date.now();
    for (const [key, timestamps] of windows.entries()) {
      // Remove entries where all timestamps are expired (older than the largest
      // reasonable window — 15 min). Individual checks also prune per-key.
      const cutoff = now - 15 * 60 * 1000;
      const valid = timestamps.filter((t) => t > cutoff);
      if (valid.length === 0) {
        windows.delete(key);
      } else {
        windows.set(key, valid);
      }
    }
  }, CLEANUP_INTERVAL_MS);

  // Allow the Node.js process to exit even if the interval is active
  if (cleanupInterval && typeof cleanupInterval === "object" && "unref" in cleanupInterval) {
    cleanupInterval.unref();
  }
}

/**
 * Checks whether the given key has exceeded its rate limit within the
 * sliding window.
 *
 * @returns `true` if the request is allowed, `false` if rate-limited.
 */
export function checkRateLimit(key: string, maxRequests: number, windowMs: number): boolean {
  ensureCleanupScheduled();

  const now = Date.now();
  const cutoff = now - windowMs;

  let timestamps = windows.get(key);
  if (!timestamps) {
    timestamps = [];
    windows.set(key, timestamps);
  }

  // Prune expired timestamps for this key
  const valid = timestamps.filter((t) => t > cutoff);
  windows.set(key, valid);

  if (valid.length >= maxRequests) {
    return false;
  }

  valid.push(now);
  return true;
}

/**
 * Rate-limit middleware that checks both per-user and per-app limits.
 * Throws `RateLimitError` if either limit is exceeded.
 */
export function rateLimitMiddleware(appId: string, userId: string, appConfig: AppConfig): void {
  const userKey = `user:${appId}:${userId}`;
  const appKey = `app:${appId}`;

  const userAllowed = checkRateLimit(userKey, appConfig.maxRequestsPerUser, appConfig.windowMs);
  if (!userAllowed) {
    const retryAfter = Math.ceil(appConfig.windowMs / 1000);
    throw new RateLimitError(retryAfter);
  }

  const appAllowed = checkRateLimit(appKey, appConfig.maxRequestsPerApp, appConfig.windowMs);
  if (!appAllowed) {
    const retryAfter = Math.ceil(appConfig.windowMs / 1000);
    throw new RateLimitError(retryAfter);
  }
}
