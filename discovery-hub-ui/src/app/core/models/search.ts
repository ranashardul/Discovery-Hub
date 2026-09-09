import { CommunicationType, DispositionStatus } from './message';

export type SearchSort = 'relevance' | 'newest' | 'oldest';

/** Query-string shape accepted by `GET /api/search` on the search service. */
export interface SearchCriteria {
  q: string;
  communicationType?: CommunicationType | null;
  sender?: string | null;
  recipient?: string | null;
  threadId?: string | null;
  dispositionStatus?: DispositionStatus | null;
  onHold?: boolean | null;
  hasAttachments?: boolean | null;
  /** ISO-8601 instant, inclusive lower bound. */
  after?: string | null;
  /** ISO-8601 instant, inclusive upper bound. */
  before?: string | null;
  sort?: SearchSort;
  from?: number;
  size?: number;
}

/**
 * One hit. `highlights` carries the matched fragments with the matching terms
 * wrapped in `<mark>` so the UI can render FR-3.3 highlighting without
 * re-implementing the analyzer client side.
 */
export interface SearchResultItem {
  messageId: string;
  score: number;
  communicationType: CommunicationType;
  sender: string;
  recipients: string[];
  subject: string;
  snippet: string;
  highlights: { subject?: string; body?: string };
  threadId: string;
  messageTimestamp: string;
  attachmentCount: number;
  onHold: boolean;
  dispositionStatus: DispositionStatus;
}

export interface SearchResponse {
  query: string;
  total: number;
  from: number;
  size: number;
  sort: SearchSort;
  tookMillis: number;
  results: SearchResultItem[];
}

export interface SearchStats {
  indexedCount: number;
  index: string;
  /** Indexing failures still being retried. */
  pendingFailures: number;
  /**
   * Failures that exceeded the retry cap and are no longer retried. Non-zero
   * means messages exist in MongoDB that will never appear in search.
   */
  abandonedFailures?: number;
}

/** A query an investigator parked on a case so it can be re-run later (FR-3.5). */
export interface SavedSearch {
  id: string;
  caseId: string;
  name: string;
  criteria: SearchCriteria;
  createdAt: string;
  lastRunAt: string | null;
  lastRunTotal: number | null;
}
