export type ExportJobStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

/**
 * What an export covers, matching `ExportScope` in export-audit-service. A
 * `CASE` export assembles the case's evidence items; a `LEGAL_HOLD` export
 * assembles everything under a hold scope.
 */
export type ExportScopeType = 'CASE' | 'LEGAL_HOLD';

export const EXPORT_SCOPES: readonly ExportScopeType[] = ['CASE', 'LEGAL_HOLD'];

/** Narrows a scope off the wire, failing loudly rather than casting blindly. */
export function toExportScope(value: string): ExportScopeType {
  if ((EXPORT_SCOPES as readonly string[]).includes(value)) {
    return value as ExportScopeType;
  }
  throw new Error(`Unknown export scope from export-audit-service: ${value}`);
}

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
