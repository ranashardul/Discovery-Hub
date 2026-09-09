export type ExportJobStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export type ExportScopeType = 'CASE_EVIDENCE' | 'HOLD_SCOPE';

export interface ManifestEntry {
  itemId: string;
  path: string;
  itemType: 'MESSAGE' | 'ATTACHMENT';
  sizeBytes: number;
  sha256: string;
}

export interface ExportManifest {
  jobId: string;
  caseId: string;
  generatedAt: string;
  itemCount: number;
  totalSizeBytes: number;
  /** SHA-256 over the concatenated per-item digests. */
  packageChecksum: string;
  algorithm: 'SHA-256';
  entries: ManifestEntry[];
}

export interface ExportJob {
  id: string;
  caseId: string;
  caseName: string;
  scopeType: ExportScopeType;
  holdId: string | null;
  status: ExportJobStatus;
  requestedAt: string;
  requestedBy: string;
  startedAt: string | null;
  completedAt: string | null;
  /** 0-100, driven by the worker's progress reports. */
  progress: number;
  itemCount: number;
  packageSizeBytes: number;
  packageChecksum: string | null;
  downloadUrl: string | null;
  /** Expiry of the pre-signed link (FR-6.4). */
  downloadExpiresAt: string | null;
  downloadCount: number;
  attempt: number;
  failureReason: string | null;
}

export interface CreateExportRequest {
  caseId: string;
  scopeType: ExportScopeType;
  holdId?: string | null;
}

/** Result of re-computing checksums against a manifest (FR-6.5). */
export interface VerificationReport {
  jobId: string;
  verifiedAt: string;
  passed: boolean;
  itemsChecked: number;
  packageChecksumMatches: boolean;
  mismatches: { itemId: string; expected: string; actual: string }[];
}
