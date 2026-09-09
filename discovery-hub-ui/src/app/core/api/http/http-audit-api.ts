import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { MockAuditApi } from '../../mock/mock-apis';
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

@Injectable()
export class HttpAuditApi extends AuditApi {
  private readonly http = inject(HttpClient);
  /** Entry lookup and actor list have no endpoint; see environment.mockBacked. */
  private readonly fallback = inject(MockAuditApi);

  private readonly base = `${environment.api.exportAudit}/audit`;

  /**
   * The service exposes two reads: everything for a case, or everything for a
   * target id. There is no paging, no actor or action filter, and no date
   * range, so the remaining narrowing happens here over the returned set.
   *
   * That is honest but not scalable — the whole case history crosses the wire
   * on every query. It is the right shape only while volumes are small.
   */
  query(query: AuditQuery): Observable<AuditPage> {
    // The bare collection endpoint requires a targetId, so without a case the
    // only thing that can be asked for is a specific target.
    const request = query.caseId
      ? this.http.get<WireAuditEvent[]>(`${this.base}/cases/${encodeURIComponent(query.caseId)}`)
      : this.http.get<WireAuditEvent[]>(this.base, {
          params: toParams({ targetId: query.q }),
        });

    return request.pipe(
      map((events) => {
        let entries = events.map((event, index) => this.toEntry(event, index));

        if (query.actor) {
          entries = entries.filter((entry) => entry.actor === query.actor);
        }
        if (query.action) {
          entries = entries.filter((entry) => entry.action === query.action);
        }
        if (query.targetType) {
          entries = entries.filter((entry) => entry.targetType === query.targetType);
        }
        if (query.after) {
          entries = entries.filter((entry) => entry.timestamp >= query.after!);
        }
        if (query.before) {
          entries = entries.filter((entry) => entry.timestamp <= query.before!);
        }

        entries.sort((left, right) => right.timestamp.localeCompare(left.timestamp));

        const page = query.page ?? 0;
        const size = query.size ?? 25;
        return {
          entries: entries.slice(page * size, page * size + size),
          total: entries.length,
          page,
          size,
        } as AuditPage;
      }),
      catchError(toApiError),
    );
  }

  getEntry(id: string): Observable<AuditEntry> {
    return this.fallback.getEntry(id);
  }

  listActors(): Observable<string[]> {
    return this.fallback.listActors();
  }

  private toEntry(event: WireAuditEvent, index: number): AuditEntry {
    return {
      id: event.id,
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
