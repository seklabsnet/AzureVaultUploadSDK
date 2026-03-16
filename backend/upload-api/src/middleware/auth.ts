import { HttpRequest } from "@azure/functions";
import jwt from "jsonwebtoken";
import { AuthError } from "../shared/errors.js";

export interface AuthContext {
  appId: string;
}

function getJwtSecret(): string {
  const secret = process.env.JWT_SIGNING_SECRET;
  if (!secret) {
    throw new Error("JWT_SIGNING_SECRET environment variable is not set");
  }
  return secret;
}

/**
 * Authenticates an incoming HTTP request by verifying the JWT signature
 * using HMAC-SHA256 and extracting the appId claim.
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
    const payload = jwt.verify(token, getJwtSecret(), {
      algorithms: ["HS256"],
    }) as jwt.JwtPayload;

    const appId = payload.appId;
    if (!appId || typeof appId !== "string") {
      throw new AuthError("Token missing required claim: appId");
    }

    return { appId };
  } catch (err) {
    if (err instanceof AuthError) {
      throw err;
    }
    if (err instanceof jwt.TokenExpiredError) {
      throw new AuthError("Token has expired. Request a new one via POST /v1/auth/token");
    }
    if (err instanceof jwt.JsonWebTokenError) {
      throw new AuthError("Invalid token signature");
    }
    throw new AuthError("Invalid or malformed token");
  }
}

export { AuthError } from "../shared/errors.js";
