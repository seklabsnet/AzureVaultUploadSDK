import { encode } from "blurhash";
import { readBlob } from "../shared/storage.js";

/**
 * Generate a BlurHash string for an image stored in Azure Blob Storage.
 * Resizes the image to 32x32 and encodes with 4x3 components.
 * Only processes image/* MIME types.
 *
 * Sharp is loaded dynamically — if unavailable, returns null gracefully.
 *
 * @returns The BlurHash string, or null on error or non-image MIME type.
 */
export async function generateBlurHash(
  containerName: string,
  blobPath: string,
  mimeType?: string | null,
): Promise<string | null> {
  if (!mimeType || !mimeType.startsWith("image/")) {
    return null;
  }

  try {
    // @ts-ignore — sharp is optional; may not be available in all runtimes
    const sharpModule = await import("sharp");
    const sharpFn = sharpModule.default ?? sharpModule;

    const buffer = await readBlob(containerName, blobPath);

    const { data, info } = await sharpFn(buffer)
      .resize(32, 32, { fit: "cover" })
      .ensureAlpha()
      .raw()
      .toBuffer({ resolveWithObject: true });

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
