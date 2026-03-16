import { app, HttpRequest, HttpResponseInit, InvocationContext } from "@azure/functions";
import { v4 as uuidv4 } from "uuid";
import { ensurePrisma } from "../shared/prisma.js";
import { commitBlockList, generateSasToken, getBlobUrl } from "../shared/storage.js";
import { authenticateRequest } from "../middleware/auth.js";
import { rateLimitMiddleware } from "../middleware/rateLimit.js";
import { success, error } from "../shared/response.js";
import { ValidationError, NotFoundError, AppError } from "../shared/errors.js";
import { logAudit } from "../shared/audit.js";
import { dispatchWebhook, WebhookPayload } from "../shared/webhook.js";

interface CompleteUploadBody {
  blockIds: string[];
}

async function handler(request: HttpRequest, context: InvocationContext): Promise<HttpResponseInit> {
  const correlationId = request.headers.get("x-correlation-id") ?? uuidv4();

  try {
    const auth = await authenticateRequest(request);
    const prisma = await ensurePrisma();

    // Load app config for rate limiting
    const appConfig = await prisma.appConfig.findUnique({
      where: { appId: auth.appId },
    });

    if (appConfig) {
      rateLimitMiddleware(auth.appId, auth.appId, {
        maxRequestsPerUser: appConfig.rateLimitPerMinute,
        maxRequestsPerApp: appConfig.rateLimitPerMinute * 10,
        windowMs: 60_000,
      });
    }

    const uploadId = request.params.uploadId;
    if (!uploadId) {
      throw new ValidationError("uploadId is required");
    }

    // Parse request body
    const body = (await request.json()) as CompleteUploadBody;
    const blockIds = body.blockIds ?? [];

    // Find upload and verify ownership
    const upload = await prisma.upload.findUnique({
      where: { id: uploadId },
      include: { chunks: true },
    });

    if (!upload) {
      throw new NotFoundError(`Upload not found: ${uploadId}`);
    }

    if (upload.appId !== auth.appId) {
      throw new NotFoundError(`Upload not found: ${uploadId}`);
    }

    if (upload.status !== "UPLOADING") {
      throw new ValidationError(`Upload is not in UPLOADING status. Current status: ${upload.status}`);
    }

    // Determine container name
    const containerName = upload.isPublic ? "uploads-public" : `uploads-${auth.appId}`;

    // For CHUNKED uploads, commit block list; for SINGLE_SHOT, blob is already complete
    const isChunked = upload.chunks.length > 0;
    if (isChunked) {
      if (!blockIds.length) {
        throw new ValidationError("blockIds is required for chunked uploads");
      }
      await commitBlockList(containerName, upload.blobPath!, blockIds);
    }

    // Generate unique fileId
    const fileId = uuidv4();

    // Generate download URL
    let downloadUrl: string;
    if (upload.isPublic) {
      const cdnBaseUrl = process.env.CDN_BASE_URL ?? "";
      downloadUrl = cdnBaseUrl ? `${cdnBaseUrl}/${fileId}` : getBlobUrl(containerName, upload.blobPath!);
    } else {
      const sas = await generateSasToken(containerName, upload.blobPath!, "r", 60);
      downloadUrl = `${sas.blobUrl}?${sas.sasToken}`;
    }

    const completedAt = new Date();

    // Update upload record
    await prisma.upload.update({
      where: { id: uploadId },
      data: {
        status: "COMPLETED",
        progress: 100,
        fileId,
        downloadUrl,
        completedAt,
      },
    });

    // Delete chunk states
    await prisma.chunkState.deleteMany({
      where: { uploadId },
    });

    // Audit log
    logAudit(uploadId, auth.appId, "UPLOAD_COMPLETED", {
      fileId,
      fileName: upload.fileName,
      fileSize: Number(upload.fileSize),
      blockCount: blockIds.length,
    }, request);

    // Webhook dispatch (fire-and-forget)
    if (appConfig?.webhookUrl) {
      const webhookPayload: WebhookPayload = {
        event: "upload.completed",
        timestamp: completedAt.toISOString(),
        data: {
          uploadId,
          fileId,
          appId: auth.appId,
          fileName: upload.fileName,
          fileSize: Number(upload.fileSize),
          mimeType: upload.mimeType,
          entityType: upload.entityType,
          entityId: upload.entityId,
          status: "COMPLETED",
          downloadUrl,
          isPublic: upload.isPublic,
          completedAt: completedAt.toISOString(),
        },
      };
      dispatchWebhook(appConfig.webhookUrl, webhookPayload, appConfig.clientSecretHash, context);
    }

    return {
      ...success({
        fileId,
        downloadUrl,
        metadata: {
          fileName: upload.fileName,
          fileSize: Number(upload.fileSize),
          mimeType: upload.mimeType,
          entityType: upload.entityType,
          entityId: upload.entityId,
        },
        processingStatus: "PENDING",
        blurHash: null,
      }),
      headers: { "x-correlation-id": correlationId },
    };
  } catch (err) {
    context.error(`[completeUpload] Error:`, err);
    return {
      ...error(err instanceof AppError ? err : (err as Error)),
      headers: { "x-correlation-id": correlationId },
    };
  }
}

app.http("completeUpload", {
  methods: ["POST"],
  authLevel: "anonymous",
  route: "v1/uploads/{uploadId}/complete",
  handler,
});
