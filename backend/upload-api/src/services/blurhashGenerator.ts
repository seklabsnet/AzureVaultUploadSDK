import { encode } from "blurhash";
import sharp from "sharp";
import { readBlob } from "../shared/storage.js";

/**
 * Generate a BlurHash string for an image stored in Azure Blob Storage.
 * Resizes the image to 32x32 and encodes with 4x3 components.
 * Only processes image/* MIME types.
 *
 * @returns The BlurHash string, or null on error or non-image MIME type.
 */
export async function generateBlurHash(
  containerName: string,
  blobPath: string,
  mimeType?: string | null,
): Promise<string | null> {
  // Only process image/* MIME types
  if (!mimeType || !mimeType.startsWith("image/")) {
    return null;
  }

  try {
    // Read the blob from Azure Storage
    const buffer = await readBlob(containerName, blobPath);

    // Use Sharp to resize to 32x32 and extract raw pixel data
    const { data, info } = await sharp(buffer)
      .resize(32, 32, { fit: "cover" })
      .ensureAlpha()
      .raw()
      .toBuffer({ resolveWithObject: true });

    // Encode with blurhash (components: 4 horizontal, 3 vertical)
    const hash = encode(
      new Uint8ClampedArray(data),
      info.width,
      info.height,
      4,
      3,
    );

    return hash;
  } catch (err) {
    console.error(`[blurhashGenerator] Failed to generate BlurHash for ${blobPath}:`, err);
    return null;
  }
}
