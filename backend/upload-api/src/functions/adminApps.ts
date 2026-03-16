import { app, HttpRequest, HttpResponseInit, InvocationContext } from "@azure/functions";
import { v4 as uuidv4 } from "uuid";
import crypto from "crypto";
import { getPrisma } from "../shared/prisma.js";
import { authenticateRequest } from "../middleware/auth.js";
import { success, error } from "../shared/response.js";
import { ValidationError, NotFoundError, AppError } from "../shared/errors.js";

function generateClientSecret(appId: string): string {
  const random = crypto.randomBytes(16).toString("hex");
  return `cvlt_${appId}_${random}`;
}

function hashSecret(plain: string): string {
  return crypto.createHash("sha256").update(plain).digest("hex");
}

const MB = 1_048_576;
const GB = 1_073_741_824;

interface CreateAppBody {
  appId: string;
  displayName: string;
  allowedMimeTypes: string[];
  maxFileSize: number;
  maxConcurrentUploads?: number;
  storageQuota: number;
  webhookUrl?: string;
  rateLimitPerMinute?: number;
}

// POST /v1/admin/apps — Register a new app
async function createApp(request: HttpRequest, context: InvocationContext): Promise<HttpResponseInit> {
  const correlationId = request.headers.get("x-correlation-id") ?? uuidv4();

  try {
    await authenticateRequest(request);
    // TODO: Add admin role check — only admins should register apps

    const body = (await request.json()) as CreateAppBody;

    // Validate required fields
    if (!body.appId || typeof body.appId !== "string") {
      throw new ValidationError("appId is required (e.g. 'centauri', 'happybrain')");
    }
    if (!body.displayName || typeof body.displayName !== "string") {
      throw new ValidationError("displayName is required (e.g. 'Centauri App')");
    }
    if (!Array.isArray(body.allowedMimeTypes) || body.allowedMimeTypes.length === 0) {
      throw new ValidationError(
        "allowedMimeTypes is required. Examples: " +
        '["image/*"] for images only, ' +
        '["image/*", "video/*", "application/pdf"] for mixed content, ' +
        '["*/*"] for everything',
      );
    }
    if (!body.maxFileSize || typeof body.maxFileSize !== "number" || body.maxFileSize <= 0) {
      throw new ValidationError(
        "maxFileSize is required (in bytes). Examples: " +
        `${10 * MB} (10MB), ${100 * MB} (100MB), ${1 * GB} (1GB), ${5 * GB} (5GB)`,
      );
    }
    if (!body.storageQuota || typeof body.storageQuota !== "number" || body.storageQuota <= 0) {
      throw new ValidationError(
        "storageQuota is required (in bytes). Examples: " +
        `${100 * GB} (100GB), ${500 * GB} (500GB), ${1024 * GB} (1TB)`,
      );
    }

    // Validate appId format
    if (!/^[a-z0-9-]+$/.test(body.appId)) {
      throw new ValidationError("appId must be lowercase alphanumeric with hyphens only (e.g. 'my-app')");
    }

    const prisma = getPrisma();

    // Check if app already exists
    const existing = await prisma.appConfig.findUnique({ where: { appId: body.appId } });
    if (existing) {
      throw new ValidationError(`App "${body.appId}" is already registered`);
    }

    // Generate client credentials
    const clientSecret = generateClientSecret(body.appId);
    const clientSecretHash = hashSecret(clientSecret);

    // Create app config
    const appConfig = await prisma.appConfig.create({
      data: {
        appId: body.appId,
        displayName: body.displayName,
        clientSecretHash,
        allowedMimeTypes: body.allowedMimeTypes,
        maxFileSize: body.maxFileSize,
        maxConcurrentUploads: body.maxConcurrentUploads ?? 3,
        storageQuota: body.storageQuota,
        webhookUrl: body.webhookUrl ?? null,
        rateLimitPerMinute: body.rateLimitPerMinute ?? 1000,
        isActive: true,
      },
    });

    context.log(`App registered: ${appConfig.appId} (${appConfig.displayName})`);

    return {
      ...success({
        appId: appConfig.appId,
        displayName: appConfig.displayName,
        clientSecret, // ⚠️ Shown ONCE — store it securely
        allowedMimeTypes: appConfig.allowedMimeTypes,
        maxFileSize: Number(appConfig.maxFileSize),
        maxConcurrentUploads: appConfig.maxConcurrentUploads,
        storageQuota: Number(appConfig.storageQuota),
        rateLimitPerMinute: appConfig.rateLimitPerMinute,
        isActive: appConfig.isActive,
        message: `App "${appConfig.appId}" registered. Store the clientSecret securely — it won't be shown again.`,
      }, 201),
      headers: { "x-correlation-id": correlationId },
    };
  } catch (err) {
    context.error(`[createApp] Error:`, err);
    return {
      ...error(err instanceof AppError ? err : (err as Error)),
      headers: { "x-correlation-id": correlationId },
    };
  }
}

// GET /v1/admin/apps — List all registered apps
async function listApps(request: HttpRequest, context: InvocationContext): Promise<HttpResponseInit> {
  const correlationId = request.headers.get("x-correlation-id") ?? uuidv4();

  try {
    await authenticateRequest(request);

    const prisma = getPrisma();
    const apps = await prisma.appConfig.findMany({
      orderBy: { createdAt: "asc" },
    });

    return {
      ...success(
        apps.map((a) => ({
          appId: a.appId,
          displayName: a.displayName,
          allowedMimeTypes: a.allowedMimeTypes,
          maxFileSize: Number(a.maxFileSize),
          maxConcurrentUploads: a.maxConcurrentUploads,
          storageQuota: Number(a.storageQuota),
          rateLimitPerMinute: a.rateLimitPerMinute,
          isActive: a.isActive,
          createdAt: a.createdAt.toISOString(),
        })),
      ),
      headers: { "x-correlation-id": correlationId },
    };
  } catch (err) {
    context.error(`[listApps] Error:`, err);
    return {
      ...error(err instanceof AppError ? err : (err as Error)),
      headers: { "x-correlation-id": correlationId },
    };
  }
}

// PUT /v1/admin/apps/{appId} — Update app config
async function updateApp(request: HttpRequest, context: InvocationContext): Promise<HttpResponseInit> {
  const correlationId = request.headers.get("x-correlation-id") ?? uuidv4();

  try {
    await authenticateRequest(request);

    const appId = request.params.appId;
    if (!appId) throw new ValidationError("appId is required");

    const prisma = getPrisma();
    const existing = await prisma.appConfig.findUnique({ where: { appId } });
    if (!existing) {
      throw new NotFoundError(`App "${appId}" not found`);
    }

    const body = (await request.json()) as Partial<CreateAppBody>;
    const updateData: Record<string, unknown> = {};

    if (body.displayName) updateData.displayName = body.displayName;
    if (body.allowedMimeTypes) updateData.allowedMimeTypes = body.allowedMimeTypes;
    if (body.maxFileSize) updateData.maxFileSize = body.maxFileSize;
    if (body.maxConcurrentUploads) updateData.maxConcurrentUploads = body.maxConcurrentUploads;
    if (body.storageQuota) updateData.storageQuota = body.storageQuota;
    if (body.webhookUrl !== undefined) updateData.webhookUrl = body.webhookUrl;
    if (body.rateLimitPerMinute) updateData.rateLimitPerMinute = body.rateLimitPerMinute;

    const updated = await prisma.appConfig.update({
      where: { appId },
      data: updateData,
    });

    return {
      ...success({
        appId: updated.appId,
        displayName: updated.displayName,
        allowedMimeTypes: updated.allowedMimeTypes,
        maxFileSize: Number(updated.maxFileSize),
        maxConcurrentUploads: updated.maxConcurrentUploads,
        storageQuota: Number(updated.storageQuota),
        rateLimitPerMinute: updated.rateLimitPerMinute,
        isActive: updated.isActive,
      }),
      headers: { "x-correlation-id": correlationId },
    };
  } catch (err) {
    context.error(`[updateApp] Error:`, err);
    return {
      ...error(err instanceof AppError ? err : (err as Error)),
      headers: { "x-correlation-id": correlationId },
    };
  }
}

app.http("createApp", {
  methods: ["POST"],
  authLevel: "anonymous",
  route: "v1/admin/apps",
  handler: createApp,
});

app.http("listApps", {
  methods: ["GET"],
  authLevel: "anonymous",
  route: "v1/admin/apps",
  handler: listApps,
});

app.http("updateApp", {
  methods: ["PUT"],
  authLevel: "anonymous",
  route: "v1/admin/apps/{appId}",
  handler: updateApp,
});
