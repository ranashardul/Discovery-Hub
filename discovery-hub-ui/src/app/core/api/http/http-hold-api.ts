import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of, switchMap } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  DeletionAttemptResult,
  HoldScope,
  LegalHold,
  PlaceHoldRequest,
  toHoldStatus,
} from '../../models/hold';
import { CaseApi } from '../case-api';
import { HoldApi } from '../hold-api';
import { toApiError } from './http-support';

interface WireHold {
  holdId: string;
  caseId: string;
  name: string;
  description: string | null;
  reason: string | null;
  status: string;
  scope: string;
  criteria: {
    participants?: string[] | null;
    communicationTypes?: string[] | null;
    dateFrom?: string | null;
    dateTo?: string | null;
  } | null;
  createdBy: string;
  createdAt: string;
  releasedBy: string | null;
  releasedAt: string | null;
  communicationCount: number;
}

/** Wire shape of POST /api/v1/holds/preview. */
interface WireScopePreview {
  matchingCount: number;
}

const ACTOR = 'discovery-hub-ui';

@Injectable()
export class HttpHoldApi extends HoldApi {
  private readonly http = inject(HttpClient);
  private readonly cases = inject(CaseApi);

  private readonly base = environment.api.caseHold;

  /**
   * Holds are addressable per case only. Listing them all means asking every
   * case, which is acceptable at this scale and honest about the API shape.
   */
  listHolds(caseId?: string | null): Observable<LegalHold[]> {
    if (caseId) {
      return this.listForCase(caseId);
    }

    return this.cases.listCases().pipe(
      switchMap((cases) => {
        if (cases.length === 0) {
          return of([] as LegalHold[]);
        }
        return forkJoin(cases.map((item) => this.listForCase(item.id, item.name))).pipe(
          map((perCase) => perCase.flat()),
        );
      }),
    );
  }

  getHold(id: string): Observable<LegalHold> {
    return this.http
      .get<WireHold>(`${this.base}/holds/${encodeURIComponent(id)}`)
      .pipe(map((hold) => this.toHold(hold)), catchError(toApiError));
  }

  placeHold(request: PlaceHoldRequest): Observable<LegalHold> {
    return this.http
      .post<WireHold>(`${this.base}/cases/${encodeURIComponent(request.caseId)}/holds`, {
        name: request.reason?.slice(0, 255) || 'Legal hold',
        description: request.reason,
        reason: request.reason,
        createdBy: ACTOR,
        criteria: {
          participants: request.scope.custodianIds ?? [],
          communicationTypes: [],
          dateFrom: request.scope.after,
          dateTo: request.scope.before,
        },
      })
      .pipe(map((hold) => this.toHold(hold)), catchError(toApiError));
  }

  releaseHold(id: string, releasedBy: string): Observable<LegalHold> {
    return this.http
      .patch<WireHold>(`${this.base}/holds/${encodeURIComponent(id)}/release`, {
        releasedBy: releasedBy || ACTOR,
      })
      .pipe(map((hold) => this.toHold(hold)), catchError(toApiError));
  }

  /**
   * Counts what the scope would cover before the hold is placed.
   *
   * The case service delegates this to the search index, which is also what
   * confirms the hold afterwards, so the number shown here is the number the
   * hold will report. A 503 when search is unreachable is deliberate on the
   * service side: showing zero would read as "this rule matches nothing".
   */
  previewScope(scope: HoldScope): Observable<number> {
    return this.http
      .post<WireScopePreview>(`${this.base}/holds/preview`, {
        participants: scope.custodianIds ?? [],
        communicationTypes: [],
        dateFrom: scope.after,
        dateTo: scope.before,
      })
      .pipe(map((response) => response.matchingCount), catchError(toApiError));
  }

  /**
   * Deletion is enforced by ingestion, which refuses while a hold covers the
   * message. A 409 is the interesting outcome, not an error, so it is
   * translated into a result rather than propagated.
   */
  attemptDelete(messageId: string): Observable<DeletionAttemptResult> {
    return this.http
      .delete<Record<string, unknown>>(
        `${environment.api.ingestion}/messages/${encodeURIComponent(messageId)}`,
      )
      .pipe(
        map(() => ({
          messageId,
          deleted: true,
          reason: 'Message disposed and removed from the index',
          blockingHoldIds: [],
          attemptedAt: new Date().toISOString(),
        })),
        catchError((error) => {
          const status = (error as { status?: number })?.status;
          if (status === 409) {
            return of({
              messageId,
              deleted: false,
              reason: 'Blocked: the message is under legal hold',
              blockingHoldIds: [],
              attemptedAt: new Date().toISOString(),
            });
          }
          return toApiError(error);
        }),
      );
  }

  private listForCase(caseId: string, caseName?: string): Observable<LegalHold[]> {
    return this.http
      .get<WireHold[]>(`${this.base}/cases/${encodeURIComponent(caseId)}/holds`)
      .pipe(
        map((holds) => holds.map((hold) => this.toHold(hold, caseName))),
        catchError(toApiError),
      );
  }

  private toHold(hold: WireHold, caseName?: string): LegalHold {
    const criteria = hold.criteria ?? {};
    return {
      id: hold.holdId,
      caseId: hold.caseId,
      caseName: caseName ?? '',
      reason: hold.reason ?? hold.description ?? hold.name,
      status: toHoldStatus(hold.status),
      scope: {
        custodianIds: criteria.participants ?? [],
        after: criteria.dateFrom ?? null,
        before: criteria.dateTo ?? null,
        // The criteria model has no free-text term.
        searchTerms: null,
      },
      placedAt: hold.createdAt,
      placedBy: hold.createdBy,
      releasedAt: hold.releasedAt,
      releasedBy: hold.releasedBy,
      matchedMessageCount: hold.communicationCount,
    };
  }
}
