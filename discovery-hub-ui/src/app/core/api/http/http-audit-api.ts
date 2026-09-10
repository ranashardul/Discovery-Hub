import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { AuditEntry, AuditPage, AuditQuery } from '../../models/audit';
import { AuditApi } from '../audit-api';
import { toApiError, toParams } from './http-support';

interface WireAuditEvent {
  id: string;
  eventId: string | null;
  caseId: string | null;
  action: string;
  targetType: string | null;
  targetId: string | null;
  actor: string | null;
  status: string | null;
  timestamp: string;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  details: Record<string, unknown> | null;
  receivedAt: string | null;
}

/** Wire shape of GET /api/audit/search. */
interface WireAuditPage {
  entries: WireAuditEvent[] | null;
  total: number;
  page: number;
  size: number;
}

@Injectable()
export class HttpAuditApi extends AuditApi {
  private readonly http = inject(HttpClient);

  private readonly base = `${environment.api.exportAudit}/audit`;

  /**
   * Filtering, ordering and paging all happen in the service now. Previously
   * an entire case history crossed the wire on every query and was narrowed in
   * the browser, which does not hold for a store designed to grow without
   * bound.
   */
  query(query: AuditQuery): Observable<AuditPage> {
    const page = query.page ?? 0;
    const size = query.size ?? 25;

    return this.http
      .get<WireAuditPage>(`${this.base}/search`, {
        params: toParams({
          caseId: query.caseId,
          actor: query.actor,
          action: query.action,
          targetType: query.targetType,
          // The free-text box is a target lookup; the service has no
          // full-text index over the trail.
          targetId: query.q,
          from: query.after,
          to: query.before,
          page,
          size,
        }),
      })
      .pipe(
        map((response) => ({
          entries: (response.entries ?? []).map((event, index) =>
            // Position within the page, offset by the page itself, so the
            // numbering a reviewer sees is continuous while paging.
            this.toEntry(event, page * size + index),
          ),
          total: response.total,
          page: response.page,
          size: response.size,
        })),
        catchError(toApiError),
      );
  }

  getEntry(id: string): Observable<AuditEntry> {
    return this.http
      .get<WireAuditEvent>(`${this.base}/${encodeURIComponent(id)}`)
      .pipe(map((event) => this.toEntry(event, 0)), catchError(toApiError));
  }

  listActors(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/actors`).pipe(catchError(toApiError));
  }

  private toEntry(event: WireAuditEvent, index: number): AuditEntry {
    return {
      id: event.eventId ?? event.id,
      // The store has no sequence column; position stands in for ordering.
      sequence: index + 1,
      timestamp: event.timestamp,
      actor: event.actor ?? 'system',
      action: event.action as AuditEntry['action'],
      targetType: (event.targetType as AuditEntry['targetType']) ?? 'SYSTEM',
      targetId: event.targetId ?? '',
      targetLabel: event.targetId ?? '',
      caseId: event.caseId,
      summary: this.summarise(event),
      before: event.before,
      after: event.after,
    };
  }

  private summarise(event: WireAuditEvent): string {
    const detail = event.details?.['summary'];
    if (typeof detail === 'string' && detail.length > 0) {
      return detail;
    }
    const action = event.action.replace(/_/g, ' ').toLowerCase();
    return event.targetId ? `${action} — ${event.targetId}` : action;
  }
}
