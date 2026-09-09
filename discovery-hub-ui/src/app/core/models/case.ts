export type MatterType = 'INVESTIGATION' | 'LITIGATION' | 'REGULATORY_INQUIRY';

/** Case lifecycle from FR-2.2. */
export type CaseStatus = 'DRAFT' | 'ACTIVE' | 'UNDER_REVIEW' | 'CLOSED';

/** The only transitions the case service accepts. Anything else is a 409. */
export const ALLOWED_CASE_TRANSITIONS: Readonly<Record<CaseStatus, readonly CaseStatus[]>> = {
  DRAFT: ['ACTIVE'],
  ACTIVE: ['UNDER_REVIEW', 'CLOSED'],
  UNDER_REVIEW: ['ACTIVE', 'CLOSED'],
  CLOSED: [],
};

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
