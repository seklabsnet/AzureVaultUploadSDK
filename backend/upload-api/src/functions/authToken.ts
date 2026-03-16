import { app, HttpRequest, HttpResponseInit, InvocationContext } from "@azure/functions";
import { v4 as uuidv4 } from "uuid";
import jwt from "jsonwebtoken";
import crypto from "crypto";
import { getPrisma } from "../shared/prisma.js";
import { success, error } from "../shared/response.js";
import { AuthError, ValidationError, AppError } from "../shared/errors.js";

const TOKEN_EXPIRY = "15m";

function getJwtSecret(): string {
  const secret = process.env.JWT_SIGNING_SECRET;
  if (!secret) {
    throw new Error("JWT_SIGNING_SECRET environment variable is not set");
  }
  return secret;
}

function hashSecret(plain: string): string {
  return crypto.createHash("sha256").update(plain).digest("hex");
}

interface TokenRequestBody {
  client_id: string;
  client_secret: string;
}

/**
 * POST /v1/auth/token
 *
 * Client Credentials flow:
 *   { client_id: "centauri", client_secret: "..." }
 *   → { access_token: "eyJ...", token_type: "Bearer", expires_in: 900 }
 */
async function handler(request: HttpRequest, context: InvocationContext): Promise<HttpResponseInit> {
  const correlationId = request.headers.get("x-correlation-id") ?? uuidv4();

  try {
    const body = (await request.json()) as TokenRequestBody;

    if (!body.client_id || typeof body.client_id !== "string") {
      throw new ValidationError("client_id is required");
    }
    if (!body.client_secret || typeof body.client_secret !== "string") {
      throw new ValidationError("client_secret is required");
    }

    const prisma = getPrisma();

    // Find app by client_id (= appId)
    const appConfig = await prisma.appConfig.findUnique({
      where: { appId: body.client_id },
    });

    if (!appConfig || !appConfig.isActive) {
      throw new AuthError("Invalid client credentials");
    }

    // Verify client_secret
    const providedHash = hashSecret(body.client_secret);
    if (providedHash !== appConfig.clientSecretHash) {
      throw new AuthError("Invalid client credentials");
    }

    // Sign JWT
    const jwtSecret = getJwtSecret();
    const now = Math.floor(Date.now() / 1000);
    const expiresIn = 900; // 15 minutes

    const token = jwt.sign(
      {
        sub: appConfig.appId,
        appId: appConfig.appId,
        displayName: appConfig.displayName,
        iat: now,
        exp: now + expiresIn,
        jti: uuidv4(),
      },
      jwtSecret,
      { algorithm: "HS256" },
    );

    context.log(`Token issued for app: ${appConfig.appId}`);

    return {
      ...success({
        access_token: token,
        token_type: "Bearer",
        expires_in: expiresIn,
      }),
      headers: { "x-correlation-id": correlationId },
    };
  } catch (err) {
    context.error(`[authToken] Error:`, err);
    return {
      ...error(err instanceof AppError ? err : (err as Error)),
      headers: { "x-correlation-id": correlationId },
    };
  }
}

// Exported for use in seed script and admin endpoints
export { hashSecret };

app.http("authToken", {
  methods: ["POST"],
  authLevel: "anonymous",
  route: "v1/auth/token",
  handler,
});
