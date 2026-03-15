import { HttpRequest } from "@azure/functions";
import { getPrisma } from "./prisma.js";

/**
 * Writes an audit log entry to the database.
 *
 * This is fire-and-forget: errors are logged to console but never thrown,
 * and the caller should not await the returned promise unless it needs
 * confirmation that the log was written.
 */
export function logAudit(
  uploadId: string,
  appId: string,
  action: string,
  details?: Record<string, unknown>,
  request?: HttpRequest,
): void {
  const ipAddress = request?.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? null;
  const userAgent = request?.headers.get("user-agent") ?? null;

  const prisma = getPrisma();

  // Fire and forget — don't block the response
  prisma.auditLog
    .create({
      data: {
        uploadId,
        appId,
        action,
        details: details ? JSON.stringify(details) : undefined,
        ipAddress,
        userAgent,
      },
    })
    .catch((err: unknown) => {
      console.error("[audit] Failed to write audit log:", err);
    });
}
