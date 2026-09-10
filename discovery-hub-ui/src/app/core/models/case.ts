export type MatterType = 'INVESTIGATION' | 'LITIGATION' | 'REGULATORY_INQUIRY';

/**
 * Case lifecycle, matching `CaseStatus` in case-hold-service. These three
 * values are the whole vocabulary the service knows; sending anything else to
 * `PATCH /api/v1/cases/{id}` is rejected by its `parseStatus`.
 */
export type CaseStatus = 'OPEN' | 'CLOSED' | 'ARCHIVED';

export const CASE_STATUSES: readonly CaseStatus[] = ['OPEN', 'CLOSED', 'ARCHIVED'];

/**
 * Transitions the UI offers.
 *
 * This is a **client-side affordance only**. The case service accepts any
 * status change and enforces no state machine, so this narrows the buttons a
 * reviewer sees rather than describing a guarantee. Archiving is terminal here
 * because a hold cannot be placed on an archived case (`HoldService` refuses
 * it), so there is no useful way back.
 */
export const ALLOWED_CASE_TRANSITIONS: Readonly<Record<CaseStatus, readonly CaseStatus[]>> = {
  OPEN: ['CLOSED', 'ARCHIVED'],
  CLOSED: ['OPEN', 'ARCHIVED'],
  ARCHIVED: [],
};

/** Narrows a status off the wire, failing loudly rather than casting blindly. */
export function toCaseStatus(value: string): CaseStatus {
  if ((CASE_STATUSES as readonly string[]).includes(value)) {
    return value as CaseStatus;
  }
  throw new Error(`Unknown case status from case-hold-service: ${value}`);
}

export interface LegalCase {
  id: string;
  caseNumber: string;
  name: string;
  description: string;
  matterType: MatterType;
  owner: string;
  status: CaseStatus;
  createdAt: string;
  updatedAt: string;
  closedAt: string | null;
  custodianCount: number;
  evidenceCount: number;
  activeHoldCount: number;
  heldMessageCount: number;
}

export interface CaseCustodian {
  caseId: string;
  custodianId: string;
  displayName: string;
  email: string;
  department: string;
  addedAt: string;
  addedBy: string;
}

/** A message pinned to a case as evidence (FR-2.4). */
export interface EvidenceItem {
  id: string;
  caseId: string;
  messageId: string;
  subject: string;
  sender: string;
  communicationType: string;
  messageTimestamp: string;
  attachmentCount: number;
  onHold: boolean;
  addedAt: string;
  addedBy: string;
  /** How it got here: one at a time, or as part of a bulk result set. */
  source: 'MANUAL' | 'SEARCH_RESULT_SET';
}

export interface CreateCaseRequest {
  name: string;
  description: string;
  matterType: MatterType;
  owner: string;
}
