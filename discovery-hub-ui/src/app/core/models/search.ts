import { CommunicationType, DispositionStatus } from './message';

export type SearchSort = 'relevance' | 'newest' | 'oldest';

/** Query-string shape accepted by `GET /api/search` on the search service. */
export interface SearchCriteria {
  q: string;
  communicationType?: CommunicationType | null;
  sender?: string | null;
  recipient?: string | null;
  /**
   * Custodians to match on either side of a conversation (FR-3.2). Sent as a
   * repeated `participant` parameter; several are OR'd, which is what
   * "messages involving any of these people" means and what `sender` AND
   * `recipient` cannot express.
   */
  participants?: string[] | null;
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
 * Whether a criteria set narrows the archive at all.
 *
 * Mirrors `SearchCriteria.hasAnyFilter` in the search service, which is what
 * decides there whether a request without `q` is answerable: "everything this
 * custodian sent" has no text to score against, but a request with neither
 * terms nor filters asks to page the whole corpus and is refused. Shared so
 * the page, the in-memory backend and the service cannot drift into
 * disagreeing about which requests are valid.
 */
export function hasAnyFilter(criteria: SearchCriteria): boolean {
  return !!(
    criteria.communicationType ||
    criteria.sender ||
    criteria.recipient ||
    criteria.participants?.length ||
    criteria.threadId ||
    criteria.dispositionStatus ||
    criteria.onHold === true ||
    criteria.onHold === false ||
    criteria.hasAttachments === true ||
    criteria.after ||
    criteria.before
  );
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
