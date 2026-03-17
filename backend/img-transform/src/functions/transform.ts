import { app, HttpRequest, HttpResponseInit, InvocationContext } from "@azure/functions";
import sharp from "sharp";
import { readBlob, writeBlob, blobExists } from "../shared/storage.js";

const MAX_WIDTH = parseInt(process.env.MAX_WIDTH || "2048");
const MAX_HEIGHT = parseInt(process.env.MAX_HEIGHT || "2048");
const CACHE_CONTAINER = process.env.CACHE_CONTAINER || "uploads-cache";

const ALLOWED_FORMATS = new Set(["webp", "avif", "jpeg", "jpg", "png"]);
const FIT_MODES: Record<string, keyof sharp.FitEnum> = {
  cover: "cover",
  contain: "contain",
  "scale-down": "inside",
};

interface TransformParams {
  fileId: string;
  width?: number;
  height?: number;
  fit: keyof sharp.FitEnum;
  quality: number;
  format: keyof sharp.FormatEnum;
}

function parseParams(req: HttpRequest): TransformParams {
  const fileId = req.params.fileId;
  if (!fileId) throw new Error("fileId is required");

  const w = req.query.get("w");
  const h = req.query.get("h");
  const fit = req.query.get("fit") || "cover";
  const q = req.query.get("q") || "80";
  const f = req.query.get("f") || "webp";

  const width = w ? parseInt(w) : undefined;
  const height = h ? parseInt(h) : undefined;

  if (width && (width < 1 || width > MAX_WIDTH)) {
    throw new Error(`Width must be between 1 and ${MAX_WIDTH}`);
  }
  if (height && (height < 1 || height > MAX_HEIGHT)) {
    throw new Error(`Height must be between 1 and ${MAX_HEIGHT}`);
  }

  const quality = Math.min(100, Math.max(1, parseInt(q)));
  const format = f === "jpg" ? "jpeg" : f;

  if (!ALLOWED_FORMATS.has(format)) {
    throw new Error(`Format must be one of: ${[...ALLOWED_FORMATS].join(", ")}`);
  }

  return {
    fileId,
    width,
    height,
    fit: FIT_MODES[fit] || "cover",
    quality,
    format: format as keyof sharp.FormatEnum,
  };
}

function buildCachePath(params: TransformParams): string {
  const w = params.width || 0;
  const h = params.height || 0;
  const fit = Object.entries(FIT_MODES).find(([, v]) => v === params.fit)?.[0] || "cover";
  return `${params.fileId}/w${w}_h${h}_${fit}_q${params.quality}.${params.format}`;
}

function getContentType(format: string): string {
  const types: Record<string, string> = {
    webp: "image/webp",
    avif: "image/avif",
    jpeg: "image/jpeg",
    png: "image/png",
  };
  return types[format] || "application/octet-stream";
}

// Resolve fileId → original blob location via mapping blob in uploads-cache
async function findOriginalBlob(fileId: string): Promise<{ container: string; path: string } | null> {
  try {
    const mappingPath = `${fileId}/_source.json`;
    const data = await readBlob(CACHE_CONTAINER, mappingPath);
    const mapping = JSON.parse(data.toString());
    return { container: mapping.container, path: mapping.path };
  } catch {
    return null;
  }
}

async function handler(req: HttpRequest, context: InvocationContext): Promise<HttpResponseInit> {
  try {
    const params = parseParams(req);
    const cachePath = buildCachePath(params);
    const contentType = getContentType(params.format as string);

    // Layer 2: Check transform cache
    if (await blobExists(CACHE_CONTAINER, cachePath)) {
      context.log(`Cache HIT: ${cachePath}`);
      const cached = await readBlob(CACHE_CONTAINER, cachePath);
      return {
        status: 200,
        headers: {
          "Content-Type": contentType,
          "Cache-Control": "public, max-age=604800",
          "X-Cache": "HIT",
        },
        body: cached,
      };
    }

    // Layer 3: Transform from original
    context.log(`Cache MISS: ${cachePath} — transforming from original`);

    // Resolve original blob: mapping blob first, query params as fallback
    const source = await findOriginalBlob(params.fileId);
    const srcContainer = source?.container ?? req.query.get("src_container");
    const srcPath = source?.path ?? req.query.get("src_path");

    if (!srcContainer || !srcPath) {
      return {
        status: 404,
        jsonBody: { error: "File not found. No source mapping exists for this fileId." },
      };
    }

    context.log(`Source resolved: ${srcContainer}/${srcPath}${source ? " (mapping)" : " (query params)"}`);
    const original = await readBlob(srcContainer, srcPath);

    // Transform with Sharp
    let pipeline = sharp(original);

    if (params.width || params.height) {
      pipeline = pipeline.resize(params.width, params.height, {
        fit: params.fit,
        withoutEnlargement: true,
      });
    }

    const transformed = await pipeline
      .toFormat(params.format, { quality: params.quality })
      .toBuffer();

    // Write to cache (fire and forget)
    writeBlob(CACHE_CONTAINER, cachePath, transformed, contentType).catch((err) => {
      context.error(`Failed to write cache: ${err.message}`);
    });

    return {
      status: 200,
      headers: {
        "Content-Type": contentType,
        "Cache-Control": "public, max-age=604800",
        "X-Cache": "MISS",
      },
      body: transformed,
    };
  } catch (err: any) {
    context.error(`Transform error: ${err.message}`);
    return {
      status: err.message?.includes("must be") ? 400 : 500,
      jsonBody: { error: err.message },
    };
  }
}

app.http("ImageTransform", {
  methods: ["GET"],
  authLevel: "anonymous",
  route: "{fileId}",
  handler,
});
