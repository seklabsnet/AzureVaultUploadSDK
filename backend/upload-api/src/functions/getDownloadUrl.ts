import { app, HttpRequest, HttpResponseInit, InvocationContext } from "@azure/functions";
import { v4 as uuidv4 } from "uuid";
import { getPrisma } from "../shared/prisma.js";
import { generateSasToken } from "../shared/storage.js";
import { authenticateRequest } from "../middleware/auth.js";
import { success, error } from "../shared/response.js";
import { ValidationError, NotFoundError, AppError } from "../shared/errors.js";

async function handler(request: HttpRequest, context: InvocationContext): Promise<HttpResponseInit> {
  const correlationId = request.headers.get("x-correlation-id") ?? uuidv4();

  try {
    const auth = await authenticateRequest(request);
    const prisma = getPrisma();

    const fileId = request.params.fileId;
    if (!fileId) {
      throw new ValidationError("fileId is required");
    }

    // Find upload by fileId
    const upload = await prisma.upload.findFirst({
      where: { fileId },
    });

    if (!upload) {
      throw new NotFoundError(`File not found: ${fileId}`);
    }

    if (upload.appId !== auth.appId) {
      throw new NotFoundError(`File not found: ${fileId}`);
    }

    let downloadUrl: string;
    let expiresAt: Date | null = null;

    if (upload.isPublic) {
      // Public files served via CDN
      const cdnBaseUrl = process.env.CDN_BASE_URL;
      if (cdnBaseUrl) {
        downloadUrl = `${cdnBaseUrl}/${fileId}`;
      } else {
        // Fallback to direct blob URL if CDN is not configured
        const containerName = "uploads-public";
        const sas = await generateSasToken(containerName, upload.blobPath, "r", 60);
        downloadUrl = `${sas.blobUrl}?${sas.sasToken}`;
        expiresAt = sas.expiresAt;
      }
    } else {
      // Private files: generate read-only SAS token with 1 hour expiry
      const containerName = `uploads-${auth.appId}`;
      const sas = await generateSasToken(containerName, upload.blobPath, "r", 60);
      downloadUrl = `${sas.blobUrl}?${sas.sasToken}`;
      expiresAt = sas.expiresAt;
    }

    const responseData: Record<string, unknown> = {
      downloadUrl,
      contentType: upload.mimeType,
      fileSize: upload.fileSize,
    };

    if (expiresAt) {
      responseData.expiresAt = expiresAt.toISOString();
    }

    if (upload.blurHash) {
      responseData.blurHash = upload.blurHash;
    }

    return {
      ...success(responseData),
      headers: { "x-correlation-id": correlationId },
    };
  } catch (err) {
    context.error(`[getDownloadUrl] Error:`, err);
    return {
      ...error(err instanceof AppError ? err : (err as Error)),
      headers: { "x-correlation-id": correlationId },
    };
  }
}

app.http("getDownloadUrl", {
  methods: ["GET"],
  authLevel: "anonymous",
  route: "v1/uploads/{fileId}/download-url",
  handler,
});
