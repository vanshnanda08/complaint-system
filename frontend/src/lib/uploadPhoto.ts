/**
 * Uploading the compressed photo, client-side, straight to Cloudinary.
 *
 * The backend's ingest endpoint takes `{ photoUrl }` -- a URL string, not a
 * file -- and is deliberately not being converted to multipart in this phase.
 * Server-side Cloudinary arrives in phase 5, and when it does the upload will
 * still happen BEFORE the ingest transaction opens, because a slow third-party
 * call must never be made while holding the clustering advisory lock.
 *
 * With no preset configured the upload is skipped and a placeholder URL is
 * returned, so the whole composer flow is exercisable locally without a
 * Cloudinary account. `photoUrl` is @NotBlank on the server, so the
 * placeholder must be a real non-empty string.
 */

const CLOUD = process.env.NEXT_PUBLIC_CLOUDINARY_CLOUD_NAME;
const PRESET = process.env.NEXT_PUBLIC_CLOUDINARY_UPLOAD_PRESET;

export const CLOUDINARY_CONFIGURED = Boolean(CLOUD && PRESET);

/**
 * Stands in for a hosted photo in local development.
 *
 * This must be a URL that actually resolves. An earlier value pointed at a path
 * invented for this project on Cloudinary's public demo account, which 404s --
 * so every photo on every issue page rendered as a broken image, and the whole
 * application looked broken to anybody running it without a Cloudinary account.
 * `sample.jpg` is a real, long-standing asset on that demo account.
 */
export const PLACEHOLDER_PHOTO_URL = "https://res.cloudinary.com/demo/image/upload/sample.jpg";

export interface UploadResult {
  url: string;
  placeholder: boolean;
}

/**
 * Delivery transformation, inserted into the URL that gets STORED.
 *
 * `f_auto` serves AVIF or WebP to browsers that accept them and the original
 * to those that do not; `q_auto` picks a quality per image rather than a fixed
 * number. Together they typically halve the bytes again on top of the
 * client-side compression, and on this project that is the whole point: the
 * reader is assumed to be on a cheap phone on a weak connection, and the photo
 * is the largest thing on the page.
 *
 * `w_1600,c_limit` is a ceiling, not a resize -- `c_limit` never enlarges, so
 * an image already smaller passes through untouched. The composer downscales
 * to 1600px before upload anyway; this stops a future change to that number
 * from quietly shipping 4000px images to phones.
 *
 * Transforming on DELIVERY rather than on upload keeps the original in
 * Cloudinary. That matters for the one case that actually comes up: deciding
 * later that 1600px was too small, which is a URL change rather than an
 * unrecoverable loss.
 *
 * Written as a string edit rather than with the Cloudinary SDK because the SDK
 * is a dependency for one line of string manipulation, and this runs in the
 * browser bundle where every kilobyte is on the critical path.
 */
const DELIVERY = "f_auto,q_auto,w_1600,c_limit";

export function withDelivery(secureUrl: string): string {
  // Never generate a derived asset on the `demo` account. That is Cloudinary's
  // public demo cloud, which this project does not own, and it is where
  // PLACEHOLDER_PHOTO_URL points when no preset is configured. Requesting a
  // transformation there bills a derived asset against somebody else's quota
  // for no benefit, and may simply 404 if they restrict derivations.
  if (secureUrl.includes("/res.cloudinary.com/demo/")) return secureUrl;

  // Only touch a URL shaped the way Cloudinary's upload API returns one. If
  // the shape ever changes, return it untransformed rather than corrupt it --
  // a slightly larger photo is a much better failure than a broken one.
  const marker = "/image/upload/";
  const at = secureUrl.indexOf(marker);
  if (at === -1) return secureUrl;

  const rest = secureUrl.slice(at + marker.length);
  // Do not stack transformations if one is somehow already present.
  if (/^[a-z]{1,2}_[^/]*\//.test(rest)) return secureUrl;

  return `${secureUrl.slice(0, at + marker.length)}${DELIVERY}/${rest}`;
}

/**
 * Uploads and reports determinate progress.
 *
 * XMLHttpRequest rather than fetch, and that is not nostalgia: fetch has no
 * upload progress event. Blueprint §3.2 requires a determinate bar because
 * users abandon indeterminate spinners, and the photo upload is both the slow
 * part of the flow and the one place a real percentage exists.
 */
export function uploadPhoto(
  blob: Blob,
  onProgress?: (fraction: number) => void,
  signal?: AbortSignal,
): Promise<UploadResult> {
  if (!CLOUDINARY_CONFIGURED) {
    onProgress?.(1);
    return Promise.resolve({ url: PLACEHOLDER_PHOTO_URL, placeholder: true });
  }

  return new Promise((resolve, reject) => {
    const form = new FormData();
    form.append("file", blob);
    form.append("upload_preset", PRESET!);

    const xhr = new XMLHttpRequest();
    xhr.open("POST", `https://api.cloudinary.com/v1_1/${CLOUD}/image/upload`);

    xhr.upload.addEventListener("progress", (e) => {
      if (e.lengthComputable) onProgress?.(e.loaded / e.total);
    });

    xhr.addEventListener("load", () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          const url = withDelivery(JSON.parse(xhr.responseText).secure_url as string);
          resolve({ url, placeholder: false });
        } catch {
          reject(new Error("The photo service returned something unexpected."));
        }
      } else {
        reject(new Error("The photo could not be uploaded. Your report was not sent."));
      }
    });

    xhr.addEventListener("error", () =>
      reject(new Error("The photo could not be uploaded. Check your connection.")),
    );
    xhr.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")));

    signal?.addEventListener("abort", () => xhr.abort());
    xhr.send(form);
  });
}
