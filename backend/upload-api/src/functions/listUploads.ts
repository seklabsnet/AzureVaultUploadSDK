import { app, HttpRequest, HttpResponseInit, InvocationContext } from "@azure/functions";
import { v4 as uuidv4 } from "uuid";
import { ensurePrisma } from "../shared/prisma.js";
import { authenticateRequest } from "../middleware/auth.js";
import { success, error } from "../shared/response.js";
import { ValidationError, AppError } from "../shared/errors.js";

const DEFAULT_LIMIT = 20;
const MAX_LIMIT = 100;

async function handler(request: HttpRequest, context: InvocationContext): Promise<HttpResponseInit> {
  const correlationId = request.headers.get("x-correlation-id") ?? uuidv4();

  try {
    const auth = await authenticateRequest(request);
    const prisma = await ensurePrisma();

    // Parse query parameters
    const pageParam = request.query.get("page");
    const limitParam = request.query.get("limit");
    const statusFilter = request.query.get("status");
    const entityTypeFilter = request.query.get("entityType");

    const page = pageParam ? parseInt(pageParam, 10) : 1;
    const limit = limitParam ? Math.min(parseInt(limitParam, 10), MAX_LIMIT) : DEFAULT_LIMIT;

    if (isNaN(page) || page < 1) {
      throw new ValidationError("page must be a positive integer");
    }

    if (isNaN(limit) || limit < 1) {
      throw new ValidationError("limit must be a positive integer");
    }

    // Build where clause
    const where: Record<string, unknown> = {
      appId: auth.appId,
    };

    if (statusFilter) {
      const validStatuses = [
        "PENDING", "VALIDATING", "UPLOADING", "PAUSED",
        "COMMITTING", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED",
      ];
      if (!validStatuses.includes(statusFilter)) {
        throw new ValidationError(`Invalid status filter. Valid values: ${validStatuses.join(", ")}`);
      }
      where.status = statusFilter;
    }

    if (entityTypeFilter) {
      where.entityType = entityTypeFilter;
    }

    const skip = (page - 1) * limit;

    // Query uploads with count
    const [uploads, total] = await Promise.all([
      prisma.upload.findMany({
        where,
        orderBy: { createdAt: "desc" },
        skip,
        take: limit,
        select: {
          id: true,
          fileName: true,
          fileSize: true,
          mimeType: true,
          entityType: true,
          entityId: true,
          status: true,
          progress: true,
          fileId: true,
          downloadUrl: true,
          blurHash: true,
          isPublic: true,
          createdAt: true,
          completedAt: true,
        },
      }),
      prisma.upload.count({ where }),
    ]);

    const serializedUploads = uploads.map((u) => ({
      ...u,
      fileSize: Number(u.fileSize),
      createdAt: u.createdAt.toISOString(),
      completedAt: u.completedAt?.toISOString() ?? null,
    }));

    return {
      ...success({
        uploads: serializedUploads,
        total,
        page,
        limit,
      }),
      headers: { "x-correlation-id": correlationId },
    };
  } catch (err) {
    context.error(`[listUploads] Error:`, err);
    return {
      ...error(err instanceof AppError ? err : (err as Error)),
      headers: { "x-correlation-id": correlationId },
    };
  }
}

app.http("listUploads", {
  methods: ["GET"],
  authLevel: "anonymous",
  route: "v1/uploads/list",
  handler,
});
