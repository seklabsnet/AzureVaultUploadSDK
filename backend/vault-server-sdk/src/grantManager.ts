import jwt from "jsonwebtoken";
import crypto from "crypto";
import type { CreateGrantOptions, GrantPayload, Logger } from "./types.js";

/**
 * Manages upload grant creation and verification.
 * Grants are signed JWTs that carry userId through the upload pipeline.
 * One-time-use enforcement via in-memory tracking.
 */
export class GrantManager {
  private readonly usedGrantIds = new Set<string>();
  private static readonly MAX_TRACKED = 10_000;

  constructor(
    private readonly grantSecret: string,
    private readonly logger: Logger,
  ) {}

  /**
   * Create a signed grant JWT.
   * Called from the customer's grant endpoint (e.g., POST /api/upload/start).
   */
  createGrant(options: CreateGrantOptions): string {
    const jti = crypto.randomUUID();
    const payload: Record<string, unknown> = {
      jti,
      sub: options.userId,
      ent: options.entityType,
      iss: "vault-server-sdk",
    };

    if (options.data) {
      payload.data = options.data;
    }

    return jwt.sign(payload, this.grantSecret, {
      expiresIn: 3600, // 1 hour
      header: { alg: "HS256", typ: "JWT", kid: "v1" } as jwt.JwtHeader,
    });
  }

  /**
   * Verify and decode a grant JWT from event metadata.
   * Returns null if grant is missing, invalid, expired, or replayed.
   * Uses +30 minute clock tolerance for in-progress uploads.
   */
  verifyGrant(token: string | undefined | null, eventId: string, uploadId: string): GrantPayload | null {
    if (!token) {
      this.logger.warn(`[VaultServerSDK] Grant missing for event ${eventId} (upload ${uploadId})`);
      return null;
    }

    try {
      const decoded = jwt.verify(token, this.grantSecret, {
        issuer: "vault-server-sdk",
        clockTolerance: 1800, // +30 minutes for in-progress uploads
      }) as jwt.JwtPayload;

      const jti = decoded.jti;
      if (!jti || !decoded.sub || !decoded.ent) {
        this.logger.warn(`[VaultServerSDK] Grant malformed for event ${eventId}`);
        return null;
      }

      // One-time use check
      if (this.usedGrantIds.has(jti)) {
        this.logger.warn(`[VaultServerSDK] Grant replayed: ${jti} for event ${eventId}`);
        return null;
      }

      // Track this grant as used
      this.usedGrantIds.add(jti);

      // Evict oldest if set grows too large
      if (this.usedGrantIds.size > GrantManager.MAX_TRACKED) {
        const first = this.usedGrantIds.values().next().value;
        if (first) this.usedGrantIds.delete(first);
      }

      return {
        userId: decoded.sub as string,
        entityType: decoded.ent as string,
        grantId: jti,
        data: (decoded.data as Record<string, unknown>) ?? null,
      };
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      this.logger.warn(`[VaultServerSDK] Grant invalid for event ${eventId}: ${msg}`);
      return null;
    }
  }
}
