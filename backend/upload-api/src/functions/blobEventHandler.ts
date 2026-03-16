import { app, EventGridEvent, InvocationContext } from "@azure/functions";
import { ensurePrisma } from "../shared/prisma.js";
import { moveBlob, deleteBlobsByPrefix } from "../shared/storage.js";
import { dispatchWebhook, WebhookPayload } from "../shared/webhook.js";
import { logAudit } from "../shared/audit.js";

interface BlobEventData {
  api?: string;
  clientRequestId?: string;
  requestId?: string;
  eTag?: string;
  contentType?: string;
  contentLength?: number;
  blobType?: string;
  url?: string;
}

interface DefenderScanEventData {
  correlationKey?: string;
  scanFinishedTimeUtc?: string;
  scanResultType?: "No threats found" | "Malicious" | "Error";
  scanResultDetails?: {
    malwareNamesFound?: string[];
    sha256?: string;
  };
  blobUri?: string;
}

async function handler(event: EventGridEvent, context: InvocationContext): Promise<void> {
  const eventType = event.eventType;
  const blobUrl = (event.data as BlobEventData)?.url ?? "";

  context.log(`[BlobEventHandler] Event: ${eventType}, blob: ${blobUrl}`);

  try {
    // ── Malware Scan Result (Defender for Storage) ──
    if (eventType === "Microsoft.Security.MalwareScanningResult") {
      const data = event.data as DefenderScanEventData;
      const scanResult = data.scanResultType ?? "unknown";
      const scannedBlobUrl = data.blobUri ?? blobUrl;

      context.log(`[BlobEventHandler] Malware scan result: ${scanResult} for ${scannedBlobUrl}`);

      if (scanResult === "Malicious") {
        await handleMalwareDetected(scannedBlobUrl, data, context);
      } else if (scanResult === "No threats found") {
        context.log(`[BlobEventHandler] Clean scan for: ${scannedBlobUrl}`);
        // Optionally update upload status to PROCESSING → COMPLETED pipeline
      }
      return;
    }

    // ── Blob Created ──
    if (eventType === "Microsoft.Storage.BlobCreated") {
      const containerMatch = blobUrl.match(/\/uploads-([\w-]+)\//);
      if (containerMatch) {
        const appId = containerMatch[1];
        context.log(`[BlobEventHandler] New blob in uploads-${appId}`);
        // Defender scans automatically — no action needed here
        // Scan result will arrive as a separate MalwareScanningResult event
      }
      return;
    }

    // ── Blob Deleted → Cache Cleanup ──
    if (eventType === "Microsoft.Storage.BlobDeleted") {
      // If a source blob was deleted, clean up its cached transform variants
      const appContainerMatch = blobUrl.match(/\/uploads-([\w-]+)\/(.+)/);
      if (appContainerMatch && !blobUrl.includes("uploads-cache")) {
        const blobPath = appContainerMatch[2];
        // Extract uploadId from path: entityType/entityId/year/month/{uploadId}/fileName
        const pathParts = blobPath.split("/");
        if (pathParts.length >= 5) {
          const uploadId = pathParts[pathParts.length - 2];
          context.log(`[BlobEventHandler] Source blob deleted, cleaning cache for uploadId=${uploadId}`);

          // Look up fileId from upload record
          const prisma = await ensurePrisma();
          const upload = await prisma.upload.findUnique({
            where: { id: uploadId },
            select: { fileId: true },
          });

          if (upload?.fileId) {
            try {
              await deleteBlobsByPrefix("uploads-cache", `${upload.fileId}_`);
              context.log(`[BlobEventHandler] Cache cleaned for fileId=${upload.fileId}`);
            } catch (cacheErr) {
              context.warn(`[BlobEventHandler] Cache cleanup failed for fileId=${upload.fileId}:`, cacheErr);
            }
          }
        }
      }
      return;
    }

    context.log(`[BlobEventHandler] Unhandled event: ${eventType}`);
  } catch (err) {
    context.error(`[BlobEventHandler] Error:`, err);
    // Don't rethrow — acknowledge the event to prevent infinite retry
  }
}

/**
 * Handle malware detection:
 * 1. Move blob to quarantine container
 * 2. Mark upload as FAILED
 * 3. Fire webhook (upload.failed)
 * 4. Audit log
 */
async function handleMalwareDetected(
  blobUrl: string,
  scanData: DefenderScanEventData,
  context: InvocationContext,
): Promise<void> {
  const malwareNames = scanData.scanResultDetails?.malwareNamesFound ?? ["unknown"];
  context.error(`[BlobEventHandler] MALWARE DETECTED: ${malwareNames.join(", ")} in ${blobUrl}`);

  // Parse container and path from URL
  // Format: https://account.blob.core.windows.net/container/path
  const urlParts = blobUrl.match(/\.blob\.core\.windows\.net\/([\w-]+)\/(.+)/);
  if (!urlParts) {
    context.error(`[BlobEventHandler] Could not parse blob URL: ${blobUrl}`);
    return;
  }

  const sourceContainer = urlParts[1];
  const blobPath = urlParts[2];

  // Extract appId from container name (uploads-{appId})
  const appIdMatch = sourceContainer.match(/^uploads-(.+)$/);
  if (!appIdMatch) {
    context.warn(`[BlobEventHandler] Non-app container, skipping: ${sourceContainer}`);
    return;
  }

  // Move blob to quarantine
  try {
    await moveBlob(sourceContainer, blobPath, "uploads-quarantine", `malware/${blobPath}`);
    context.log(`[BlobEventHandler] Blob quarantined: ${blobPath}`);
  } catch (moveErr) {
    context.error(`[BlobEventHandler] Failed to quarantine blob:`, moveErr);
  }

  // Find and update the upload record
  const prisma = await ensurePrisma();
  const pathParts = blobPath.split("/");
  const uploadId = pathParts.length >= 5 ? pathParts[pathParts.length - 2] : null;

  if (!uploadId) return;

  const upload = await prisma.upload.findUnique({ where: { id: uploadId } });
  if (!upload) return;

  // Mark upload as FAILED
  await prisma.upload.update({
    where: { id: uploadId },
    data: {
      status: "FAILED",
      errorMessage: `Malware detected: ${malwareNames.join(", ")}`,
    },
  });

  // Audit log
  logAudit(uploadId, upload.appId, "MALWARE_DETECTED", {
    malwareNames,
    sha256: scanData.scanResultDetails?.sha256,
    originalBlobUrl: blobUrl,
    quarantinePath: `malware/${blobPath}`,
  });

  // Webhook
  const appConfig = await prisma.appConfig.findUnique({ where: { appId: upload.appId } });
  if (appConfig?.webhookUrl) {
    const payload: WebhookPayload = {
      event: "upload.failed",
      timestamp: new Date().toISOString(),
      data: {
        uploadId,
        fileId: upload.fileId,
        appId: upload.appId,
        fileName: upload.fileName,
        fileSize: Number(upload.fileSize),
        mimeType: upload.mimeType,
        entityType: upload.entityType,
        entityId: upload.entityId,
        status: "FAILED",
        downloadUrl: null,
        isPublic: upload.isPublic,
        completedAt: null,
      },
    };
    dispatchWebhook(appConfig.webhookUrl, payload, appConfig.clientSecretHash, context);
  }

  context.log(`[BlobEventHandler] Upload ${uploadId} marked FAILED, webhook dispatched`);
}

app.eventGrid("BlobEventHandler", {
  handler,
});
