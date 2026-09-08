"use client";

import { useEffect, useRef, useState } from "react";
import { Photo } from "./Photo";
import { compressImage, UnsupportedImageError, type CompressedImage } from "@/lib/compressImage";
import { fileSize } from "@/lib/format";

/**
 * Photo capture, compression, preview.
 *
 * `capture="environment"` opens the rear camera directly on mobile, which is
 * the whole flow for somebody standing in front of a pothole.
 *
 * The preview is the COMPRESSED image, so what the user approves is what gets
 * sent. The size is shown for the same reason blueprint §3.2 puts it on the
 * submit button: a person on a weak connection is entitled to know what they
 * are about to spend.
 *
 * This component never uploads. It hands the caller a compressed blob and the
 * caller decides when to send it -- which is what makes "hold the composed
 * report in memory and relabel the button Retry submission" possible without
 * recompressing or, worse, losing the photo.
 */

export interface PhotoUploaderProps {
  onChange: (image: CompressedImage | null) => void;
  error?: string;
}

export function PhotoUploader({ onChange, error }: PhotoUploaderProps) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [image, setImage] = useState<CompressedImage | null>(null);
  const [busy, setBusy] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);

  // Object URLs are a manual allocation. Revoking on replace and on unmount
  // keeps a composer that has been through several retries from leaking a
  // blob per attempt.
  useEffect(() => {
    return () => {
      if (image) URL.revokeObjectURL(image.previewUrl);
    };
  }, [image]);

  async function handleFile(file: File | undefined) {
    if (!file) return;
    setBusy(true);
    setLocalError(null);
    try {
      const compressed = await compressImage(file);
      setImage((previous) => {
        if (previous) URL.revokeObjectURL(previous.previewUrl);
        return compressed;
      });
      onChange(compressed);
    } catch (e) {
      const message =
        e instanceof UnsupportedImageError
          ? e.message
          : "That photo could not be read. Try taking it again.";
      setLocalError(message);
      onChange(null);
    } finally {
      setBusy(false);
    }
  }

  const shown = localError ?? error;

  return (
    <div className="flex flex-col gap-2">
      <label htmlFor="photo" className="text-meta text-ink">
        Photo
      </label>

      <input
        ref={inputRef}
        id="photo"
        type="file"
        accept="image/*"
        capture="environment"
        className="sr-only"
        onChange={(e) => handleFile(e.target.files?.[0])}
      />

      <button
        type="button"
        onClick={() => inputRef.current?.click()}
        className="flex items-center justify-center border border-ink bg-surface-raised text-dense cursor-pointer"
        style={{ minHeight: "var(--hit-min)", borderRadius: "var(--radius)" }}
        aria-describedby={shown ? "photo-error" : undefined}
      >
        {busy ? "Preparing photo…" : image ? "Take a different photo" : "Take a photo"}
      </button>

      {image && (
        <figure className="m-0">
          <Photo
            src={image.previewUrl}
            alt="The photo that will be sent with your report"
            width={image.width}
            height={image.height}
            className="w-full h-auto border border-rule"
            style={{ borderRadius: "var(--radius)" }}
          />
          <figcaption className="mt-1 text-meta text-ink-muted">
            {image.width}×{image.height}, {fileSize(image.bytes)} — reduced from{" "}
            {fileSize(image.originalBytes)}. This is exactly what gets sent.
          </figcaption>
        </figure>
      )}

      {shown && (
        <p id="photo-error" className="text-meta" style={{ color: "var(--st-breached)" }} role="alert">
          {shown}
        </p>
      )}
    </div>
  );
}
