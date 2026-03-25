import { app, HttpRequest, HttpResponseInit, InvocationContext } from "@azure/functions";
import { v4 as uuidv4 } from "uuid";
import { ensurePrisma } from "../shared/prisma.js";
import { authenticateRequest } from "../middleware/auth.js";
import { success, error } from "../shared/response.js";
import { AppError, ValidationError } from "../shared/errors.js";

async function handler(request: HttpRequest, context: InvocationContext): Promise<HttpResponseInit> {
  const correlationId = request.headers.get("x-correlation-id") ?? uuidv4();

  try {
    const auth = await authenticateRequest(request);
    const prisma = await ensurePrisma();

    // Parse query parameters
    const since = request.query.get("since") ?? undefined;
    const limitParam = request.query.get("limit");
    const limit = Math.min(Math.max(parseInt(limitParam ?? "100", 10) || 100, 1), 1000);

    // Validate 'since' format if provided
    if (since && !since.startsWith("evt_")) {
      throw new ValidationError("Invalid 'since' parameter. Must be an event ID (evt_...)");
    }

    // Query events after 'since' for this app
    const events = await prisma.eventBuffer.findMany({
      where: {
        appId: auth.appId,
        ...(since ? { id: { gt: since } } : {}),
      },
      orderBy: { id: "asc" },
      take: limit,
    });

    // Check if there are more events beyond this batch
    const hasMore = events.length === limit;

    return {
      ...success({
        events: events.map((e) => ({
          id: e.id,
          type: e.eventType,
          timestamp: e.createdAt.toISOString(),
          payload: e.payload,
          signature: e.signature,
        })),
        hasMore,
      }),
      headers: { "x-correlation-id": correlationId },
    };
  } catch (err) {
    context.error(`[eventPoll] Error:`, err);
    return {
      ...error(err instanceof AppError ? err : (err as Error)),
      headers: { "x-correlation-id": correlationId },
    };
  }
}

app.http("eventPoll", {
  methods: ["GET"],
  authLevel: "anonymous",
  route: "v1/events/poll",
  handler,
});
