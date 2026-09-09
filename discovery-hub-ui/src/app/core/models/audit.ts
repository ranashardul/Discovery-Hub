export type AuditAction =
  | 'CASE_CREATED'
  | 'CASE_UPDATED'
  | 'CASE_STATUS_CHANGED'
  | 'CUSTODIAN_ADDED'
  | 'CUSTODIAN_REMOVED'
  | 'EVIDENCE_ADDED'
  | 'EVIDENCE_REMOVED'
  | 'HOLD_PLACED'
  | 'HOLD_RELEASED'
  | 'SEARCH_EXECUTED'
  | 'SEARCH_SAVED'
  | 'EXPORT_REQUESTED'
  | 'EXPORT_COMPLETED'
  | 'EXPORT_DOWNLOADED'
  | 'EXPORT_VERIFIED'
  | 'DELETION_BLOCKED'
  | 'DISPOSITION_RUN'
  | 'RETENTION_POLICY_UPDATED';

export type AuditTargetType =
  | 'CASE'
  | 'HOLD'
  | 'MESSAGE'
  | 'EXPORT'
  | 'SEARCH'
  | 'CUSTODIAN'
  | 'RETENTION_POLICY'
  | 'SYSTEM';

/**
 * One append-only entry. Nothing in the API can update or delete these
 * (FR-7.3), which is why there is no update model anywhere in this file.
 */
export interface AuditEntry {
  id: string;
  /** Monotonic position in the log; makes gaps visible. */
  sequence: number;
  timestamp: string;
  actor: string;
  action: AuditAction;
  targetType: AuditTargetType;
  targetId: string;
  targetLabel: string;
  caseId: string | null;
  summary: string;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
}

export interface AuditQuery {
  caseId?: string | null;
  actor?: string | null;
  action?: AuditAction | null;
  targetType?: AuditTargetType | null;
  after?: string | null;
  before?: string | null;
  q?: string | null;
  page?: number;
  size?: number;
}

export interface AuditPage {
  total: number;
  page: number;
  size: number;
  entries: AuditEntry[];
}
