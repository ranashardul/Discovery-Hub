import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { MockCaseApi } from '../../mock/mock-apis';
import {
  CaseCustodian,
  CaseStatus,
  CreateCaseRequest,
  EvidenceItem,
  LegalCase,
  toCaseStatus,
} from '../../models/case';
import { Custodian } from '../../models/message';
import { AddEvidenceRequest, CaseApi, CaseListQuery } from '../case-api';
import { toApiError, toParams } from './http-support';

interface WireCase {
  caseId: string;
  caseName: string;
  description: string | null;
  status: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  communicationCount: number;
  custodianCount: number;
  activeHoldCount: number;
}

interface WireCaseCustodian {
  custodianId: string;
  displayName: string;
  email: string;
  department: string | null;
  addedAt: string;
  addedBy: string;
}

interface WireCommunicationItem {
  communicationId: string;
  communicationType: string | null;
  addedAt: string;
  message: {
    subject?: string | null;
    sender?: string | null;
    recipients?: string[] | null;
    messageTimestamp?: string | null;
    attachmentCount?: number | null;
    communicationType?: string | null;
  } | null;
}

/** Wire shape of GET /api/search/custodians. */
interface WireCustodian {
  name: string;
  messageCount: number;
}

interface WireCommunications {
  caseId: string;
  total: number;
  unresolvedCount: number;
  added: WireCommunicationItem[];
  newlyAdded: WireCommunicationItem[];
}

/**
 * The case service does not model who is acting, so every mutating call has to
 * name an actor. Until the UI has authentication there is nobody to name.
 */
const ACTOR = 'discovery-hub-ui';

@Injectable()
export class HttpCaseApi extends CaseApi {
  private readonly http = inject(HttpClient);
  /** Removing a single evidence item has no backend; see environment.mockBacked. */
  private readonly fallback = inject(MockCaseApi);

  private readonly base = `${environment.api.caseHold}/cases`;

  listCases(query?: CaseListQuery): Observable<LegalCase[]> {
    return this.http
      .get<WireCase[]>(this.base, { params: toParams({ status: query?.status }) })
      .pipe(
        map((cases) => {
          const term = query?.q?.trim().toLowerCase();
          const mapped = cases.map((item) => this.toCase(item));
          // The API filters by status only, so free-text narrowing happens here.
          return term
            ? mapped.filter(
                (item) =>
                  item.name.toLowerCase().includes(term) ||
                  item.caseNumber.toLowerCase().includes(term),
              )
            : mapped;
        }),
        catchError(toApiError),
      );
  }

  getCase(id: string): Observable<LegalCase> {
    return this.http
      .get<WireCase>(`${this.base}/${encodeURIComponent(id)}`)
      .pipe(map((item) => this.toCase(item)), catchError(toApiError));
  }

  createCase(request: CreateCaseRequest): Observable<LegalCase> {
    // matterType has no column on the case entity; it is folded into the
    // description so the information is not silently dropped.
    const description = request.matterType
      ? `[${request.matterType}] ${request.description ?? ''}`.trim()
      : request.description;

    return this.http
      .post<WireCase>(this.base, {
        caseName: request.name,
        description,
        createdBy: request.owner || ACTOR,
      })
      .pipe(map((item) => this.toCase(item)), catchError(toApiError));
  }

  updateCase(id: string, patch: Partial<CreateCaseRequest>): Observable<LegalCase> {
    return this.http
      .patch<WireCase>(`${this.base}/${encodeURIComponent(id)}`, {
        caseName: patch.name,
        description: patch.description,
        updatedBy: patch.owner || ACTOR,
      })
      .pipe(map((item) => this.toCase(item)), catchError(toApiError));
  }

  changeStatus(id: string, status: CaseStatus): Observable<LegalCase> {
    return this.http
      .patch<WireCase>(`${this.base}/${encodeURIComponent(id)}`, {
        status,
        updatedBy: ACTOR,
      })
      .pipe(map((item) => this.toCase(item)), catchError(toApiError));
  }

  listEvidence(caseId: string): Observable<EvidenceItem[]> {
    return this.http
      .get<WireCommunications>(`${this.base}/${encodeURIComponent(caseId)}/communications`)
      .pipe(
        map((response) => (response.added ?? []).map((item) => this.toEvidence(caseId, item))),
        catchError(toApiError),
      );
  }

  addEvidence(caseId: string, request: AddEvidenceRequest): Observable<EvidenceItem[]> {
    return this.http
      .post<WireCommunications>(`${this.base}/${encodeURIComponent(caseId)}/communications`, {
        communications: request.messageIds.map((messageId: string) => ({
          communicationId: messageId,
        })),
        addedBy: ACTOR,
      })
      .pipe(
        map((response) => (response.newlyAdded ?? []).map((item) => this.toEvidence(caseId, item))),
        catchError(toApiError),
      );
  }

  /** No endpoint removes a communication from a case. */
  removeEvidence(caseId: string, evidenceId: string): Observable<void> {
    return this.fallback.removeEvidence(caseId, evidenceId);
  }

  listCustodians(caseId: string): Observable<CaseCustodian[]> {
    return this.http
      .get<WireCaseCustodian[]>(`${this.base}/${encodeURIComponent(caseId)}/custodians`)
      .pipe(
        map((custodians) =>
          custodians.map((custodian) => ({
            caseId,
            custodianId: custodian.custodianId,
            displayName: custodian.displayName,
            email: custodian.email,
            department: custodian.department ?? '',
            addedAt: custodian.addedAt,
            addedBy: custodian.addedBy,
          })),
        ),
        catchError(toApiError),
      );
  }

  addCustodian(caseId: string, custodianId: string): Observable<CaseCustodian> {
    return this.http
      .post<WireCaseCustodian>(`${this.base}/${encodeURIComponent(caseId)}/custodians`, {
        custodianId,
        addedBy: ACTOR,
      })
      .pipe(
        map((custodian) => ({
          caseId,
          custodianId: custodian.custodianId,
          displayName: custodian.displayName,
          email: custodian.email,
          department: custodian.department ?? '',
          addedAt: custodian.addedAt,
          addedBy: custodian.addedBy,
        })),
        catchError(toApiError),
      );
  }

  removeCustodian(caseId: string, custodianId: string): Observable<void> {
    return this.http
      .delete<void>(
        `${this.base}/${encodeURIComponent(caseId)}/custodians/${encodeURIComponent(custodianId)}`,
      )
      .pipe(catchError(toApiError));
  }

  /**
   * The custodian directory is derived from the archive, because no service
   * models custodians as entities: the only truthful answer to "who is in
   * here" is the set of identities that actually sent something.
   *
   * Only the name and message count are real. Display name, email, department
   * and title have no source, so the identity stands in for the name and the
   * rest are left empty rather than invented.
   */
  listAllCustodians(): Observable<Custodian[]> {
    return this.http
      .get<WireCustodian[]>(`${environment.api.search}/custodians`)
      .pipe(
        map((custodians) =>
          custodians.map((custodian) => ({
            id: custodian.name,
            displayName: custodian.name,
            email: custodian.name,
            department: '',
            title: '',
            messageCount: custodian.messageCount,
          })),
        ),
        catchError(toApiError),
      );
  }

  /**
   * The case entity has no case number, matter type or explicit owner, so
   * those are derived rather than stored: the number from the id, the matter
   * type from the description prefix written on create.
   */
  private toCase(item: WireCase): LegalCase {
    const description = item.description ?? '';
    const matterMatch = description.match(/^\[(INVESTIGATION|LITIGATION|REGULATORY_INQUIRY)]\s*/);

    return {
      id: item.caseId,
      caseNumber: `CASE-${item.caseId.slice(0, 8).toUpperCase()}`,
      name: item.caseName,
      description: matterMatch ? description.slice(matterMatch[0].length) : description,
      matterType: (matterMatch?.[1] as LegalCase['matterType']) ?? 'INVESTIGATION',
      owner: item.createdBy,
      status: toCaseStatus(item.status),
      createdAt: item.createdAt,
      updatedAt: item.updatedAt,
      closedAt: item.status === 'CLOSED' ? item.updatedAt : null,
      custodianCount: item.custodianCount,
      evidenceCount: item.communicationCount,
      activeHoldCount: item.activeHoldCount,
      heldMessageCount: 0,
    };
  }

  private toEvidence(caseId: string, item: WireCommunicationItem): EvidenceItem {
    const message = item.message ?? {};
    return {
      id: `${caseId}:${item.communicationId}`,
      caseId,
      messageId: item.communicationId,
      // An unresolved reference means the message was disposed, or never
      // existed. The list still shows it, because the case recorded it.
      subject: message.subject ?? '(message unavailable)',
      sender: message.sender ?? '',
      recipients: message.recipients ?? [],
      communicationType: message.communicationType ?? item.communicationType ?? 'EMAIL',
      messageTimestamp: message.messageTimestamp ?? item.addedAt,
      attachmentCount: message.attachmentCount ?? 0,
      onHold: false,
      addedAt: item.addedAt,
      addedBy: ACTOR,
      source: 'MANUAL',
    };
  }
}
