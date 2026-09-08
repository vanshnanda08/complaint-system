import { z } from "zod";

/**
 * The client-side contract, as Zod.
 *
 * Every rule here mirrors one the server already enforces. That duplication is
 * deliberate and it is not redundancy for its own sake: bean validation and the
 * clustering engine give a caller a correct refusal, but they give it *after* a
 * photo has been uploaded and a round trip spent. Validating the same rules
 * before submit turns a server rejection into something the person can fix
 * while standing in front of the pothole.
 *
 * Where a rule exists on the server, the comment names it. If one changes there
 * and not here, the failure mode is a rejected submission with a good server
 * message — annoying, not silent — which is the right direction for the two to
 * drift.
 */

/** `civictrack.clustering.max-accuracy-m`. Above this the server refuses. */
export const MAX_ACCURACY_M = 150;

/** Blueprint §3.2: a manual pin records manual_pin = true and accuracy 10. */
export const MANUAL_PIN_ACCURACY_M = 10;

/** `ResolveRequest.note` is @Size(min = 20) on the server. */
export const MIN_PROOF_NOTE = 20;

/**
 * What the composer requires before it will attempt a submit.
 *
 * `photoUrl` is absent on purpose: the photo is uploaded during submit, so at
 * validation time all that exists is a compressed blob in memory. `hasPhoto`
 * stands in for it, and `ingestSchema` below validates the real payload once
 * the URL exists.
 */
export const composerSchema = z.object({
  categoryCode: z.string().min(1, { message: "Choose what the problem is." }),

  lat: z
    .number({ message: "Place the pin on the map so a crew can find the problem." })
    .min(-90)
    .max(90),
  lng: z
    .number({ message: "Place the pin on the map so a crew can find the problem." })
    .min(-180)
    .max(180),

  accuracyM: z
    .number({ message: "A location is required." })
    .positive({ message: "A location is required." })
    .max(MAX_ACCURACY_M, {
      message: `GPS accuracy is worse than ${MAX_ACCURACY_M} m. Place the pin on the map instead.`,
    }),

  manualPin: z.boolean(),

  // Mirrors @Size(max = 2000) and @Size(max = 200) on IngestReportRequest.
  description: z.string().max(2000).optional().or(z.literal("")),
  landmark: z.string().max(200).optional().or(z.literal("")),

  hasPhoto: z.literal(true, { message: "A photo is required." }),
});

export type ComposerValues = z.infer<typeof composerSchema>;

/**
 * The actual POST /api/v1/reports body.
 *
 * `photoUrl` is @NotBlank on the server, which is why a placeholder URL in
 * local development still has to be a non-empty string rather than null.
 */
export const ingestSchema = composerSchema.omit({ hasPhoto: true }).extend({
  photoUrl: z.string().min(1, { message: "The photo failed to upload." }),
  deviceId: z.string().max(64),
});

export type IngestBody = z.infer<typeof ingestSchema>;

/**
 * Submitting a fix for citizen verification.
 *
 * Both rules are the server's: `proofPhotoUrl` @NotBlank and `note`
 * @Size(min = 20). The note minimum is not arbitrary formality — the note is
 * shown to every person who reported the issue, and "done" is not an account of
 * what was done.
 */
export const proofSchema = z.object({
  note: z
    .string()
    .trim()
    .min(MIN_PROOF_NOTE, {
      message: `Describe what was done in at least ${MIN_PROOF_NOTE} characters. Everyone who reported this will read it.`,
    })
    .max(2000),
  hasPhoto: z.literal(true, { message: "A photo of the finished work is required." }),
});

export type ProofValues = z.infer<typeof proofSchema>;

export const loginSchema = z.object({
  email: z.string().min(1, { message: "Enter your email." }).email({ message: "That is not an email address." }),
  password: z.string().min(1, { message: "Enter your password." }),
});

export type LoginValues = z.infer<typeof loginSchema>;

export const registerSchema = loginSchema.extend({
  fullName: z.string().trim().min(1, { message: "Enter your name." }).max(160),
  password: z
    .string()
    .min(8, { message: "Use at least 8 characters." })
    .max(200),
});

export type RegisterValues = z.infer<typeof registerSchema>;
