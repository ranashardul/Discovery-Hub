import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of, switchMap } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  CreateExportRequest,
  ExportJob,
  ExportManifest,
  VerificationReport,
  toExportScope,
} from '../../models/export';
import { CaseApi } from '../case-api';
import { ExportApi } from '../export-api';
import { toApiError, toParams } from './http-support';

interface WireExportJob {
  exportId: string;
  caseId: string;
  holdId: string | null;
  scope: string;
  requestedBy: string;
  status: string;
  s3Bucket: string | null;
  s3Key: string | null;
  packageSizeBytes: number;
  packageSha256: string | null;
  messageCount: number;
  attachmentCount: number;
  attempts: number;
  lastError: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  updatedAt: string;
}

interface WireVerify {
  exportId: string;
  verified: boolean;
  packageChecksumMatches: boolean;
  recordedPackageSha256: string | null;
  recomputedPackageSha256: string | null;
  items: { itemId?: string; path?: string; matches?: boolean }[] | null;
}

/** Wire shape of GET /api/exports/{id}/manifest. */
interface WireManifestItem {
  type: string;
  path: string;
  messageId: string;
  attachmentId: string | null;
  filename: string | null;
  sizeBytes: number | null;
  sha256: string | null;
}

interface WireManifest {
  exportId: string;
  caseId: string;
  scope: string;
  requestedBy: string;
  createdAt: string;
  packageSha256: string | null;
  messageCount: number;
  attachmentCount: number;
  items: WireManifestItem[] | null;
}

interface WireDownloadUrl {
  exportId: string;
  downloadUrl: string;
  s3Bucket: string;
  s3Key: string;
}

const ACTOR = 'discovery-hub-ui';

@Injectable()
export class HttpExportApi extends ExportApi {
  private readonly http = inject(HttpClient);
  private readonly cases = inject(CaseApi);

  private readonly base = `${environment.api.exportAudit}/exports`;

  /**
   * Exports are addressable per case only — the collection endpoint requires
   * caseId and fails without it — so listing everything means asking every
   * case, as with holds.
   */
  listJobs(caseId?: string | null): Observable<ExportJob[]> {
    if (caseId) {
      return this.listForCase(caseId);
    }

    return this.cases.listCases().pipe(
      switchMap((cases) => {
        if (cases.length === 0) {
          return of([] as ExportJob[]);
        }
        return forkJoin(cases.map((item) => this.listForCase(item.id, item.name))).pipe(
          map((perCase) => perCase.flat()),
        );
      }),
    );
  }

  private listForCase(caseId: string, caseName?: string): Observable<ExportJob[]> {
    return this.http.get<WireExportJob[]>(this.base, { params: toParams({ caseId }) }).pipe(
      map((jobs) => jobs.map((job) => this.toJob(job, caseName))),
      catchError(toApiError),
    );
  }

  getJob(id: string): Observable<ExportJob> {
    return this.http
      .get<WireExportJob>(`${this.base}/${encodeURIComponent(id)}`)
      .pipe(map((job) => this.toJob(job)), catchError(toApiError));
  }

  requestExport(request: CreateExportRequest): Observable<ExportJob> {
    return this.http
      .post<WireExportJob>(this.base, {
        caseId: request.caseId,
        scope: request.scopeType,
        holdId: request.holdId ?? null,
        requestedBy: ACTOR,
      })
      .pipe(map((job) => this.toJob(job)), catchError(toApiError));
  }

  retryJob(id: string): Observable<ExportJob> {
    return this.http
      .post<WireExportJob>(`${this.base}/${encodeURIComponent(id)}/retry`, {})
      .pipe(map((job) => this.toJob(job)), catchError(toApiError));
  }

  /**
   * The package inventory, read out of the ZIP by the service.
   *
   * Costs an S3 fetch, so it is asked for only when a reviewer opens the
   * manifest rather than being folded into the job listing.
   */
  getManifest(id: string): Observable<ExportManifest> {
    return this.http
      .get<WireManifest>(`${this.base}/${encodeURIComponent(id)}/manifest`)
      .pipe(
        map((manifest): ExportManifest => {
          const entries = manifest.items ?? [];
          return {
            jobId: manifest.exportId,
            caseId: manifest.caseId,
            generatedAt: manifest.createdAt,
            itemCount: entries.length,
            totalSizeBytes: entries.reduce((total, item) => total + (item.sizeBytes ?? 0), 0),
            // Null until the package checksum is written into the manifest;
            // the job record carries it either way.
            packageChecksum: manifest.packageSha256 ?? '',
            algorithm: 'SHA-256',
            entries: entries.map((item) => ({
              itemId: item.attachmentId ?? item.messageId,
              path: item.path,
              itemType: item.type === 'ATTACHMENT' ? 'ATTACHMENT' : 'MESSAGE',
              sizeBytes: item.sizeBytes ?? 0,
              sha256: item.sha256 ?? '',
            })),
          };
        }),
        catchError(toApiError),
      );
  }

  verifyPackage(id: string): Observable<VerificationReport> {
    return this.http
      .post<WireVerify>(`${this.base}/${encodeURIComponent(id)}/verify`, {})
      .pipe(
        map((response): VerificationReport => {
          const items = response.items ?? [];
          return {
            jobId: response.exportId,
            verifiedAt: new Date().toISOString(),
            passed: response.verified,
            itemsChecked: items.length,
            packageChecksumMatches: response.packageChecksumMatches,
            mismatches: items
              .filter((item) => item.matches === false)
              .map((item) => ({
                itemId: item.path ?? item.itemId ?? 'unknown',
                // The service reports a boolean per item, not the two digests,
                // so there is nothing truthful to put here.
                expected: response.recordedPackageSha256 ?? '',
                actual: response.recomputedPackageSha256 ?? '',
              })),
          };
        }),
        catchError(toApiError),
      );
  }

  /**
   * The service mints a presigned URL on demand rather than tracking downloads,
   * so this fetches the link and returns the refreshed job carrying it.
   */
  registerDownload(id: string): Observable<ExportJob> {
    return this.http.get<WireDownloadUrl>(`${this.base}/${encodeURIComponent(id)}/download`).pipe(
      catchError(toApiError),
      switchMap((download) =>
        this.getJob(id).pipe(
          map((job) => ({
            ...job,
            downloadUrl: download.downloadUrl,
            // The service does not report the presigned URL's lifetime, so the
            // configured expiry is the best the UI can state.
            downloadExpiresAt: null,
          })),
        ),
      ),
    );
  }

  private toJob(job: WireExportJob, caseName?: string): ExportJob {
    const total = job.messageCount + job.attachmentCount;
    return {
      id: job.exportId,
      caseId: job.caseId,
      caseName: caseName ?? '',
      scopeType: toExportScope(job.scope),
      holdId: job.holdId,
      status: job.status as ExportJob['status'],
      requestedAt: job.createdAt,
      requestedBy: job.requestedBy,
      startedAt: job.startedAt,
      completedAt: job.completedAt,
      // The job records no percentage; only the terminal states are known.
      progress: job.status === 'COMPLETED' ? 100 : job.status === 'RUNNING' ? 50 : 0,
      itemCount: total,
      packageSizeBytes: job.packageSizeBytes,
      packageChecksum: job.packageSha256,
      downloadUrl: null,
      downloadExpiresAt: null,
      downloadCount: 0,
      attempt: job.attempts,
      failureReason: job.lastError,
    };
  }
}
