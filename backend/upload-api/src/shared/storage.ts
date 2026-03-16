import {
  BlobSASPermissions,
  BlobServiceClient,
  SASProtocol,
  StorageSharedKeyCredential,
  generateBlobSASQueryParameters,
} from "@azure/storage-blob";
import { DefaultAzureCredential } from "@azure/identity";

let blobServiceClient: BlobServiceClient;

export function getStorageClient(): BlobServiceClient {
  if (!blobServiceClient) {
    const accountName = process.env.StorageAccountName;
    if (!accountName) {
      throw new Error("StorageAccountName environment variable is not set");
    }
    const credential = new DefaultAzureCredential();
    blobServiceClient = new BlobServiceClient(
      `https://${accountName}.blob.core.windows.net`,
      credential,
    );
  }
  return blobServiceClient;
}

export async function generateSasToken(
  containerName: string,
  blobPath: string,
  permissions: string,
  expiresInMinutes: number,
): Promise<{ sasToken: string; blobUrl: string; expiresAt: Date }> {
  const client = getStorageClient();
  const now = new Date();
  const expiresAt = new Date(now.getTime() + expiresInMinutes * 60 * 1000);

  // Use User Delegation Key for SAS generation (no storage account key needed)
  const delegationKey = await client.getUserDelegationKey(now, expiresAt);

  const accountName = process.env.StorageAccountName!;

  const sasParams = generateBlobSASQueryParameters(
    {
      containerName,
      blobName: blobPath,
      permissions: BlobSASPermissions.parse(permissions),
      startsOn: now,
      expiresOn: expiresAt,
      protocol: SASProtocol.Https,
    },
    delegationKey,
    accountName,
  );

  const sasToken = sasParams.toString();
  const blobUrl = `https://${accountName}.blob.core.windows.net/${containerName}/${blobPath}`;

  return { sasToken, blobUrl, expiresAt };
}

export async function commitBlockList(
  containerName: string,
  blobPath: string,
  blockIds: string[],
): Promise<void> {
  const client = getStorageClient();
  const containerClient = client.getContainerClient(containerName);
  const blockBlobClient = containerClient.getBlockBlobClient(blobPath);

  await blockBlobClient.commitBlockList(blockIds);
}

export async function deleteBlob(
  containerName: string,
  blobPath: string,
): Promise<void> {
  const client = getStorageClient();
  const containerClient = client.getContainerClient(containerName);
  const blobClient = containerClient.getBlobClient(blobPath);

  await blobClient.deleteIfExists();
}

/**
 * Move a blob from one container/path to another (copy + delete source).
 */
export async function moveBlob(
  sourceContainer: string,
  sourcePath: string,
  destContainer: string,
  destPath: string,
): Promise<void> {
  const client = getStorageClient();
  const sourceClient = client.getContainerClient(sourceContainer).getBlobClient(sourcePath);
  const destClient = client.getContainerClient(destContainer).getBlobClient(destPath);

  // Copy source → dest
  const poller = await destClient.beginCopyFromURL(sourceClient.url);
  await poller.pollUntilDone();

  // Delete source
  await sourceClient.deleteIfExists();
}

/**
 * Delete all blobs in a container that match a prefix.
 * Used for cache cleanup (e.g., delete all transform variants of a file).
 */
export async function deleteBlobsByPrefix(
  containerName: string,
  prefix: string,
): Promise<number> {
  const client = getStorageClient();
  const containerClient = client.getContainerClient(containerName);
  let deleted = 0;

  for await (const blob of containerClient.listBlobsFlat({ prefix })) {
    await containerClient.getBlobClient(blob.name).deleteIfExists();
    deleted++;
  }

  return deleted;
}

export function getBlobUrl(containerName: string, blobPath: string): string {
  const accountName = process.env.StorageAccountName;
  if (!accountName) {
    throw new Error("StorageAccountName environment variable is not set");
  }
  return `https://${accountName}.blob.core.windows.net/${containerName}/${blobPath}`;
}
