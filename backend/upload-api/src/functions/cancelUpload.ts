import { app, HttpRequest, HttpResponseInit, InvocationContext } from "@azure/functions";
import { v4 as uuidv4 } from "uuid";
import { getPrisma } from "../shared/prisma.js";
import { deleteBlob } from "../shared/storage.js";
import { authenticateRequest } from "../middleware/auth.js";
import { success, error } from "../shared/response.js";
import { ValidationError, NotFoundError, AppError } from "../shared/errors.js";
import { logAudit } from "../shared/audit.js";
import { dispatchWebhook, WebhookPayload } from "../shared/webhook.js";

async function handler(request: HttpRequest, context: InvocationContext): Promise<HttpResponseInit> {
  const correlationId = request.headers.get("x-correlation-id") ?? uuidv4();

  try {
    const auth = await authenticateRequest(request);
    const prisma = getPrisma();

    const uploadId = request.params.uploadId;
    if (!uploadId) {
      throw new ValidationError("uploadId is required");
    }

    // Find upload and verify ownership
    const upload = await prisma.upload.findUnique({
      where: { id: uploadId },
    });

    if (!upload) {
      throw new NotFoundError(`Upload not found: ${uploadId}`);
    }

    if (upload.appId !== auth.appId) {
      throw new NotFoundError(`Upload not found: ${uploadId}`);
    }

    const containerName = upload.isPublic ? "uploads-public" : `uploads-${auth.appId}`;

    // Delete blob from storage based on status
    if (upload.status === "COMPLETED" || upload.status === "UPLOADING" || upload.status === "PAUSED") {
      try {
        await deleteBlob(containerName, upload.blobPath!);
      } catch (blobErr) {
        // Log but don't fail the cancellation if blob deletion fails
        context.warn(`[cancelUpload] Failed to delete blob for upload ${uploadId}:`, blobErr);
      }
    }

    // Delete chunk states
    await prisma.chunkState.deleteMany({
      where: { uploadId },
    });

    // Update upload status to CANCELLED
    await prisma.upload.update({
      where: { id: uploadId },
      data: {
        status: "CANCELLED",
      },
    });

    // Audit log
    logAudit(uploadId, auth.appId, "UPLOAD_CANCELLED", {
      previousStatus: upload.status,
      fileName: upload.fileName,
    }, request);

    // Webhook dispatch (fire-and-forget)
    const appConfig = await prisma.appConfig.findUnique({ where: { appId: auth.appId } });
    if (appConfig?.webhookUrl) {
      const webhookPayload: WebhookPayload = {
        event: "upload.cancelled",
        timestamp: new Date().toISOString(),
        data: {
          uploadId,
          fileId: upload.fileId,
          appId: auth.appId,
          fileName: upload.fileName,
          fileSize: Number(upload.fileSize),
          mimeType: upload.mimeType,
          entityType: upload.entityType,
          entityId: upload.entityId,
          status: "CANCELLED",
          downloadUrl: null,
          isPublic: upload.isPublic,
          completedAt: null,
        },
      };
      dispatchWebhook(appConfig.webhookUrl, webhookPayload, appConfig.clientSecretHash, context);
    }

    return {
      ...success({ success: true }),
      headers: { "x-correlation-id": correlationId },
    };
  } catch (err) {
    context.error(`[cancelUpload] Error:`, err);
    return {
      ...error(err instanceof AppError ? err : (err as Error)),
      headers: { "x-correlation-id": correlationId },
    };
  }
}

app.http("cancelUpload", {
  methods: ["DELETE"],
  authLevel: "anonymous",
  route: "v1/uploads/{uploadId}",
  handler,
});
