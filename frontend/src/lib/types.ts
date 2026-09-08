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
