import { app, InvocationContext, Timer } from "@azure/functions";
import { ensurePrisma } from "../shared/prisma.js";

async function handler(timer: Timer, context: InvocationContext): Promise<void> {
  context.log("[eventBufferCleanup] Running cleanup...");

  try {
    const prisma = await ensurePrisma();
    const result = await prisma.eventBuffer.deleteMany({
      where: { expiresAt: { lt: new Date() } },
    });

    context.log(`[eventBufferCleanup] Deleted ${result.count} expired events`);
  } catch (err) {
    context.error("[eventBufferCleanup] Error:", err);
  }
}

app.timer("eventBufferCleanup", {
  schedule: "0 0 * * * *", // Every hour
  handler,
});
