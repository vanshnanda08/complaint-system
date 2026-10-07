import { useEffect, useState } from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiBase, query, request } from "./api";
import type {
  BboxResult,
  BreachingIssue,
  DashboardMetrics,
  Category,
  DashboardSummary,
  DepartmentAccountability,
  PublicHistoryEntry,
  PublicIssue,
  PublicIssuePage,
  PublicReport,
  Ward,
} from "./types";

/**
 * Server state, per blueprint §6.
 *
 * The stale times are the blueprint's and each one is a claim about how fast
 * the underlying fact changes:
 *
 *   lists            30s      a queue that is half a minute stale is fine
 *   issue detail      0       the page somebody opened to check a status
 *   categories/wards  ∞       reference data; it changes by migration
 *
 * `keepPreviousData` on the paged and viewport queries stops the list blanking
 * to a loading state on every filter keystroke or map pan, which would make the
 * interface feel broken while behaving correctly.
 */

const LIST_STALE = 30_000;
const REFERENCE_STALE = Infinity;

export const keys = {
  issues: (params: unknown) => ["issues", params] as const,
  issue: (id: string) => ["issue", id] as const,
  issueReports: (id: string) => ["issue", id, "reports"] as const,
  issueHistory: (id: string) => ["issue", id, "history"] as const,
  bbox: (params: unknown) => ["bbox", params] as const,
  categories: () => ["categories"] as const,
  wards: () => ["wards"] as const,
  summary: () => ["dashboard", "summary"] as const,
  departments: () => ["dashboard", "departments"] as const,
  metrics: () => ["dashboard", "metrics"] as const,
  breaching: () => ["dashboard", "breaching"] as const,
  // Everything under ["me"] belongs to the signed-in user. Each key carries
  // the user id, so signing in as somebody else on the same tab never serves
  // the previous person's list from cache.
  myVerifications: (userId: string | undefined) => ["me", "verifications", userId] as const,
  myVerification: (userId: string | undefined, issueId: string) =>
    ["me", "verifications", userId, issueId] as const,
  myNotifications: (userId: string | undefined) => ["me", "notifications", userId] as const,
  myUnread: (userId: string | undefined) => ["me", "notifications", userId, "unread"] as const,
};

export interface IssueFilters {
  status?: string;
  category?: string;
  ward?: string;
  sort?: string;
  limit?: number;
  offset?: number;
}

export function useIssues(filters: IssueFilters) {
  return useQuery({
    queryKey: keys.issues(filters),
    queryFn: () => request<PublicIssuePage>(`/public/issues${query({ ...filters })}`),
    staleTime: LIST_STALE,
    placeholderData: keepPreviousData,
  });
}

export function useIssue(id: string) {
  return useQuery({
    queryKey: keys.issue(id),
    queryFn: () => request<PublicIssue>(`/public/issues/${id}`),
    staleTime: 0,
  });
}

export function useIssueReports(id: string) {
  return useQuery({
    queryKey: keys.issueReports(id),
    queryFn: () => request<PublicReport[]>(`/public/issues/${id}/reports`),
    staleTime: 0,
  });
}

export function useIssueHistory(id: string) {
  return useQuery({
    queryKey: keys.issueHistory(id),
    queryFn: () => request<PublicHistoryEntry[]>(`/public/issues/${id}/history`),
    staleTime: 0,
  });
}

export interface BboxParams {
  south: number;
  west: number;
  north: number;
  east: number;
  status?: string;
  category?: string;
}

export function useBbox(params: BboxParams | null) {
  return useQuery({
    queryKey: keys.bbox(params),
    queryFn: () => request<BboxResult>(`/public/issues/bbox${query({ ...params! })}`),
    // Null until the map reports its first viewport. Fetching a guessed
    // bounding box would spend a 500-row query on a rectangle nobody is
    // looking at.
    enabled: params !== null,
    staleTime: LIST_STALE,
    placeholderData: keepPreviousData,
  });
}

export function useCategories() {
  return useQuery({
    queryKey: keys.categories(),
    queryFn: () => request<Category[]>("/categories"),
    staleTime: REFERENCE_STALE,
  });
}

export function useWards() {
  return useQuery({
    queryKey: keys.wards(),
    queryFn: () => request<Ward[]>("/wards"),
    staleTime: REFERENCE_STALE,
  });
}

export function useDashboardSummary() {
  return useQuery({
    queryKey: keys.summary(),
    queryFn: () => request<DashboardSummary>("/dashboard/summary"),
    staleTime: LIST_STALE,
    refetchInterval: DASHBOARD_REFETCH,
  });
}

export function useDepartmentAccountability() {
  return useQuery({
    queryKey: keys.departments(),
    queryFn: () => request<DepartmentAccountability[]>("/dashboard/departments"),
    staleTime: LIST_STALE,
    refetchInterval: DASHBOARD_REFETCH,
  });
}

/**
 * Blueprint 3.8: "Everything else is fetched on load and refetched every 60
 * seconds." Only the overdue tile and the breaching list are live.
 */
const DASHBOARD_REFETCH = 60_000;

export function useDashboardMetrics() {
  return useQuery({
    queryKey: keys.metrics(),
    queryFn: () => request<DashboardMetrics>("/dashboard/metrics"),
    staleTime: LIST_STALE,
    refetchInterval: DASHBOARD_REFETCH,
  });
}

export function useBreaching() {
  return useQuery({
    queryKey: keys.breaching(),
    queryFn: () => request<BreachingIssue[]>("/dashboard/breaching"),
    staleTime: LIST_STALE,
    refetchInterval: DASHBOARD_REFETCH,
  });
}

export type StreamState = "connecting" | "live" | "reconnecting" | "unsupported";

/**
 * Subscribes to the dashboard's event stream and refetches the two live
 * figures whenever the server says they may have changed.
 *
 * The events carry no data, deliberately: the server sends "changed", the
 * client refetches through the same endpoints it always uses, and so there is
 * exactly one definition of "overdue" -- the server's. The server already
 * coalesces bursts to at most one event a second, so this does not throttle.
 *
 * EventSource reconnects on its own after a drop; the state exists so the page
 * can say "live" only while it is, rather than claiming it permanently.
 */
export function useDashboardStream(): StreamState {
  const qc = useQueryClient();
  // "connecting" on the server and the client alike. Choosing the first state
  // from `typeof EventSource` made the two renders disagree -- the server has
  // no EventSource -- and React discarded the server's HTML for the whole
  // page. The browser walk caught it as a hydration error.
  const [state, setState] = useState<StreamState>("connecting");

  useEffect(() => {
    if (typeof EventSource === "undefined") return;
    const source = new EventSource(`${apiBase()}/stream/dashboard`);
    source.addEventListener("ready", () => setState("live"));
    source.addEventListener("changed", () => {
      void qc.invalidateQueries({ queryKey: keys.summary() });
      void qc.invalidateQueries({ queryKey: keys.breaching() });
    });
    source.onerror = () => setState("reconnecting");
    return () => source.close();
  }, [qc]);

  return state;
}
