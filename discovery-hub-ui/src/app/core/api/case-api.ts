import { Observable } from 'rxjs';
import {
  CaseCustodian,
  CaseStatus,
  CreateCaseRequest,
  EvidenceItem,
  LegalCase,
} from '../models/case';
import { Custodian } from '../models/message';

export interface CaseListQuery {
  status?: CaseStatus | null;
  q?: string | null;
}

export interface AddEvidenceRequest {
  messageIds: string[];
  source: 'MANUAL' | 'SEARCH_RESULT_SET';
}

/** Contract of the case & hold service, case half (`/api/v1/cases`). */
export abstract class CaseApi {
  abstract listCases(query?: CaseListQuery): Observable<LegalCase[]>;

  abstract getCase(id: string): Observable<LegalCase>;

  abstract createCase(request: CreateCaseRequest): Observable<LegalCase>;

  abstract updateCase(id: string, patch: Partial<CreateCaseRequest>): Observable<LegalCase>;

  /**
   * `PATCH /api/v1/cases/{id}/status`. Rejects transitions outside
   * ALLOWED_CASE_TRANSITIONS with a readable error.
   */
  abstract changeStatus(id: string, status: CaseStatus): Observable<LegalCase>;

  abstract listCustodians(caseId: string): Observable<CaseCustodian[]>;

  abstract addCustodian(caseId: string, custodianId: string): Observable<CaseCustodian>;

  abstract removeCustodian(caseId: string, custodianId: string): Observable<void>;

  abstract listEvidence(caseId: string): Observable<EvidenceItem[]>;

  /** Returns only the items actually added; already-present messages are skipped. */
  abstract addEvidence(caseId: string, request: AddEvidenceRequest): Observable<EvidenceItem[]>;

  abstract removeEvidence(caseId: string, evidenceId: string): Observable<void>;

  /** Directory of custodians available to attach to a case. */
  abstract listAllCustodians(): Observable<Custodian[]>;
}
