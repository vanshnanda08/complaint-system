import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { query, request } from "./api";
import type {
  BboxResult,
  Category,
  DashboardSummary,
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
  });
}
