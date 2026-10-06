/**
 * The API contract, as TypeScript.
 *
 * These mirror the backend records one for one. They are hand-written rather
 * than generated, and the risk that carries -- drifting from the server -- is
 * bounded by two things: every field here appears in an integration test on
 * the Java side, and the public shapes are locked by
 * `PublicApiIT.publicIssueNeverCarriesAnIdentity`, which asserts on serialised
 * JSON and fails if the server starts sending something new.
 *
 * Note what is absent from `PublicIssue`, and note that it is absent on the
 * server too: no `assignedTo`, no `resolvedBy`, no reporter or device id. If a
 * field is ever needed here that is not on the public DTO, the answer is a
 * decision about the public contract, not a cast.
 */

import type { IssueStatus } from "./status";

export type Priority = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

export type ClusterDecision =
  | "NEW_ISSUE"
  | "MERGED"
  | "MERGED_LOW_CONF"
  | "SPLIT_LOW_CONF"
  | "SPLIT_EXTENT_CAPPED"
  | "MANUAL";

export interface PublicIssue {
  id: string;
  publicRef: string;
  categoryCode: string;
  categoryName: string;
  wardId: string;
  wardNumber: number;
  wardName: string;
  departmentId: string | null;
  departmentName: string | null;
  status: IssueStatus;
  priority: Priority;
  priorityScore: number;
  lat: number;
  lng: number;
  reportCount: number;
  distinctReporterCount: number;
  needsReview: boolean;
  reviewReason: string | null;
  firstReportedAt: string;
  lastReportedAt: string;
  dueAt: string;
  pausedSeconds: number;
  /** dueAt + pausedSeconds, computed server-side. Never re-derive this. */
  effectiveDeadline: string;
  escalationLevel: number;
  resolvedAt: string | null;
  reopenCount: number;
  resolutionNote: string | null;
  resolutionPhotoUrl: string | null;
  resolvedWithoutVerification: boolean;
  mergeRadiusM: number;
  clusterExtentM: number;
  clusterExtentCapM: number;
  positionalUncertaintyM: number;
  /** DD-063: set when a supervisor merged this ticket into another. The reports went there. */
  mergedIntoId: string | null;
  mergedIntoRef: string | null;
}

export interface PublicReport {
  id: string;
  sequence: number;
  createdAt: string;
  lat: number;
  lng: number;
  gpsAccuracyM: number;
  manualPin: boolean;
  clusterDecision: ClusterDecision;
  clusterDistanceM: number | null;
  effectiveRadiusM: number | null;
  projectedExtentM: number | null;
  photoUrl: string;
  description: string | null;
  landmark: string | null;
}

export interface PublicHistoryEntry {
  fromStatus: IssueStatus | null;
  toStatus: IssueStatus;
  actorRole: string;
  note: string | null;
  createdAt: string;
}

export interface PublicIssuePage {
  items: PublicIssue[];
  total: number;
  limit: number;
  offset: number;
}

export interface BboxResult {
  items: PublicIssue[];
  cap: number;
  /** True when the server had more to give. The map must say so, not pretend. */
  capped: boolean;
}

export interface Category {
  code: string;
  displayName: string;
  mergeRadiusM: number;
  maxExtentMultiplier: number;
  extentCapM: number;
  defaultSlaHours: number;
  departmentId: string | null;
}

export interface Ward {
  id: string;
  wardNumber: number;
  name: string;
  /** GeoJSON, present only when requested with ?includeBoundary=true. */
  boundary?: unknown;
}

export interface DashboardSummary {
  /** Null means the query failed, NOT that nothing is overdue. Zero means zero. */
  overdueCount: number | null;
  totalResolved: number;
  topOverdueWard: { name: string; count: number } | null;
  topOverdueDepartment: { name: string; count: number } | null;
  generatedAt: string;
}

/**
 * What `GET /api/v1/issues/{id}` returns: the staff view, `IssueDto`.
 *
 * NOT `PublicIssue`. It is a different record on the server with a different
 * shape -- it carries `assignedTo`, and it does NOT carry `categoryName`,
 * `wardName` or the cluster geometry. Typing this endpoint as `PublicIssue`
 * was a real bug: `effectiveDeadline` came back undefined, `Intl` was handed an
 * invalid date, and the work view crashed with `RangeError: Invalid time value`
 * -- intermittently, because whether it threw depended on whether the shared
 * clock store had ticked yet.
 */
export interface StaffIssue {
  id: string;
  publicRef: string;
  categoryCode: string;
  wardId: string;
  departmentId: string | null;
  status: IssueStatus;
  priority: Priority;
  priorityScore: number;
  lat: number;
  lng: number;
  reportCount: number;
  distinctReporterCount: number;
  needsReview: boolean;
  reviewReason: string | null;
  firstReportedAt: string;
  lastReportedAt: string;
  dueAt: string;
  pausedSeconds: number;
  /** dueAt + pausedSeconds, computed server-side. */
  effectiveDeadline: string;
  escalationLevel: number;
  assignedTo: string | null;
  resolvedAt: string | null;
  reopenCount: number;
}

export interface QueueRow {
  id: string;
  publicRef: string;
  categoryCode: string;
  categoryName: string;
  wardId: string;
  wardName: string;
  departmentId: string | null;
  status: IssueStatus;
  priority: Priority;
  priorityScore: number;
  lat: number;
  lng: number;
  reportCount: number;
  distinctReporterCount: number;
  needsReview: boolean;
  reviewReason: string | null;
  firstReportedAt: string;
  dueAt: string;
  pausedSeconds: number;
  effectiveDeadline: string;
  escalationLevel: number;
  assignedTo: string | null;
  landmark: string | null;
}

export interface MyReport {
  reportId: string;
  reportedAt: string;
  description: string | null;
  landmark: string | null;
  photoUrl: string;
  merged: boolean;
  issue: PublicIssue;
}

export interface MyReportsPage {
  items: MyReport[];
  total: number;
  limit: number;
  offset: number;
}

/** The ingest response. Blueprint §5's version is stale; this is the contract. */
export interface ClusterResult {
  reportId: string;
  issueId: string;
  publicRef: string;
  clusterDecision: ClusterDecision;
  reportCount: number;
  distinctReporterCount: number;
  distanceToClusterM: number | null;
  effectiveRadiusM: number | null;
  projectedExtentM: number | null;
  needsReview: boolean;
  status: IssueStatus;
  dueAt: string;
  priorityBand: Priority | null;
  wardName: string | null;
  departmentName: string | null;
  message: string;
}

/** RFC 7807. `detail` is rendered verbatim; the client never paraphrases it. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  [key: string]: unknown;
}

// ---------------------------------------------------------------------------
// Phase 6: citizen verification and notifications
// ---------------------------------------------------------------------------

export type Verdict = "FIXED" | "NOT_FIXED";

/**
 * `GET /me/verifications/{id}`: everything the verify screen needs.
 *
 * `eligible` false is an answer, not an error -- the server says why in
 * `ineligibleReason`, and the screen shows that sentence verbatim. `myVerdict`
 * is the caller's vote in the CURRENT round only (DD-059), so it survives the
 * vote settling the issue and resets when a new fix is submitted.
 */
export interface VerificationView {
  issue: PublicIssue;
  beforePhotoUrl: string | null;
  /** When the fix was submitted. Null once the issue is no longer pending. */
  submittedAt: string | null;
  /** After this, silence counts as agreement. Null once the issue is no longer pending. */
  silenceDeadline: string | null;
  round: number;
  eligible: boolean;
  ineligibleReason: string | null;
  myVerdict: Verdict | null;
  myReason: string | null;
  confirmations: number;
  rejections: number;
  required: number;
}

export type SettleOutcome =
  | "STILL_OPEN"
  | "RESOLVED"
  | "RESOLVED_WITHOUT_VERIFICATION"
  | "REOPENED"
  | "ALREADY_SETTLED";

/** `POST /issues/{id}/verify`. `status` is the issue's status after the vote. */
export interface VoteResult {
  verdict: Verdict;
  status: IssueStatus;
  outcome: SettleOutcome;
  confirmations: number;
  rejections: number;
  required: number;
}

/** `GET /me/verifications`: one fix waiting on the caller. */
export interface AwaitingVerdict {
  issue: PublicIssue;
  silenceDeadline: string | null;
}

export type NotificationType = "VERIFY_REQUESTED" | "RESOLVED" | "REOPENED" | "REJECTED";

export interface AppNotification {
  id: number;
  issueId: string | null;
  type: NotificationType;
  title: string;
  body: string | null;
  createdAt: string;
  readAt: string | null;
}

export interface NotificationPage {
  items: AppNotification[];
  total: number;
  unread: number;
  limit: number;
  offset: number;
}

/**
 * `GET /dashboard/departments`. The rates are null, not zero, when their
 * denominator is zero: a department that has resolved nothing has no rate.
 */
export interface DepartmentAccountability {
  departmentId: string;
  departmentName: string;
  resolved: number;
  resolvedWithoutVerification: number;
  unverifiedRate: number | null;
  fixesClaimed: number;
  reopened: number;
  reopenRate: number | null;
}

// ---------------------------------------------------------------------------
// Phase 7: the full dashboard, and supervisor moderation
// ---------------------------------------------------------------------------

/** Hours, first report to resolution. `medianHours` is null below the minimum sample. */
export interface Median {
  name: string;
  medianHours: number | null;
  resolved: number;
}

export interface ComplianceDay {
  day: string;
  resolved: number;
  onTime: number;
}

export interface AgeBucket {
  label: string;
  fromDays: number;
  toDays: number | null;
  open: number;
}

export interface DayCount {
  day: string;
  reported: number;
  resolved: number;
}

export interface TopCluster {
  id: string;
  publicRef: string;
  categoryName: string;
  wardName: string;
  status: IssueStatus;
  distinctReporterCount: number;
  reportCount: number;
}

/**
 * `GET /dashboard/metrics`. Each section is null when ITS query failed -- never
 * as a stand-in for zero. A figure below `minimumSample` arrives as a null rate
 * or median beside its count, and the tile says how many more it needs.
 */
export interface DashboardMetrics {
  resolutionTime: { overall: Median; byDepartment: Median[]; byWard: Median[] } | null;
  slaCompliance: { resolved: number; onTime: number; rate: number | null; trend: ComplianceDay[] } | null;
  backlogAge: AgeBucket[] | null;
  reportedVsResolved: DayCount[] | null;
  topClusters: TopCluster[] | null;
  minimumSample: number;
  generatedAt: string;
}

export interface BreachingIssue {
  id: string;
  publicRef: string;
  categoryName: string;
  wardName: string;
  departmentName: string | null;
  status: IssueStatus;
  effectiveDeadline: string;
  overdueSeconds: number;
  escalationLevel: number;
  distinctReporterCount: number;
}

/** Why the clustering engine asked a person to look. The values IssueRepository writes. */
export type ReviewReason = "LOW_CONF_MERGE" | "LOW_CONF_SPLIT" | "EXTENT_CAP";

export interface ReviewItem {
  id: string;
  publicRef: string;
  categoryCode: string;
  categoryName: string;
  wardId: string;
  wardName: string;
  status: IssueStatus;
  priority: Priority;
  reviewReason: ReviewReason | string | null;
  lat: number;
  lng: number;
  reportCount: number;
  distinctReporterCount: number;
  extentM: number;
  extentCapM: number;
  mergeRadiusM: number;
  firstReportedAt: string;
  effectiveDeadline: string;
  points: { reportId: string; lat: number; lng: number; accuracyM: number; clusterDecision: ClusterDecision }[];
}

export interface ReviewPage {
  items: ReviewItem[];
  total: number;
  limit: number;
  offset: number;
}

export interface ClusterGeometry {
  lat: number;
  lng: number;
  extentM: number;
  reportCount: number;
  distinctReporters: number;
  positionalUncertaintyM: number;
  firstReportedAt: string;
}

export interface SplitPreview {
  remaining: ClusterGeometry;
  created: ClusterGeometry;
}

export interface ModerationResult {
  issueId: string;
  publicRef: string;
  relatedIssueId: string | null;
  relatedPublicRef: string | null;
}

export interface ModerationEntry {
  id: number;
  action: "CONFIRM" | "SPLIT" | "MERGE" | "RECATEGORISE";
  issueId: string;
  relatedIssueId: string | null;
  actorRole: string;
  note: string | null;
  createdAt: string;
  detail: Record<string, unknown>;
}

/** `GET /supervisor/queue`: a queue row plus identities, for supervisors only (blueprint 3.16). */
export interface BoardRow {
  row: QueueRow;
  reporters: { id: string; fullName: string }[];
  anonymousReporters: number;
  assigneeName: string | null;
}

export interface Member {
  id: string;
  fullName: string;
  role: "STAFF" | "SUPERVISOR";
  wardName: string | null;
  openAssigned: number;
}
