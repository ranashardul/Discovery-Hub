/**
 * Holds propagate asynchronously (FR-4.3), so a hold is PROPAGATING until the
 * worker has marked every message in scope.
 */
export type HoldStatus = 'PROPAGATING' | 'ACTIVE' | 'RELEASING' | 'RELEASED';

export interface HoldScope {
  custodianIds: string[];
  after: string | null;
  before: string | null;
  /** Optional narrowing terms; empty means the whole custodian/date scope. */
  searchTerms: string | null;
}

export interface LegalHold {
  id: string;
  caseId: string;
  caseName: string;
  reason: string;
  status: HoldStatus;
  scope: HoldScope;
  placedAt: string;
  placedBy: string;
  releasedAt: string | null;
  releasedBy: string | null;
  /** Messages the worker has stamped so far. */
  matchedMessageCount: number;
  /** Total the scope resolved to, used to render propagation progress. */
  estimatedScopeCount: number;
}

export interface PlaceHoldRequest {
  caseId: string;
  reason: string;
  scope: HoldScope;
}

/** Outcome of asking the platform to delete a message (FR-4.6). */
export interface DeletionAttemptResult {
  messageId: string;
  deleted: boolean;
  reason: string;
  blockingHoldIds: string[];
  attemptedAt: string;
}
