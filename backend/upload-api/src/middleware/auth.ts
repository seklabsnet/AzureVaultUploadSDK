import { HttpRequest } from "@azure/functions";
import { AuthError } from "../shared/errors.js";

export interface AuthContext {
  userId: string;
  appId: string;
  email?: string;
}

/**
 * Authenticates an incoming HTTP request by extracting and decoding the JWT
 * from the Authorization header.
 *
 * TODO: Production — validate JWT signature against JWKS endpoint.
 * Currently only decodes the payload without cryptographic verification.
 */
export async function authenticateRequest(request: HttpRequest): Promise<AuthContext> {
  const authHeader = request.headers.get("authorization");
  if (!authHeader) {
    throw new AuthError("Missing Authorization header");
  }

  const parts = authHeader.split(" ");
  if (parts.length !== 2 || parts[0].toLowerCase() !== "bearer") {
    throw new AuthError("Invalid Authorization header format. Expected: Bearer <token>");
  }

  const token = parts[1];

  try {
    const payload = decodeJwtPayload(token);

    const userId = payload.sub ?? payload.userId ?? payload.oid;
    const appId = payload.appId ?? payload.azp ?? payload.client_id;

    if (!userId || !appId) {
      throw new AuthError("Token missing required claims: userId (sub/oid) and appId (azp/client_id)");
    }

    return {
      userId: String(userId),
      appId: String(appId),
      email: payload.email ?? payload.preferred_username ?? undefined,
    };
  } catch (err) {
    if (err instanceof AuthError) {
      throw err;
    }
    throw new AuthError("Invalid or malformed token");
  }
}

function decodeJwtPayload(token: string): Record<string, unknown> {
  const segments = token.split(".");
  if (segments.length !== 3) {
    throw new AuthError("Token is not a valid JWT (expected 3 segments)");
  }

  const payloadSegment = segments[1];

  // Base64url decode
  const base64 = payloadSegment.replace(/-/g, "+").replace(/_/g, "/");
  const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
  const decoded = Buffer.from(padded, "base64").toString("utf-8");

  return JSON.parse(decoded) as Record<string, unknown>;
}

export { AuthError } from "../shared/errors.js";
