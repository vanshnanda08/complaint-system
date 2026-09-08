"use client";

import Image from "next/image";
import { useState } from "react";

/**
 * A photo that degrades instead of breaking.
 *
 * Every image in this application comes from a URL stored on a report or an
 * issue, which means the application does not control whether it still
 * resolves. Three ways it can fail in practice:
 *
 *   - Cloudinary is not configured locally, so reports carry a placeholder URL.
 *   - The phase-5 cleanup job deletes assets with no corresponding report row,
 *     and a race there leaves a live report pointing at a deleted asset.
 *   - A citizen's photo is removed on request.
 *
 * A broken-image icon says "this site is broken". A labelled panel says "this
 * photo is gone", which is the true statement and the one that does not make a
 * reader distrust the rest of the page.
 */
export interface PhotoProps {
  src: string | null | undefined;
  alt: string;
  width: number;
  height: number;
  className?: string;
  style?: React.CSSProperties;
}

export function Photo({ src, alt, width, height, className = "", style }: PhotoProps) {
  const [failed, setFailed] = useState(false);

  if (!src || failed) {
    return (
      <div
        className={`flex items-center justify-center bg-surface border border-rule ${className}`}
        style={{ width, height, borderRadius: "var(--radius)", ...style }}
        role="img"
        aria-label={`${alt} — photo unavailable`}
      >
        <span className="text-meta text-ink-muted px-2 text-center">Photo unavailable</span>
      </div>
    );
  }

  return (
    <Image
      src={src}
      alt={alt}
      width={width}
      height={height}
      unoptimized
      onError={() => setFailed(true)}
      className={className}
      style={style}
    />
  );
}
