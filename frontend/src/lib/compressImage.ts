/**
 * Client-side image compression, before anything is uploaded.
 *
 * Blueprint §3.2 budgets ~300 KB after compression with the longest edge at
 * 1600px. The reason is the venue and the field: a 4 MB phone photo over a
 * weak connection is the difference between a report submitted in twenty
 * seconds and a report abandoned.
 *
 * Two properties worth stating because they are easy to get wrong:
 *
 * 1. The preview shown to the user is the COMPRESSED image, not the original.
 *    Showing the original would let somebody approve a photo that is not the
 *    photo being sent, and the whole point of compressing is that the result
 *    is still legible enough to prove a pothole.
 *
 * 2. Quality is stepped down until the size target is met, rather than fixed.
 *    A fixed quality produces wildly different sizes for a flat wall and a
 *    detailed street scene, and it is the street scene that matters here.
 */

export interface CompressedImage {
  blob: Blob;
  /** Object URL of the compressed result. The caller must revoke it. */
  previewUrl: string;
  width: number;
  height: number;
  bytes: number;
  originalBytes: number;
}

const MAX_EDGE = 1600;
const TARGET_BYTES = 300 * 1024;
const QUALITY_STEPS = [0.82, 0.72, 0.62, 0.52, 0.42];

/** Types we will actually send. Checked before any work, per blueprint §4. */
const ACCEPTED = ["image/jpeg", "image/png", "image/webp", "image/heic", "image/heif"];

export class UnsupportedImageError extends Error {
  constructor(type: string) {
    super(
      `That file is a ${type || "unknown type"}. Choose a photo — JPEG, PNG or WebP.`,
    );
  }
}

export async function compressImage(file: File): Promise<CompressedImage> {
  if (!ACCEPTED.includes(file.type)) {
    throw new UnsupportedImageError(file.type);
  }

  const bitmap = await createImageBitmap(file);
  try {
    const scale = Math.min(1, MAX_EDGE / Math.max(bitmap.width, bitmap.height));
    const width = Math.round(bitmap.width * scale);
    const height = Math.round(bitmap.height * scale);

    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("Could not prepare the photo for upload.");
    ctx.drawImage(bitmap, 0, 0, width, height);

    let blob: Blob | null = null;
    for (const quality of QUALITY_STEPS) {
      blob = await toBlob(canvas, quality);
      if (blob && blob.size <= TARGET_BYTES) break;
    }
    if (!blob) throw new Error("Could not prepare the photo for upload.");

    return {
      blob,
      previewUrl: URL.createObjectURL(blob),
      width,
      height,
      bytes: blob.size,
      originalBytes: file.size,
    };
  } finally {
    bitmap.close();
  }
}

function toBlob(canvas: HTMLCanvasElement, quality: number): Promise<Blob | null> {
  return new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", quality));
}
