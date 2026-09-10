import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { environment } from '../../../../environments/environment';

import { CommunicationType } from '../../models/message';
import {
  DashboardCounts,
  DispositionRun,
  RetentionPolicy,
  ServiceStatus,
} from '../../models/retention';
import { SearchStats } from '../../models/search';
import { AuditApi } from '../audit-api';
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

/** Wire shape of GET /api/ingestion/retention/policies. */
interface WireRetentionPolicy {
  communicationType: string;
  retentionPeriod: string;
  retentionMinutes: number;
}

const ACTOR = 'discovery-hub-ui';

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
  private readonly audit = inject(AuditApi);

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
      custodians: this.cases.listAllCustodians().pipe(
        map((list) => list.length),
        catchError(() => of(0)),
      ),
      // One entry is enough to learn the total, so size=1 keeps the payload
      // small on a trail that only grows.
      auditEntries: this.audit.query({ page: 0, size: 1 }).pipe(
        map((page) => page.total),
        catchError(() => of(0)),
      ),
    }).pipe(
      map((counts) => ({
        totalMessages: counts.stats?.indexedCount ?? 0,
        indexedMessages: counts.stats?.indexedCount ?? 0,
        emailCount: counts.emailCount,
        chatCount: counts.chatCount,
        withAttachments: counts.withAttachments,
        custodians: counts.custodians,
        activeCases: counts.cases.filter((item) => item.status !== 'CLOSED').length,
        totalCases: counts.cases.length,
        activeHolds: counts.holds.filter((hold) => hold.status === 'ACTIVE').length,
        heldMessages: counts.heldMessages,
        exportsCompleted: counts.exports.filter((job) => job.status === 'COMPLETED').length,
        exportsInFlight: counts.exports.filter(
          (job) => job.status === 'QUEUED' || job.status === 'RUNNING',
        ).length,
        auditEntries: counts.auditEntries,
        // Disposed messages are deleted outright, so nothing remains to count;
        // the disposition runs carry the history.
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

  /**
   * Effective retention periods. The service layers any period set through
   * this API over the ones the environment was configured with, so what comes
   * back is what disposition will actually apply.
   *
   * DEFAULT is filtered out: it is the fallback for unmapped types, not a
   * communication type the screen can edit.
   */
  retentionPolicies(): Observable<RetentionPolicy[]> {
    return this.http
      .get<WireRetentionPolicy[]>(`${environment.api.ingestion}/retention/policies`)
      .pipe(
        map((policies) =>
          policies
            .filter((policy) => policy.communicationType !== 'DEFAULT')
            .map(
              (policy): RetentionPolicy => ({
                communicationType: policy.communicationType as CommunicationType,
                retentionMinutes: policy.retentionMinutes,
                // The service records who changed a period but does not
                // return it on the read; the audit trail carries the history.
                updatedAt: '',
                updatedBy: '',
              }),
            ),
        ),
        catchError(toApiError),
      );
  }

  /**
   * Shortening a period means the next disposition run deletes material that
   * was previously in scope. The service refuses anything below its configured
   * floor, so a 400 here is the guard rail working, not a client bug.
   */
  updateRetentionPolicy(
    communicationType: CommunicationType,
    retentionMinutes: number,
  ): Observable<RetentionPolicy> {
    return this.http
      .put<WireRetentionPolicy>(
        `${environment.api.ingestion}/retention/policies/${encodeURIComponent(communicationType)}`,
        { retentionMinutes, updatedBy: ACTOR },
      )
      .pipe(
        map(
          (policy): RetentionPolicy => ({
            communicationType: policy.communicationType as CommunicationType,
            retentionMinutes: policy.retentionMinutes,
            updatedAt: new Date().toISOString(),
            updatedBy: ACTOR,
          }),
        ),
        catchError(toApiError),
      );
  }

  dispositionRuns(): Observable<DispositionRun[]> {
    return this.http
      .get<WireDispositionRun[]>(`${environment.api.ingestion}/disposition/runs`)
      .pipe(map((runs) => runs.map((run) => this.toRun(run))), catchError(toApiError));
  }

  /**
   * Runs a disposition pass now rather than waiting for the scheduler.
   *
   * This deletes data. The service refuses it outright when disposition is
   * disabled for the environment, and while another pass is in flight, so
   * both come back as a 409 rather than doing something surprising.
   */
  runDisposition(): Observable<DispositionRun> {
    return this.http
      .post<WireDispositionRun>(`${environment.api.ingestion}/disposition/runs`, {})
      .pipe(map((run) => this.toRun(run)), catchError(toApiError));
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
