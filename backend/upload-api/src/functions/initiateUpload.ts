import { app, HttpRequest, HttpResponseInit, InvocationContext } from "@azure/functions";
import { v4 as uuidv4 } from "uuid";
import { getPrisma } from "../shared/prisma.js";
import { generateSasToken } from "../shared/storage.js";
import { authenticateRequest } from "../middleware/auth.js";
import { rateLimitMiddleware } from "../middleware/rateLimit.js";
import { success, error } from "../shared/response.js";
import { ValidationError, NotFoundError, AppError } from "../shared/errors.js";
import { logAudit } from "../shared/audit.js";

const MB = 1_048_576;
const GB = 1_073_741_824;
const SINGLE_SHOT_THRESHOLD = 4 * MB;

interface InitiateUploadBody {
  fileName: string;
  fileSize: number;
  mimeType: string;
  entityType: string;
  entityId: string;
  isPublic?: boolean;
  metadata?: Record<string, unknown>;
}

interface ChunkTier {
  chunkSize: number;
  concurrency: number;
}

function determineChunkTier(fileSize: number): ChunkTier {
  if (fileSize <= 256 * MB) {
    return { chunkSize: 4 * MB, concurrency: 4 };
  } else if (fileSize <= 1 * GB) {
    return { chunkSize: 8 * MB, concurrency: 6 };
  } else if (fileSize <= 5 * GB) {
    return { chunkSize: 16 * MB, concurrency: 8 };
  } else {
    return { chunkSize: 32 * MB, concurrency: 8 };
  }
}

function matchesMimePattern(mimeType: string, pattern: string): boolean {
  if (pattern === "*" || pattern === "*/*") return true;
  if (pattern.endsWith("/*")) {
    const prefix = pattern.slice(0, -2);
    return mimeType.startsWith(prefix + "/");
  }
  return mimeType === pattern;
}

async function handler(request: HttpRequest, context: InvocationContext): Promise<HttpResponseInit> {
  const correlationId = request.headers.get("x-correlation-id") ?? uuidv4();

  try {
    const auth = await authenticateRequest(request);
    const prisma = getPrisma();

    // Load app config for rate limiting and validation
    const appConfig = await prisma.appConfig.findUnique({
      where: { appId: auth.appId },
    });

    if (!appConfig) {
      throw new NotFoundError(
        `App "${auth.appId}" is not registered. ` +
        `Register it first via POST /v1/admin/apps with: ` +
        `{ appId, displayName, allowedMimeTypes, maxFileSize, storageQuota }`,
      );
    }

    if (!appConfig.isActive) {
      throw new ValidationError("Application is currently inactive");
    }

    // Rate limit check
    rateLimitMiddleware(auth.appId, auth.appId, {
      maxRequestsPerUser: appConfig.rateLimitPerMinute,
      maxRequestsPerApp: appConfig.rateLimitPerMinute * 10,
      windowMs: 60_000,
    });

    // Parse and validate request body
    const body = (await request.json()) as InitiateUploadBody;

    if (!body.fileName || typeof body.fileName !== "string") {
      throw new ValidationError("fileName is required and must be a string");
    }
    if (!body.fileSize || typeof body.fileSize !== "number" || body.fileSize <= 0) {
      throw new ValidationError("fileSize is required and must be a positive number");
    }
    if (!body.mimeType || typeof body.mimeType !== "string") {
      throw new ValidationError("mimeType is required and must be a string");
    }
    if (!body.entityType || typeof body.entityType !== "string") {
      throw new ValidationError("entityType is required and must be a string");
    }
    if (!body.entityId || typeof body.entityId !== "string") {
      throw new ValidationError("entityId is required and must be a string");
    }

    // Validate mimeType against allowed types
    const allowedTypes = (appConfig.allowedMimeTypes as string[]) ?? [];
    if (allowedTypes.length > 0) {
      const isAllowed = allowedTypes.some((pattern) => matchesMimePattern(body.mimeType, pattern));
      if (!isAllowed) {
        throw new ValidationError(`MIME type "${body.mimeType}" is not allowed. Allowed types: ${allowedTypes.join(", ")}`);
      }
    }

    // Validate file size
    if (body.fileSize > appConfig.maxFileSize) {
      throw new ValidationError(
        `File size ${body.fileSize} exceeds maximum allowed size of ${appConfig.maxFileSize} bytes`,
      );
    }

    // Determine upload strategy
    const strategy = body.fileSize <= SINGLE_SHOT_THRESHOLD ? "SINGLE_SHOT" : "CHUNKED";

    // Generate IDs and paths
    const uploadId = uuidv4();
    const now = new Date();
    const year = now.getUTCFullYear();
    const month = String(now.getUTCMonth() + 1).padStart(2, "0");
    const isPublic = body.isPublic ?? false;

    const blobPath = `${body.entityType}/${body.entityId}/${year}/${month}/${uploadId}/${body.fileName}`;
    const containerName = isPublic ? "uploads-public" : `uploads-${auth.appId}`;

    // Generate SAS token with Write + Create permissions, 15 min expiry
    const sas = await generateSasToken(containerName, blobPath, "wc", 15);

    // Determine chunk parameters
    let chunkCount: number | undefined;
    let maxBlockSize: number | undefined;

    if (strategy === "CHUNKED") {
      const tier = determineChunkTier(body.fileSize);
      maxBlockSize = tier.chunkSize;
      chunkCount = Math.ceil(body.fileSize / tier.chunkSize);
    } else {
      maxBlockSize = body.fileSize;
      chunkCount = 1;
    }

    // Create Upload record
    const upload = await prisma.upload.create({
      data: {
        id: uploadId,
        appId: auth.appId,
        fileName: body.fileName,
        fileSize: body.fileSize,
        mimeType: body.mimeType,
        entityType: body.entityType,
        entityId: body.entityId,
        isPublic,
        status: "UPLOADING",
        progress: 0,
        blobPath,
        sasToken: sas.sasToken,
        sasExpiresAt: sas.expiresAt,
      },
    });

    // If CHUNKED, create ChunkState records
    if (strategy === "CHUNKED" && chunkCount) {
      const chunkStates = [];
      const chunkSize = maxBlockSize!;

      for (let i = 0; i < chunkCount; i++) {
        const offset = i * chunkSize;
        const size = Math.min(chunkSize, body.fileSize - offset);
        const blockId = Buffer.from(`block-${String(i).padStart(6, "0")}`).toString("base64");

        chunkStates.push({
          uploadId,
          chunkIndex: i,
          blockId,
          size,
          offset,
          uploaded: false,
        });
      }

      await prisma.chunkState.createMany({ data: chunkStates });
    }

    // Audit log
    logAudit(uploadId, auth.appId, "UPLOAD_INITIATED", {
      fileName: body.fileName,
      fileSize: body.fileSize,
      mimeType: body.mimeType,
      strategy,
      entityType: body.entityType,
      entityId: body.entityId,
    }, request);

    const responseData: Record<string, unknown> = {
      uploadId,
      blobUrl: sas.blobUrl,
      sasToken: sas.sasToken,
      strategy,
      maxBlockSize,
      expiresAt: sas.expiresAt.toISOString(),
    };

    if (strategy === "CHUNKED") {
      responseData.chunkCount = chunkCount;
    }

    return {
      ...success(responseData),
      headers: { "x-correlation-id": correlationId },
    };
  } catch (err) {
    context.error(`[initiateUpload] Error:`, err);
    return {
      ...error(err instanceof AppError ? err : (err as Error)),
      headers: { "x-correlation-id": correlationId },
    };
  }
}

app.http("initiateUpload", {
  methods: ["POST"],
  authLevel: "anonymous",
  route: "v1/uploads/initiate",
  handler,
});
