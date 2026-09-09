import { Observable } from 'rxjs';
import {
  CreateExportRequest,
  ExportJob,
  ExportManifest,
  VerificationReport,
} from '../models/export';

/** Contract of the export service (`/api/v1/exports`). */
export abstract class ExportApi {
  abstract listJobs(caseId?: string | null): Observable<ExportJob[]>;

  abstract getJob(id: string): Observable<ExportJob>;

  /** `POST /api/v1/exports` — returns the job ID immediately (FR-6.2). */
  abstract requestExport(request: CreateExportRequest): Observable<ExportJob>;

  /** Retries a FAILED job in place; never produces a second package (FR-6.6). */
  abstract retryJob(id: string): Observable<ExportJob>;

  abstract getManifest(id: string): Observable<ExportManifest>;

  /** Re-computes every checksum and compares against the manifest (FR-6.5). */
  abstract verifyPackage(id: string): Observable<VerificationReport>;

  /** Records the download and refreshes the expiring link if it lapsed. */
  abstract registerDownload(id: string): Observable<ExportJob>;
}
