import { PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();

const apps = [
  {
    appId: "centauri",
    displayName: "Centauri",
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
    console.log(`    allowedMimeTypes: ${result.allowedMimeTypes.join(", ")}`);
    console.log(`    maxFileSize: ${Number(result.maxFileSize) / 1024 / 1024} MB`);
    console.log(`    storageQuota: ${Number(result.storageQuota) / 1024 / 1024 / 1024} GB`);
    console.log(`    rateLimitPerMinute: ${result.rateLimitPerMinute}`);
    console.log("");
  }

  console.log("Seed completed!");
}

main()
  .catch((e) => {
    console.error("Seed failed:", e);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
