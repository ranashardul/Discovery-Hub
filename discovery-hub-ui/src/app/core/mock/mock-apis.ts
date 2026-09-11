import { Injectable, inject } from '@angular/core';
import { Observable, defer, delay, of, throwError } from 'rxjs';
import { ApiError } from '../api/api-error';
import { AuditApi } from '../api/audit-api';
import { AddEvidenceRequest, CaseApi, CaseListQuery } from '../api/case-api';
import { DemoApi } from '../api/demo-api';
import { ExportApi } from '../api/export-api';
import { HoldApi } from '../api/hold-api';
import { PlatformApi } from '../api/platform-api';
import { SearchApi } from '../api/search-api';
import { AuditEntry, AuditPage, AuditQuery } from '../models/audit';
import {
  DemoIngestAcceptance,
  DemoRetentionStatus,
  DemoStorageProof,
} from '../models/demo';
import {
  CaseCustodian,
  CaseStatus,
  CreateCaseRequest,
  EvidenceItem,
  LegalCase,
} from '../models/case';
import {
  CreateExportRequest,
  ExportJob,
  ExportManifest,
  VerificationReport,
} from '../models/export';
import { DeletionAttemptResult, HoldScope, LegalHold, PlaceHoldRequest } from '../models/hold';
import { CommunicationType, Custodian, Message } from '../models/message';
import {
  DashboardCounts,
  DispositionRun,
  RetentionPolicy,
  ServiceStatus,
} from '../models/retention';
import { SavedSearch, SearchCriteria, SearchResponse, SearchStats } from '../models/search';
import { MockStore } from './mock-store';

/**
 * Wraps a synchronous store call in an Observable that behaves like a network
 * call: it is cold, it takes time, and it fails with 503 when the owning
 * service has been switched off on the dashboard (NFR-2). Screens therefore hit
 * the same loading and error paths they will hit against the real services.
 */
function remote<T>(
  store: MockStore,
  serviceId: string,
  serviceName: string,
  latencyMs: number,
  work: () => T,
): Observable<T> {
  return defer(() => {
    const status = store.serviceStatus().find((candidate) => candidate.id === serviceId);
    if (status?.health === 'DOWN') {
      throw new ApiError(503, `${serviceName} is unavailable. Other screens keep working.`);
    }
    return of(work());
  }).pipe(delay(latencyMs));
}

@Injectable()
export class MockSearchApi extends SearchApi {
  private readonly store = inject(MockStore);

  override search(criteria: SearchCriteria): Observable<SearchResponse> {
    return remote(this.store, 'search', 'Search service', 140, () =>
      this.store.search(criteria, { audit: true }),
    );
  }

  override getMessage(messageId: string): Observable<Message> {
    return remote(this.store, 'search', 'Search service', 70, () =>
      this.store.getMessage(messageId),
    );
  }

  override stats(): Observable<SearchStats> {
    return remote(this.store, 'search', 'Search service', 60, () => this.store.searchStats());
  }

  override getThread(threadId: string): Observable<Message[]> {
    return remote(this.store, 'search', 'Search service', 90, () => this.store.getThread(threadId));
  }

  override resolveAllIds(criteria: SearchCriteria): Observable<string[]> {
    return remote(this.store, 'search', 'Search service', 200, () =>
      this.store.resolveAllIds(criteria),
    );
  }

  override listSavedSearches(caseId: string): Observable<SavedSearch[]> {
    return remote(this.store, 'search', 'Search service', 60, () =>
      this.store.listSavedSearches(caseId),
    );
  }

  override saveSearch(caseId: string, name: string, criteria: SearchCriteria): Observable<SavedSearch> {
    return remote(this.store, 'search', 'Search service', 80, () =>
      this.store.saveSearch(caseId, name, criteria),
    );
  }

  override deleteSavedSearch(id: string): Observable<void> {
    return remote(this.store, 'search', 'Search service', 60, () =>
      this.store.deleteSavedSearch(id),
    );
  }
}

@Injectable()
export class MockCaseApi extends CaseApi {
  private readonly store = inject(MockStore);

  override listCases(query: CaseListQuery = {}): Observable<LegalCase[]> {
    return remote(this.store, 'case-hold', 'Case & hold service', 90, () =>
      this.store.listCases(query),
    );
  }

  override getCase(id: string): Observable<LegalCase> {
    return remote(this.store, 'case-hold', 'Case & hold service', 80, () => this.store.getCase(id));
  }

  override createCase(request: CreateCaseRequest): Observable<LegalCase> {
    return remote(this.store, 'case-hold', 'Case & hold service', 120, () =>
      this.store.createCase(request),
    );
  }

  override updateCase(id: string, patch: Partial<CreateCaseRequest>): Observable<LegalCase> {
    return remote(this.store, 'case-hold', 'Case & hold service', 110, () =>
      this.store.updateCase(id, patch),
    );
  }

  override changeStatus(id: string, status: CaseStatus): Observable<LegalCase> {
    return remote(this.store, 'case-hold', 'Case & hold service', 110, () =>
      this.store.changeStatus(id, status),
    );
  }

  override listCustodians(caseId: string): Observable<CaseCustodian[]> {
    return remote(this.store, 'case-hold', 'Case & hold service', 70, () =>
      this.store.listCaseCustodians(caseId),
    );
  }

  override addCustodian(caseId: string, custodianId: string): Observable<CaseCustodian> {
    return remote(this.store, 'case-hold', 'Case & hold service', 100, () =>
      this.store.addCustodian(caseId, custodianId),
    );
  }

  override removeCustodian(caseId: string, custodianId: string): Observable<void> {
    return remote(this.store, 'case-hold', 'Case & hold service', 100, () =>
      this.store.removeCustodian(caseId, custodianId),
    );
  }

  override listEvidence(caseId: string): Observable<EvidenceItem[]> {
    return remote(this.store, 'case-hold', 'Case & hold service', 90, () =>
      this.store.listEvidence(caseId),
    );
  }

  override addEvidence(caseId: string, request: AddEvidenceRequest): Observable<EvidenceItem[]> {
    return remote(this.store, 'case-hold', 'Case & hold service', 150, () =>
      this.store.addEvidence(caseId, request),
    );
  }

  override removeEvidence(caseId: string, messageId: string): Observable<void> {
    return remote(this.store, 'case-hold', 'Case & hold service', 100, () =>
      this.store.removeEvidence(caseId, messageId),
    );
  }

  override listAllCustodians(): Observable<Custodian[]> {
    return remote(this.store, 'case-hold', 'Case & hold service', 80, () =>
      this.store.listCustodianDirectory(),
    );
  }
}

@Injectable()
export class MockHoldApi extends HoldApi {
  private readonly store = inject(MockStore);

  override listHolds(caseId?: string | null): Observable<LegalHold[]> {
    return remote(this.store, 'case-hold', 'Case & hold service', 90, () =>
      this.store.listHolds(caseId),
    );
  }

  override getHold(id: string): Observable<LegalHold> {
    return remote(this.store, 'case-hold', 'Case & hold service', 70, () => this.store.getHold(id));
  }

  override placeHold(request: PlaceHoldRequest): Observable<LegalHold> {
    return remote(this.store, 'case-hold', 'Case & hold service', 160, () =>
      this.store.placeHold(request),
    );
  }

  override releaseHold(id: string, releasedBy: string): Observable<LegalHold> {
    return remote(this.store, 'case-hold', 'Case & hold service', 130, () =>
      this.store.releaseHold(id, releasedBy),
    );
  }

  override previewScope(scope: HoldScope): Observable<number> {
    return remote(this.store, 'case-hold', 'Case & hold service', 180, () =>
      this.store.previewScope(scope),
    );
  }

  override attemptDelete(messageId: string): Observable<DeletionAttemptResult> {
    return remote(this.store, 'ingestion', 'Ingestion & archival service', 150, () =>
      this.store.attemptDelete(messageId),
    );
  }
}

@Injectable()
export class MockExportApi extends ExportApi {
  private readonly store = inject(MockStore);

  override listJobs(caseId?: string | null): Observable<ExportJob[]> {
    return remote(this.store, 'export-audit', 'Export & audit service', 80, () =>
      this.store.listExports(caseId),
    );
  }

  override getJob(id: string): Observable<ExportJob> {
    return remote(this.store, 'export-audit', 'Export & audit service', 60, () =>
      this.store.getExport(id),
    );
  }

  override requestExport(request: CreateExportRequest): Observable<ExportJob> {
    return remote(this.store, 'export-audit', 'Export & audit service', 120, () =>
      this.store.requestExport(request),
    );
  }

  override retryJob(id: string): Observable<ExportJob> {
    return remote(this.store, 'export-audit', 'Export & audit service', 110, () =>
      this.store.retryExport(id),
    );
  }

  override getManifest(id: string): Observable<ExportManifest> {
    return remote(this.store, 'export-audit', 'Export & audit service', 130, () =>
      this.store.getManifest(id),
    );
  }

  override verifyPackage(id: string): Observable<VerificationReport> {
    return remote(this.store, 'export-audit', 'Export & audit service', 400, () =>
      this.store.verifyExport(id),
    );
  }

  override registerDownload(id: string): Observable<ExportJob> {
    return remote(this.store, 'export-audit', 'Export & audit service', 90, () =>
      this.store.registerDownload(id),
    );
  }
}

@Injectable()
export class MockAuditApi extends AuditApi {
  private readonly store = inject(MockStore);

  override query(query: AuditQuery): Observable<AuditPage> {
    return remote(this.store, 'export-audit', 'Export & audit service', 100, () =>
      this.store.queryAudit(query),
    );
  }

  override getEntry(id: string): Observable<AuditEntry> {
    return remote(this.store, 'export-audit', 'Export & audit service', 60, () =>
      this.store.getAuditEntry(id),
    );
  }

  override listActors(): Observable<string[]> {
    return remote(this.store, 'export-audit', 'Export & audit service', 60, () =>
      this.store.listActors(),
    );
  }
}

@Injectable()
export class MockPlatformApi extends PlatformApi {
  private readonly store = inject(MockStore);

  override counts(): Observable<DashboardCounts> {
    return of(this.store.counts()).pipe(delay(120));
  }

  /** Never fails: it is the screen that reports failure. */
  override serviceStatus(): Observable<ServiceStatus[]> {
    return of(this.store.serviceStatus()).pipe(delay(50));
  }

  override retentionPolicies(): Observable<RetentionPolicy[]> {
    return remote(this.store, 'ingestion', 'Ingestion & archival service', 80, () =>
      this.store.retentionPolicies(),
    );
  }

  override updateRetentionPolicy(
    communicationType: CommunicationType,
    retentionMinutes: number,
  ): Observable<RetentionPolicy> {
    return remote(this.store, 'ingestion', 'Ingestion & archival service', 110, () =>
      this.store.updateRetentionPolicy(communicationType, retentionMinutes),
    );
  }

  override dispositionRuns(): Observable<DispositionRun[]> {
    return remote(this.store, 'ingestion', 'Ingestion & archival service', 80, () =>
      this.store.listDispositionRuns(),
    );
  }

  override runDisposition(): Observable<DispositionRun> {
    return remote(this.store, 'ingestion', 'Ingestion & archival service', 600, () =>
      this.store.runDisposition('MANUAL'),
    );
  }
}

/**
 * Refuses, with a reason.
 *
 * A retention demo is a claim about the real pipeline: a message travelling
 * API → Kafka → worker → MongoDB → S3, and binaries actually leaving the
 * bucket when its period expires. Simulating that in the browser would
 * demonstrate nothing except that the simulation works, and a screen showing
 * a fake purge as though it were real is worse than one that says it needs
 * the services running.
 *
 * Registered so offline mode still resolves the contract and the route
 * mounts, rather than failing injection.
 */
@Injectable()
export class MockDemoApi extends DemoApi {
  private static unavailable<T>(): Observable<T> {
    return throwError(
      () =>
        new ApiError(
          503,
          'The retention demo runs against the real services — it needs ingestion, ' +
            'Kafka and S3 to prove a disposition actually happened. Start the stack ' +
            'and set useMockBackend to false.',
        ),
    );
  }

  override ingest(): Observable<DemoIngestAcceptance> {
    return MockDemoApi.unavailable();
  }

  override resolveMessageId(): Observable<string | null> {
    return MockDemoApi.unavailable();
  }

  override retentionStatus(): Observable<DemoRetentionStatus | null> {
    return MockDemoApi.unavailable();
  }

  override storageProof(): Observable<DemoStorageProof> {
    return MockDemoApi.unavailable();
  }
}
