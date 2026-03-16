import { PrismaClient } from "@prisma/client";
import { DefaultAzureCredential } from "@azure/identity";

let prisma: PrismaClient;
let tokenExpiresAt = 0;

const PG_TOKEN_SCOPE = "https://ossrdbms-aad.database.windows.net/.default";
const useManagedIdentity = process.env.USE_MANAGED_IDENTITY === "true";

/**
 * Returns a Prisma client connected to PostgreSQL.
 *
 * If USE_MANAGED_IDENTITY=true: acquires Entra token, sets DATABASE_URL with token as password.
 * Otherwise: uses DATABASE_URL as-is (password-based, for local dev).
 */
export async function ensurePrisma(): Promise<PrismaClient> {
  if (useManagedIdentity) {
    await refreshTokenIfNeeded();
  }

  if (!prisma) {
    prisma = new PrismaClient();
  }
  return prisma;
}

/** @deprecated Use ensurePrisma() instead. Kept for backward compat during migration. */
export function getPrisma(): PrismaClient {
  if (!prisma) {
    prisma = new PrismaClient();
  }
  return prisma;
}

async function refreshTokenIfNeeded(): Promise<void> {
  const now = Date.now();

  // Token still valid (with 5 min buffer)
  if (now < tokenExpiresAt - 5 * 60 * 1000) {
    return;
  }

  const pgHost = process.env.PostgresHost;
  const pgDb = process.env.PostgresDb ?? "azurevault";

  if (!pgHost) {
    throw new Error("PostgresHost is required for Managed Identity auth");
  }

  const credential = new DefaultAzureCredential();
  const token = await credential.getToken(PG_TOKEN_SCOPE);

  if (!token) {
    throw new Error("Failed to acquire Entra token for PostgreSQL");
  }

  tokenExpiresAt = token.expiresOnTimestamp;

  const miUsername = process.env.MI_PG_USERNAME ?? "func-azurevault-upload";
  process.env.DATABASE_URL = `postgresql://${miUsername}:${encodeURIComponent(token.token)}@${pgHost}:5432/${pgDb}?sslmode=require`;

  // Reconnect Prisma with new token
  if (prisma) {
    await prisma.$disconnect();
  }
  prisma = new PrismaClient();
}
