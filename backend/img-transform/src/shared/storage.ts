import { BlobServiceClient } from "@azure/storage-blob";
import { DefaultAzureCredential } from "@azure/identity";

let client: BlobServiceClient;

export function getStorageClient(): BlobServiceClient {
  if (!client) {
    const accountName = process.env.StorageAccountName!;
    client = new BlobServiceClient(
      `https://${accountName}.blob.core.windows.net`,
      new DefaultAzureCredential(),
    );
  }
  return client;
}

export async function readBlob(containerName: string, blobPath: string): Promise<Buffer> {
  const client = getStorageClient();
  const containerClient = client.getContainerClient(containerName);
  const blobClient = containerClient.getBlobClient(blobPath);
  const download = await blobClient.download(0);

  const chunks: Buffer[] = [];
  for await (const chunk of download.readableStreamBody as NodeJS.ReadableStream) {
    chunks.push(Buffer.from(chunk as ArrayBuffer));
  }
  return Buffer.concat(chunks);
}

export async function writeBlob(
  containerName: string,
  blobPath: string,
  data: Buffer,
  contentType: string,
): Promise<void> {
  const client = getStorageClient();
  const containerClient = client.getContainerClient(containerName);
  const blockBlobClient = containerClient.getBlockBlobClient(blobPath);
  await blockBlobClient.upload(data, data.length, {
    blobHTTPHeaders: {
      blobContentType: contentType,
      blobCacheControl: "public, max-age=604800", // 7 days
    },
  });
}

export async function blobExists(containerName: string, blobPath: string): Promise<boolean> {
  const client = getStorageClient();
  const containerClient = client.getContainerClient(containerName);
  const blobClient = containerClient.getBlobClient(blobPath);
  return blobClient.exists();
}
