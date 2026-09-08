"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { PageShell } from "@/components/PageShell";
import { PhotoUploader } from "@/components/PhotoUploader";
import { CategoryPicker } from "@/components/CategoryPicker";
import { UploadProgress } from "@/components/UploadProgress";
import { Button } from "@/components/Button";
import { MapCanvas } from "@/components/MapCanvas";
import { LoadingState } from "@/components/LoadingState";
import { useCategories } from "@/lib/queries";
import { useComposer } from "@/lib/composer";
import { useAuth } from "@/lib/auth";
import { useGeolocation } from "@/lib/useGeolocation";
import { deviceId } from "@/lib/deviceId";
import { uploadPhoto, CLOUDINARY_CONFIGURED } from "@/lib/uploadPhoto";
import { ApiError, apiBase } from "@/lib/api";
import { fileSize, metres } from "@/lib/format";
import {
  composerSchema,
  ingestSchema,
  MANUAL_PIN_ACCURACY_M,
  MAX_ACCURACY_M,
  type ComposerValues,
} from "@/lib/schemas";
import type { ClusterResult } from "@/lib/types";

/**
 * Report composer (blueprint §3.2).
 *
 * Three sections on ONE SCROLLING PAGE, not a wizard. A wizard costs a tap per
 * step and provides no benefit when there are three inputs.
 *
 * Validation is React Hook Form driven by the Zod schema in lib/schemas.ts
 * (blueprint §6). Every rule in that schema mirrors one the server already
 * enforces, so the schema is the single statement of what a valid report is —
 * rather than a handful of `if` statements scattered through a submit handler,
 * which is what this screen had before and which put the 150 m accuracy rule
 * three levels away from the sentence that explains it.
 *
 * The performance budget here is stricter than every other screen combined,
 * which is why the map is not rendered unless manual pin placement is actually
 * needed: `MapCanvas` is a dynamic ssr:false import, so on the happy path
 * Leaflet is never downloaded.
 *
 * On a network failure NOTHING IS DISCARDED. The composed report lives in the
 * ComposerProvider above this screen, the photo stays compressed in memory, an
 * already-uploaded photo URL is remembered so a retry does not re-upload it,
 * and the button relabels to "Retry submission".
 */
export default function ReportComposerPage() {
  const router = useRouter();
  const categories = useCategories();
  const { report, patch } = useComposer();
  const { session } = useAuth();
  const { state: geo, locate } = useGeolocation();

  const [uploadFraction, setUploadFraction] = useState<number | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [submittedRef, setSubmittedRef] = useState<string | null>(null);

  const form = useForm<ComposerValues>({
    resolver: zodResolver(composerSchema),
    // Errors appear when a field is corrected rather than only on submit, so a
    // person who fixes their photo sees the error clear immediately.
    mode: "onSubmit",
    reValidateMode: "onChange",
    defaultValues: {
      categoryCode: report.categoryCode ?? "",
      lat: report.lat ?? undefined,
      lng: report.lng ?? undefined,
      accuracyM: report.accuracyM ?? undefined,
      manualPin: report.manualPin,
      description: report.description,
      landmark: report.landmark,
      hasPhoto: (report.photo != null || report.photoUrl != null) as true,
    },
  });

  const { register, handleSubmit, setValue, control, formState } = form;
  const errors = formState.errors;

  // `useWatch` rather than `watch()`: the latter returns a function the React
  // Compiler cannot memoise safely, and it re-renders the whole form on every
  // keystroke in any field. This subscribes to just these four values.
  const manualPin = useWatch({ control, name: "manualPin" });
  const lat = useWatch({ control, name: "lat" });
  const lng = useWatch({ control, name: "lng" });
  const categoryCode = useWatch({ control, name: "categoryCode" });

  // The automatic fix feeds the form. A manual pin, once placed, wins: the
  // person overrode the device on purpose.
  useEffect(() => {
    if (geo.kind !== "ok" || manualPin) return;
    setValue("lat", geo.lat, { shouldValidate: false });
    setValue("lng", geo.lng, { shouldValidate: false });
    setValue("accuracyM", geo.accuracyM, { shouldValidate: false });
    // Safe to call because `patch` is stable -- see ComposerProvider. If it
    // were recreated on every report change this would loop forever.
    patch({ lat: geo.lat, lng: geo.lng, accuracyM: geo.accuracyM });
  }, [geo, manualPin, setValue, patch]);

  /**
   * Navigate once the report is in, from an effect rather than from inside the
   * submit handler.
   *
   * Calling `router.push` inside React Hook Form's `handleSubmit` does not
   * navigate: RHF flips `isSubmitting` back to false when the handler's promise
   * settles, and that state update on this page supersedes the pending
   * transition. The push ran, returned, and the URL never changed -- with the
   * report already created server-side, which is the worst version of this bug,
   * because the citizen would have resubmitted something that had succeeded.
   *
   * Driving it from an effect keyed on the reference means navigation happens
   * after the submit lifecycle is over. The router is an external system, which
   * is what effects are for.
   */
  useEffect(() => {
    if (submittedRef) router.push(`/report/success/${submittedRef}`);
  }, [submittedRef, router]);

  const needsManualPin =
    geo.kind === "too-imprecise" || geo.kind === "denied" || geo.kind === "unavailable";

  function placePin(la: number, ln: number) {
    setValue("manualPin", true);
    setValue("lat", la, { shouldValidate: true });
    setValue("lng", ln, { shouldValidate: true });
    setValue("accuracyM", MANUAL_PIN_ACCURACY_M, { shouldValidate: true });
    patch({ manualPin: true, lat: la, lng: ln, accuracyM: MANUAL_PIN_ACCURACY_M });
  }

  const onSubmit = handleSubmit(async (values) => {
    setFailed(null);
    try {
      // A retry after a failed SUBMIT must not re-upload a photo that already
      // reached Cloudinary: it would cost the user their bandwidth twice and
      // leak an orphaned asset.
      let photoUrl = report.photoUrl;
      if (!photoUrl && report.photo) {
        setUploadFraction(0);
        photoUrl = (await uploadPhoto(report.photo.blob, setUploadFraction)).url;
        patch({ photoUrl });
      }
      setUploadFraction(null);

      // The real payload is validated too, now that the photo URL exists. The
      // composer schema could not check it: at that point the photo was a blob.
      const body = ingestSchema.parse({
        categoryCode: values.categoryCode,
        lat: values.lat,
        lng: values.lng,
        accuracyM: values.accuracyM,
        manualPin: values.manualPin,
        description: values.description || undefined,
        landmark: values.landmark || undefined,
        photoUrl,
        deviceId: deviceId(),
      });

      const res = await fetch(`${apiBase()}/reports`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          // Attached when present, never demanded (DD-017).
          ...(session ? { Authorization: `Bearer ${session.accessToken}` } : {}),
        },
        body: JSON.stringify(body),
      });

      if (!res.ok) throw new ApiError(res.status, await res.json().catch(() => null));

      const result = (await res.json()) as ClusterResult;
      try {
        window.sessionStorage.setItem(`ct:${result.publicRef}`, JSON.stringify(result));
      } catch {
        // Storage refused. The success screen refetches by reference instead.
      }
      setSubmittedRef(result.publicRef);
    } catch (e) {
      setUploadFraction(null);
      setFailed(
        e instanceof ApiError
          ? (e.problem?.detail ?? "The report could not be sent.")
          : "The report could not be sent. Your connection may have dropped.",
      );
    }
  });

  return (
    <PageShell>
      <h1 className="text-display">Report a problem</h1>

      {/* No action attribute and no server post: an event handler, per §6. */}
      <form onSubmit={onSubmit} noValidate>
        {/*
          `lat`, `lng`, `accuracyM`, `manualPin`, `hasPhoto` and `categoryCode`
          are form values with no visible control, so they are held in form
          state via setValue and are NOT registered to hidden inputs.

          An earlier version did register them, and it silently broke the whole
          submit: a DOM input stores strings, so `setValue("manualPin", true)`
          came back out as the string "true" and `z.boolean()` rejected it. The
          form then failed validation with no visible error, because the failing
          fields had no rendered control to attach a message to.
        */}
        {/* ---- 1. Photo ---- */}
        <section className="mt-6">
          <h2 className="text-heading">Photo</h2>
          <div className="mt-3">
            <PhotoUploader
              onChange={(img) => {
                patch({ photo: img, photoUrl: null });
                setValue("hasPhoto", (img != null) as true, { shouldValidate: formState.isSubmitted });
              }}
              error={errors.hasPhoto?.message}
            />
          </div>
        </section>

        {/* ---- 2. Location ---- */}
        <section className="mt-8">
          <h2 className="text-heading">Location</h2>

          {geo.kind === "locating" && <LoadingState label="Finding your location" />}

          {geo.kind === "ok" && !manualPin && (
            <p className="mt-2 text-dense">
              Located to within {metres(geo.accuracyM)}.{" "}
              <button
                type="button"
                className="underline bg-transparent border-0 p-0 cursor-pointer text-ink text-dense"
                onClick={() => placePin(geo.lat, geo.lng)}
              >
                Place the pin myself instead
              </button>
            </p>
          )}

          {/* Field-level, inline, specific. Blueprint §3.2's own example. */}
          {geo.kind === "too-imprecise" && (
            <p className="mt-2 text-dense" style={{ color: "var(--st-breached)" }}>
              GPS accuracy is {metres(geo.accuracyM)}. Place the pin on the map instead.
            </p>
          )}
          {geo.kind === "denied" && (
            <p className="mt-2 text-dense" style={{ color: "var(--st-breached)" }}>
              Location access is blocked for this site. Place the pin on the map instead.
            </p>
          )}
          {geo.kind === "unavailable" && (
            <p className="mt-2 text-dense" style={{ color: "var(--st-breached)" }}>
              Your device could not report a location. Place the pin on the map instead.
            </p>
          )}

          {(needsManualPin || manualPin) && (
            <div className="mt-3">
              <p className="text-meta text-ink-muted">
                Tap the map where the problem is. Accuracy worse than {MAX_ACCURACY_M} m is
                not precise enough for a crew to find it.
              </p>
              <div className="mt-2">
                {/* Leaflet is downloaded ONLY at this point. */}
                <MapCanvas
                  center={{ lat: lat ?? 30.9, lng: lng ?? 75.85 }}
                  zoom={17}
                  height={300}
                  onPinPlace={placePin}
                  markers={
                    lat != null && lng != null
                      ? [{ id: "pin", lat, lng, kind: "report" as const, color: "#14181A", label: "Your pin" }]
                      : []
                  }
                />
              </div>
              {lat != null && lng != null && (
                <p className="mt-2 text-meta text-ink-muted font-mono">
                  {lat.toFixed(5)}, {lng.toFixed(5)}
                </p>
              )}
            </div>
          )}

          {geo.kind !== "ok" && !manualPin && (
            <p className="mt-2">
              <button
                type="button"
                className="underline bg-transparent border-0 p-0 cursor-pointer text-ink text-dense"
                onClick={locate}
              >
                Try locating me again
              </button>
            </p>
          )}

          {(errors.lat || errors.lng || errors.accuracyM) && (
            <p className="mt-2 text-meta" style={{ color: "var(--st-breached)" }} role="alert">
              {errors.accuracyM?.message ?? errors.lat?.message ?? errors.lng?.message}
            </p>
          )}
        </section>

        {/* ---- 3. Details ---- */}
        <section className="mt-8">
          <h2 className="text-heading">Details</h2>

          <div className="mt-3">
            {categories.isPending && <LoadingState label="Loading categories" />}
            {categories.data && (
              <CategoryPicker
                categories={categories.data.map((c) => ({ code: c.code, displayName: c.displayName }))}
                value={categoryCode || undefined}
                onChange={(code) => {
                  setValue("categoryCode", code, { shouldValidate: true });
                  patch({ categoryCode: code });
                }}
              />
            )}
            {errors.categoryCode && (
              <p className="mt-2 text-meta" style={{ color: "var(--st-breached)" }} role="alert">
                {errors.categoryCode.message}
              </p>
            )}
          </div>

          <div className="mt-5 flex flex-col gap-4">
            <label className="flex flex-col gap-1" style={{ maxWidth: "var(--measure-form)" }}>
              <span className="text-meta">Landmark (optional)</span>
              <span className="text-meta text-ink-muted">Helps a crew find the exact spot.</span>
              <input
                {...register("landmark", { onChange: (e) => patch({ landmark: e.target.value }) })}
                maxLength={200}
                placeholder="Near the bus stop"
                className="bg-surface-raised border border-rule px-3 text-body"
                style={{ minHeight: "var(--hit-min)", borderRadius: "var(--radius)" }}
              />
            </label>

            <label className="flex flex-col gap-1" style={{ maxWidth: "var(--measure-form)" }}>
              <span className="text-meta">Description (optional)</span>
              <textarea
                {...register("description", { onChange: (e) => patch({ description: e.target.value }) })}
                maxLength={2000}
                rows={3}
                className="bg-surface-raised border border-rule px-3 py-2 text-body"
                style={{ borderRadius: "var(--radius)" }}
              />
            </label>
          </div>
        </section>

        {/* ---- Submit ---- */}
        <section
          className="mt-8 sticky bottom-0 bg-surface pt-3 pb-4 border-t border-rule"
          style={{ marginLeft: -16, marginRight: -16, paddingLeft: 16, paddingRight: 16 }}
        >
          {uploadFraction !== null && (
            <div className="mb-3">
              <UploadProgress fraction={uploadFraction} />
            </div>
          )}

          {failed && (
            <p className="mb-3 text-dense" style={{ color: "var(--st-breached)" }} role="alert">
              {failed} Nothing was lost — your photo and details are still here.
            </p>
          )}

          <Button type="submit" disabled={formState.isSubmitting} className="w-full">
            {formState.isSubmitting
              ? "Sending…"
              : failed
                ? "Retry submission"
                : report.photo
                  ? `Send report · ${fileSize(report.photo.bytes)}`
                  : "Send report"}
          </Button>

          {!session && (
            // The one place report §12.6's limitation is surfaced to a citizen.
            <p className="mt-2 text-meta text-ink-muted">
              You are reporting anonymously. An anonymous report cannot be used to
              confirm the fix later.{" "}
              <a href="/login" className="underline text-ink">
                Sign in first
              </a>{" "}
              if you want to be asked.
            </p>
          )}

          {!CLOUDINARY_CONFIGURED && (
            <p className="mt-2 text-meta text-ink-muted">
              Photo hosting is not configured, so a placeholder image URL is sent.
              Everything else works normally.
            </p>
          )}
        </section>
      </form>
    </PageShell>
  );
}
