import { PrismaClient } from "@prisma/client";
import crypto from "crypto";

const prisma = new PrismaClient();

function hashSecret(plain: string): string {
  return crypto.createHash("sha256").update(plain).digest("hex");
}

// Client secrets — load from environment variables, never hardcode
const secrets: Record<string, string> = {
  centauri: process.env.CENTAURI_CLIENT_SECRET || "GENERATE_WITH_ADMIN_API",
  happybrain: process.env.HAPPYBRAIN_CLIENT_SECRET || "GENERATE_WITH_ADMIN_API",
};

const apps = [
  {
    appId: "centauri",
    displayName: "Centauri",
    clientSecretHash: hashSecret(secrets.centauri),
    allowedMimeTypes: ["image/*", "video/*", "application/pdf"],
    maxFileSize: BigInt(100 * 1024 * 1024), // 100 MB
    maxConcurrentUploads: 3,
    storageQuota: BigInt(500 * 1024 * 1024 * 1024), // 500 GB
    webhookUrl: null,
    rateLimitPerMinute: 1000,
    isActive: true,
  },
  {
    appId: "happybrain",
    displayName: "HappyBrain",
    clientSecretHash: hashSecret(secrets.happybrain),
    allowedMimeTypes: ["image/*", "audio/*", "video/*", "application/pdf"],
    maxFileSize: BigInt(5 * 1024 * 1024 * 1024), // 5 GB
    maxConcurrentUploads: 5,
    storageQuota: BigInt(1024) * BigInt(1024 * 1024 * 1024), // 1 TB
    webhookUrl: null,
    rateLimitPerMinute: 2000,
    isActive: true,
  },
];

async function main() {
  console.log("Seeding app configs...\n");

  for (const app of apps) {
    const result = await prisma.appConfig.upsert({
      where: { appId: app.appId },
      update: app,
      create: app,
    });
    console.log(`  ✓ ${result.displayName} (${result.appId})`);
    console.log(`    clientSecret: ${secrets[result.appId]}`);
    console.log(`    allowedMimeTypes: ${result.allowedMimeTypes.join(", ")}`);
    console.log(`    maxFileSize: ${Number(result.maxFileSize) / 1024 / 1024} MB`);
    console.log(`    storageQuota: ${Number(result.storageQuota) / 1024 / 1024 / 1024} GB`);
    console.log(`    rateLimitPerMinute: ${result.rateLimitPerMinute}`);
    console.log("");
  }

  console.log("Seed completed!");
  console.log("\n⚠️  Store client secrets securely — they are shown only once.");
}

main()
  .catch((e) => {
    console.error("Seed failed:", e);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
