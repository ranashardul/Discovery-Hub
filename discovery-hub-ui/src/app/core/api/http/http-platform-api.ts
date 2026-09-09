import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { MockPlatformApi } from '../../mock/mock-apis';
import { CommunicationType } from '../../models/message';
import {
  DashboardCounts,
  DispositionRun,
  RetentionPolicy,
  ServiceStatus,
} from '../../models/retention';
import { SearchStats } from '../../models/search';
import { CaseApi } from '../case-api';
import { ExportApi } from '../export-api';
import { HoldApi } from '../hold-api';
import { PlatformApi } from '../platform-api';
import { SearchApi } from '../search-api';
import { toApiError } from './http-support';

interface WireDispositionRun {
  runId?: string;
  id?: string;
  startedAt: string;
  finishedAt: string | null;
  scanned: number;
  deleted: number;
  skippedOnHold: number;
  failed: number;
  s3ObjectsPurged?: number;
  searchPurgePublished?: number;
  dryRun: boolean;
  deleteCapReached?: boolean;
}

/** Each service is proxied under its own health path; see proxy.conf.json. */
const SERVICES: { name: string; path: string; port: number }[] = [
  { name: 'ingestion-service', path: 'ingestion', port: 8081 },
  { name: 'search-service', path: 'search', port: 8082 },
  { name: 'case-hold-service', path: 'case-hold', port: 8083 },
  { name: 'export-audit-service', path: 'export-audit', port: 8084 },
];

@Injectable()
export class HttpPlatformApi extends PlatformApi {
  private readonly http = inject(HttpClient);
  private readonly search = inject(SearchApi);
  private readonly cases = inject(CaseApi);
  private readonly holds = inject(HoldApi);
  private readonly exports = inject(ExportApi);
  /** Retention policy read/write and the disposition trigger have no endpoint. */
  private readonly fallback = inject(MockPlatformApi);

  /**
   * Fans out across services because no single endpoint reports platform-wide
   * counts. A failing service contributes zero rather than failing the whole
   * dashboard, so one service being down does not blank the page.
   */
  counts(): Observable<DashboardCounts> {
    // Each breakdown is a filtered search asking only for the total, so size=1
    // keeps the payloads tiny.
    const total = (criteria: Record<string, unknown>) =>
      this.search
        .search({ q: '', size: 1, ...criteria } as never)
        .pipe(
          map((response) => response.total),
          catchError(() => of(0)),
        );

    return forkJoin({
      stats: this.search.stats().pipe(catchError(() => of(null as SearchStats | null))),
      cases: this.cases.listCases().pipe(catchError(() => of([]))),
      holds: this.holds.listHolds().pipe(catchError(() => of([]))),
      exports: this.exports.listJobs().pipe(catchError(() => of([]))),
      emailCount: total({ communicationType: 'EMAIL' }),
      chatCount: total({ communicationType: 'CHAT' }),
      withAttachments: total({ hasAttachments: true }),
      heldMessages: total({ onHold: true }),
    }).pipe(
      map(({ stats, cases, holds, exports, emailCount, chatCount, withAttachments, heldMessages }) => ({
        totalMessages: stats?.indexedCount ?? 0,
        indexedMessages: stats?.indexedCount ?? 0,
        emailCount,
        chatCount,
        withAttachments,
        // No service counts distinct custodians; the sender list is not exposed.
        custodians: 0,
        activeCases: cases.filter((item) => item.status !== 'CLOSED').length,
        totalCases: cases.length,
        activeHolds: holds.filter((hold) => hold.status === 'ACTIVE').length,
        heldMessages,
        exportsCompleted: exports.filter((job) => job.status === 'COMPLETED').length,
        exportsInFlight: exports.filter(
          (job) => job.status === 'QUEUED' || job.status === 'RUNNING',
        ).length,
        // Audit is queryable per case or target only, so there is no total.
        auditEntries: 0,
        // Disposed messages are deleted outright, so nothing remains to count;
        // the disposition runs below carry the history.
        disposedMessages: 0,
      })),
    );
  }

  serviceStatus(): Observable<ServiceStatus[]> {
    return forkJoin(
      SERVICES.map((service) =>
        this.http.get<{ status: string }>(`${environment.api.health}/${service.path}`).pipe(
          map(
            (health): ServiceStatus => ({
              id: service.path,
              name: service.name,
              port: service.port,
              health: health.status === 'UP' ? 'UP' : 'DEGRADED',
              detail: health.status,
            }),
          ),
          catchError(() =>
            of<ServiceStatus>({
              id: service.path,
              name: service.name,
              port: service.port,
              health: 'DOWN',
              detail: 'unreachable',
            }),
          ),
        ),
      ),
    );
  }

  /** Retention periods are configuration on the ingestion service, not an API. */
  retentionPolicies(): Observable<RetentionPolicy[]> {
    return this.fallback.retentionPolicies();
  }

  updateRetentionPolicy(
    communicationType: CommunicationType,
    retentionMinutes: number,
  ): Observable<RetentionPolicy> {
    return this.fallback.updateRetentionPolicy(communicationType, retentionMinutes);
  }

  dispositionRuns(): Observable<DispositionRun[]> {
    return this.http
      .get<WireDispositionRun[]>(`${environment.api.ingestion}/disposition/runs`)
      .pipe(map((runs) => runs.map((run) => this.toRun(run))), catchError(toApiError));
  }

  /** The disposition job is scheduled; there is no endpoint to trigger one. */
  runDisposition(): Observable<DispositionRun> {
    return this.fallback.runDisposition();
  }

  private toRun(run: WireDispositionRun): DispositionRun {
    return {
      id: run.runId ?? run.id ?? '',
      startedAt: run.startedAt,
      completedAt: run.finishedAt,
      // Every run recorded by the service comes from the scheduler; there is
      // no manual trigger.
      trigger: 'SCHEDULED',
      scanned: run.scanned,
      deleted: run.deleted,
      skippedOnHold: run.skippedOnHold,
      failed: run.failed,
      // The run summary carries counts, not the ids it touched.
      deletedSample: [],
      skippedSample: [],
    };
  }
}
