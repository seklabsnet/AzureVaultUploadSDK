import { app, HttpRequest, HttpResponseInit, InvocationContext } from "@azure/functions";
import { v4 as uuidv4 } from "uuid";
import { ensurePrisma } from "../shared/prisma.js";
import { authenticateRequest } from "../middleware/auth.js";
import { success, error } from "../shared/response.js";
import { ValidationError, NotFoundError, AppError } from "../shared/errors.js";

async function handler(request: HttpRequest, context: InvocationContext): Promise<HttpResponseInit> {
  const correlationId = request.headers.get("x-correlation-id") ?? uuidv4();

  try {
    const auth = await authenticateRequest(request);
    const prisma = await ensurePrisma();

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

    // Calculate progress from chunk states if still uploading
    let progress = upload.progress;

    if (upload.status === "UPLOADING") {
      const chunks = await prisma.chunkState.findMany({
        where: { uploadId },
      });

      if (chunks.length > 0) {
        const uploadedChunks = chunks.filter((c) => c.uploaded).length;
        progress = Math.round((uploadedChunks / chunks.length) * 100);
      }
    }

    const responseData: Record<string, unknown> = {
      uploadId: upload.id,
      status: upload.status,
      progress,
    };

    if (upload.fileId) {
      responseData.fileId = upload.fileId;
    }

    if (upload.downloadUrl) {
      responseData.downloadUrl = upload.downloadUrl;
    }

    if (upload.blurHash) {
      responseData.blurHash = upload.blurHash;
    }

    return {
      ...success(responseData),
      headers: { "x-correlation-id": correlationId },
    };
  } catch (err) {
    context.error(`[getUploadStatus] Error:`, err);
    return {
      ...error(err instanceof AppError ? err : (err as Error)),
      headers: { "x-correlation-id": correlationId },
    };
  }
}

app.http("getUploadStatus", {
  methods: ["GET"],
  authLevel: "anonymous",
  route: "v1/uploads/{uploadId}/status",
  handler,
});
