import { app, EventGridEvent, InvocationContext } from "@azure/functions";

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

async function handler(event: EventGridEvent, context: InvocationContext): Promise<void> {
  const eventType = event.eventType;
  const data = event.data as BlobEventData | undefined;
  const blobUrl = data?.url ?? "unknown";

  context.log(`[BlobEventHandler] Received event: type=${eventType}, blobUrl=${blobUrl}`);

  try {
    if (eventType === "Microsoft.Storage.BlobCreated") {
      context.log(`[BlobEventHandler] BlobCreated: ${blobUrl}`);

      // Check if this is an upload in an app container (uploads-{appId})
      const uploadContainerMatch = blobUrl.match(/uploads-([a-zA-Z0-9-]+)\//);
      if (uploadContainerMatch) {
        const appId = uploadContainerMatch[1];
        context.log(`[BlobEventHandler] Blob created in app container: appId=${appId}`);

        // TODO: Trigger malware scan result check via Defender for Storage
        // The scan happens automatically; here we could poll for results
        // or rely on a separate Defender event subscription.
      }
    } else if (eventType === "Microsoft.Storage.BlobDeleted") {
      context.log(`[BlobEventHandler] BlobDeleted: ${blobUrl}`);

      // Check if a cached blob was deleted; if so, clean up related cache entries
      const cacheMatch = blobUrl.match(/uploads-cache\/([a-zA-Z0-9-]+)\//);
      if (cacheMatch) {
        const fileId = cacheMatch[1];
        context.log(`[BlobEventHandler] Cache blob deleted for fileId=${fileId}. Consider cleaning up related cache variants.`);

        // TODO: Delete all cache variants for this fileId
        // e.g., uploads-cache/{fileId}/w_400.jpg, uploads-cache/{fileId}/w_800.jpg, etc.
      }
    } else {
      context.log(`[BlobEventHandler] Unhandled event type: ${eventType}`);
    }
  } catch (err) {
    context.error(`[BlobEventHandler] Error processing event:`, err);
    // Do not rethrow — let Event Grid know the event was received.
    // Failed processing can be retried via dead-letter queue.
  }
}

app.eventGrid("BlobEventHandler", {
  handler,
});
