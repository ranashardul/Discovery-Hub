import { Injectable, OnDestroy, signal } from '@angular/core';
import { ApiError } from '../api/api-error';
import { AddEvidenceRequest, CaseListQuery } from '../api/case-api';
import { AuditAction, AuditEntry, AuditPage, AuditQuery, AuditTargetType } from '../models/audit';
import {
  ALLOWED_CASE_TRANSITIONS,
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
  ManifestEntry,
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
import { Corpus, generateCorpus } from './corpus';
import { ACTORS, CASE_SEEDS } from './corpus-vocabulary';
import { SeededRandom } from './random';
import { executeSearch, parseQuery, toResultItem } from './search-engine';

/** The single persona from the requirements; every mutation is attributed here. */
export const CURRENT_ACTOR = 'ines.beaumont@stonewall-bank.example';

const MINUTES = 60 * 1000;
const TICK_MS = 900;

interface CaseRecord {
  id: string;
  caseNumber: string;
  name: string;
  description: string;
  matterType: CreateCaseRequest['matterType'];
  owner: string;
  status: CaseStatus;
  createdAt: string;
  updatedAt: string;
  closedAt: string | null;
}

interface HoldRecord extends Omit<LegalHold, 'caseName' | 'matchedMessageCount'> {
  /** Messages the propagation worker has stamped so far. */
  stamped: Set<string>;
  /** Full scope, resolved when the hold was placed. */
  pending: string[];
}

interface ExportRecord extends ExportJob {
  manifest: ExportManifest | null;
  /** Flipped by the "simulate tampering" action to prove verification works. */
  tampered: boolean;
}

/**
 * In-memory stand-in for the four backend services.
 *
 * It exists so the prototype can be driven end to end — search, hold,
 * disposition, export, audit — without any service running. Behaviour that is
 * asynchronous in the real platform (hold propagation, export jobs) is
 * asynchronous here too, advanced by a single timer, so the UI has to deal with
 * the same in-flight states it will deal with in production.
 */
@Injectable({ providedIn: 'root' })
export class MockStore implements OnDestroy {
  private readonly random = new SeededRandom(1337);
  private readonly corpus: Corpus;

  private readonly cases: CaseRecord[] = [];
  private readonly caseCustodians: CaseCustodian[] = [];
  private readonly evidence: EvidenceItem[] = [];
  private readonly holds: HoldRecord[] = [];
  private readonly exports: ExportRecord[] = [];
  private readonly audit: AuditEntry[] = [];
  private readonly savedSearches: SavedSearch[] = [];
  private readonly retention: RetentionPolicy[] = [];
  private readonly dispositionRuns: DispositionRun[] = [];
  private readonly serviceStatuses: ServiceStatus[] = [];

  private sequence = 1;
  private caseCounter = 0;
  private holdCounter = 0;
  private exportCounter = 0;
  private disposedCount = 0;
  private readonly timer: ReturnType<typeof setInterval>;

  /** Bumped whenever background work changes state, so views can react. */
  readonly revision = signal(0);

  constructor() {
    this.corpus = generateCorpus();
    this.seedRetention();
    this.seedCases();
    this.seedHolds();
    this.seedExports();
    this.seedServiceStatus();
    this.applyRetentionStatus();
    this.timer = setInterval(() => this.tick(), TICK_MS);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  // ---------------------------------------------------------------- search

  search(criteria: SearchCriteria, options: { audit?: boolean } = {}): SearchResponse {
    if (!criteria.q || !criteria.q.trim()) {
      throw ApiError.badRequest("Query parameter 'q' is required and must not be blank", 'q');
    }

    const started = performance.now();
    const from = Math.max(0, criteria.from ?? 0);
    const size = Math.min(100, Math.max(1, criteria.size ?? 20));
    const hits = executeSearch(this.corpus, criteria);
    const terms = parseQuery(criteria.q);

    const results = hits
      .slice(from, from + size)
      .map((hit) => toResultItem(this.corpus.messages[hit.index], hit.score, terms));

    if (options.audit) {
      this.append('SEARCH_EXECUTED', 'SEARCH', criteria.q, `Query "${criteria.q}"`, {
        summary: `Search executed — ${hits.length} matching messages`,
        after: { criteria: criteria as unknown as Record<string, unknown>, total: hits.length },
      });
    }

    return {
      query: criteria.q,
      total: hits.length,
      from,
      size,
      sort: criteria.sort ?? 'relevance',
      tookMillis: Math.max(1, Math.round(performance.now() - started)),
      results,
    };
  }

  resolveAllIds(criteria: SearchCriteria): string[] {
    return executeSearch(this.corpus, criteria).map((hit) => this.corpus.messages[hit.index].id);
  }

  searchStats(): SearchStats {
    return {
      indexedCount: this.corpus.messages.length - this.disposedCount,
      index: 'messages',
      pendingFailures: 0,
    };
  }

  getMessage(messageId: string): Message {
    const message = this.corpus.byId.get(messageId);
    if (!message) {
      throw ApiError.notFound(`No message indexed for messageId ${messageId}`);
    }
    return message;
  }

  getThread(threadId: string): Message[] {
    return this.corpus.messages
      .filter((message) => message.threadId === threadId)
      .sort((left, right) => Date.parse(left.messageTimestamp) - Date.parse(right.messageTimestamp));
  }

  listSavedSearches(caseId: string): SavedSearch[] {
    return this.savedSearches.filter((saved) => saved.caseId === caseId);
  }

  saveSearch(caseId: string, name: string, criteria: SearchCriteria): SavedSearch {
    const legalCase = this.requireCase(caseId);
    const saved: SavedSearch = {
      id: `srch-${this.savedSearches.length + 1}`,
      caseId,
      name,
      criteria,
      createdAt: new Date().toISOString(),
      lastRunAt: null,
      lastRunTotal: null,
    };
    this.savedSearches.push(saved);
    this.append('SEARCH_SAVED', 'SEARCH', saved.id, name, {
      caseId,
      summary: `Saved search "${name}" on case ${legalCase.caseNumber}`,
      after: { name, criteria: criteria as unknown as Record<string, unknown> },
    });
    return saved;
  }

  deleteSavedSearch(id: string): void {
    const index = this.savedSearches.findIndex((saved) => saved.id === id);
    if (index < 0) {
      throw ApiError.notFound(`No saved search ${id}`);
    }
    this.savedSearches.splice(index, 1);
  }

  // ----------------------------------------------------------------- cases

  listCases(query: CaseListQuery = {}): LegalCase[] {
    const needle = query.q?.toLowerCase().trim();

    return this.cases
      .filter((record) => !query.status || record.status === query.status)
      .filter(
        (record) =>
          !needle ||
          record.name.toLowerCase().includes(needle) ||
          record.caseNumber.toLowerCase().includes(needle) ||
          record.description.toLowerCase().includes(needle),
      )
      .map((record) => this.toCase(record))
      .sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt));
  }

  getCase(id: string): LegalCase {
    return this.toCase(this.requireCase(id));
  }

  createCase(request: CreateCaseRequest): LegalCase {
    if (!request.name?.trim()) {
      throw ApiError.badRequest('Case name is required', 'name');
    }
    if (!request.owner?.trim()) {
      throw ApiError.badRequest('Case owner is required', 'owner');
    }

    const now = new Date().toISOString();
    const record: CaseRecord = {
      id: `case-${String(++this.caseCounter).padStart(4, '0')}`,
      caseNumber: this.nextCaseNumber(),
      name: request.name.trim(),
      description: request.description?.trim() ?? '',
      matterType: request.matterType,
      owner: request.owner.trim(),
      status: 'OPEN',
      createdAt: now,
      updatedAt: now,
      closedAt: null,
    };

    this.cases.push(record);
    this.append('CASE_CREATED', 'CASE', record.id, record.caseNumber, {
      caseId: record.id,
      summary: `Case ${record.caseNumber} "${record.name}" created`,
      after: {
        name: record.name,
        matterType: record.matterType,
        owner: record.owner,
        status: record.status,
      },
    });

    return this.toCase(record);
  }

  updateCase(id: string, patch: Partial<CreateCaseRequest>): LegalCase {
    const record = this.requireCase(id);
    this.assertOpen(record, 'update');

    const before = {
      name: record.name,
      description: record.description,
      matterType: record.matterType,
      owner: record.owner,
    };

    Object.assign(record, {
      name: patch.name?.trim() ?? record.name,
      description: patch.description?.trim() ?? record.description,
      matterType: patch.matterType ?? record.matterType,
      owner: patch.owner?.trim() ?? record.owner,
      updatedAt: new Date().toISOString(),
    });

    this.append('CASE_UPDATED', 'CASE', record.id, record.caseNumber, {
      caseId: record.id,
      summary: `Case ${record.caseNumber} details updated`,
      before,
      after: {
        name: record.name,
        description: record.description,
        matterType: record.matterType,
        owner: record.owner,
      },
    });

    return this.toCase(record);
  }

  changeStatus(id: string, status: CaseStatus): LegalCase {
    const record = this.requireCase(id);
    const allowed = ALLOWED_CASE_TRANSITIONS[record.status];

    if (!allowed.includes(status)) {
      throw ApiError.conflict(
        `Invalid transition ${record.status} → ${status}. Allowed from ${record.status}: ${
          allowed.length ? allowed.join(', ') : 'none — a closed case is final'
        }`,
      );
    }

    const before = record.status;
    record.status = status;
    record.updatedAt = new Date().toISOString();

    if (status === 'CLOSED') {
      record.closedAt = record.updatedAt;
      // Closing a case releases its holds unless another hold still covers the
      // message (FR-4.5); releaseHold handles the overlap.
      for (const hold of this.holds.filter(
        (candidate) => candidate.caseId === id && candidate.status !== 'RELEASED',
      )) {
        this.releaseHold(hold.id, 'system@discoveryhub', `case ${record.caseNumber} closed`);
      }
    }

    this.append('CASE_STATUS_CHANGED', 'CASE', record.id, record.caseNumber, {
      caseId: record.id,
      summary: `Case ${record.caseNumber} moved ${before} → ${status}`,
      before: { status: before },
      after: { status },
    });

    return this.toCase(record);
  }

  listCaseCustodians(caseId: string): CaseCustodian[] {
    this.requireCase(caseId);
    return this.caseCustodians.filter((link) => link.caseId === caseId);
  }

  addCustodian(caseId: string, custodianId: string): CaseCustodian {
    const record = this.requireCase(caseId);
    this.assertOpen(record, 'add custodians to');

    if (this.caseCustodians.some((link) => link.caseId === caseId && link.custodianId === custodianId)) {
      throw ApiError.conflict(`${custodianId} is already a custodian on this case`);
    }

    // In real-service mode the picker shows email addresses derived from the
    // case's evidence, not the synthetic corpus IDs. Fall back to a synthetic
    // custodian when the ID is not part of the seeded corpus.
    const custodian =
      this.corpus.custodians.find((candidate) => candidate.id === custodianId) ??
      this.corpus.custodians.find((candidate) => candidate.email === custodianId) ?? {
        id: custodianId,
        displayName: custodianId,
        email: custodianId,
        department: '',
        title: '',
        messageCount: 0,
      };

    const link: CaseCustodian = {
      caseId,
      custodianId,
      displayName: custodian.displayName,
      email: custodian.email,
      department: custodian.department,
      addedAt: new Date().toISOString(),
      addedBy: CURRENT_ACTOR,
    };

    this.caseCustodians.push(link);
    record.updatedAt = link.addedAt;
    this.append('CUSTODIAN_ADDED', 'CUSTODIAN', custodianId, custodian.displayName, {
      caseId,
      summary: `${custodian.displayName} attached to ${record.caseNumber} as custodian`,
      after: { custodianId, email: custodian.email, department: custodian.department },
    });

    return link;
  }

  removeCustodian(caseId: string, custodianId: string): void {
    const record = this.requireCase(caseId);
    this.assertOpen(record, 'remove custodians from');

    const index = this.caseCustodians.findIndex(
      (link) => link.caseId === caseId && link.custodianId === custodianId,
    );
    if (index < 0) {
      throw ApiError.notFound(`Custodian ${custodianId} is not attached to this case`);
    }

    const [removed] = this.caseCustodians.splice(index, 1);
    record.updatedAt = new Date().toISOString();
    this.append('CUSTODIAN_REMOVED', 'CUSTODIAN', custodianId, removed.displayName, {
      caseId,
      summary: `${removed.displayName} removed from ${record.caseNumber}`,
      before: { custodianId, email: removed.email },
    });
  }

  listEvidence(caseId: string): EvidenceItem[] {
    this.requireCase(caseId);
    return this.evidence
      .filter((item) => item.caseId === caseId)
      .map((item) => ({ ...item, onHold: (this.corpus.byId.get(item.messageId)?.holdCount ?? 0) > 0 }))
      .sort((left, right) => Date.parse(right.addedAt) - Date.parse(left.addedAt));
  }

  addEvidence(caseId: string, request: AddEvidenceRequest): EvidenceItem[] {
    const record = this.requireCase(caseId);
    this.assertOpen(record, 'add evidence to');

    const existing = new Set(
      this.evidence.filter((item) => item.caseId === caseId).map((item) => item.messageId),
    );
    const added: EvidenceItem[] = [];
    const now = new Date().toISOString();

    for (const messageId of request.messageIds) {
      if (existing.has(messageId)) {
        continue;
      }
      const message = this.corpus.byId.get(messageId);
      if (!message) {
        throw ApiError.notFound(`No message ${messageId}`);
      }

      const item: EvidenceItem = {
        id: `ev-${this.evidence.length + added.length + 1}`,
        caseId,
        messageId,
        subject: message.subject,
        sender: message.sender,
        recipients: message.recipients,
        communicationType: message.communicationType,
        messageTimestamp: message.messageTimestamp,
        attachmentCount: message.attachments.length,
        onHold: message.holdCount > 0,
        addedAt: now,
        addedBy: CURRENT_ACTOR,
        source: request.source,
      };

      added.push(item);
      existing.add(messageId);
    }

    this.evidence.push(...added);
    record.updatedAt = now;

    if (added.length > 0) {
      this.append('EVIDENCE_ADDED', 'CASE', caseId, record.caseNumber, {
        caseId,
        summary: `${added.length} message(s) added to ${record.caseNumber} as evidence${
          request.source === 'SEARCH_RESULT_SET' ? ' from a search result set' : ''
        }`,
        after: {
          messageIds: added.slice(0, 25).map((item) => item.messageId),
          count: added.length,
          source: request.source,
        },
      });
    }

    return added;
  }

  removeEvidence(caseId: string, messageId: string): void {
    const record = this.requireCase(caseId);
    this.assertOpen(record, 'remove evidence from');

    if (this.holds.some((hold) => hold.caseId === caseId && hold.status !== 'RELEASED')) {
      throw ApiError.conflict(
        'Cannot remove evidence while the case is under an active legal hold',
      );
    }

    const index = this.evidence.findIndex(
      (item) => item.caseId === caseId && item.messageId === messageId,
    );
    if (index < 0) {
      throw ApiError.notFound(`No evidence item ${messageId} on this case`);
    }

    const [removed] = this.evidence.splice(index, 1);
    record.updatedAt = new Date().toISOString();
    this.append('EVIDENCE_REMOVED', 'CASE', caseId, record.caseNumber, {
      caseId,
      summary: `Evidence ${removed.messageId} removed from ${record.caseNumber}`,
      before: { messageId: removed.messageId, subject: removed.subject },
    });
  }

  listCustodianDirectory(): Custodian[] {
    return [...this.corpus.custodians].sort((left, right) =>
      left.displayName.localeCompare(right.displayName),
    );
  }

  // ----------------------------------------------------------------- holds

  listHolds(caseId?: string | null): LegalHold[] {
    return this.holds
      .filter((hold) => !caseId || hold.caseId === caseId)
      .map((hold) => this.toHold(hold))
      .sort((left, right) => Date.parse(right.placedAt) - Date.parse(left.placedAt));
  }

  getHold(id: string): LegalHold {
    return this.toHold(this.requireHold(id));
  }

  previewScope(scope: HoldScope): number {
    return this.resolveScope(scope).length;
  }

  placeHold(request: PlaceHoldRequest): LegalHold {
    const record = this.requireCase(request.caseId);
    this.assertOpen(record, 'place holds on');

    if (!request.reason?.trim()) {
      throw ApiError.badRequest('A hold reason is required for the chain of custody', 'reason');
    }
    if (request.scope.custodianIds.length === 0) {
      throw ApiError.badRequest('Select at least one custodian to scope the hold', 'custodianIds');
    }

    const inScope = this.resolveScope(request.scope);
    const now = new Date().toISOString();
    const hold: HoldRecord = {
      id: `hold-${String(++this.holdCounter).padStart(4, '0')}`,
      caseId: request.caseId,
      reason: request.reason.trim(),
      // The case-hold service writes a hold as ACTIVE in one transaction, so
      // there is no intermediate state to simulate here.
      status: 'ACTIVE',
      scope: request.scope,
      placedAt: now,
      placedBy: CURRENT_ACTOR,
      releasedAt: null,
      releasedBy: null,
      stamped: new Set<string>(inScope),
      pending: [],
    };

    for (const messageId of inScope) {
      const message = this.corpus.byId.get(messageId);
      if (message) {
        message.holdCount++;
      }
    }

    this.holds.push(hold);
    record.updatedAt = now;
    this.append('HOLD_PLACED', 'HOLD', hold.id, hold.id, {
      caseId: hold.caseId,
      summary: `Hold placed on ${record.caseNumber} covering ${inScope.length} message(s)`,
      after: {
        reason: hold.reason,
        custodians: request.scope.custodianIds.length,
        after: request.scope.after,
        before: request.scope.before,
        searchTerms: request.scope.searchTerms,
        scopeCount: inScope.length,
      },
    });

    return this.toHold(hold);
  }

  releaseHold(id: string, releasedBy: string, note?: string): LegalHold {
    const hold = this.requireHold(id);
    if (hold.status === 'RELEASED') {
      throw ApiError.conflict('This hold has already been released');
    }

    for (const messageId of hold.stamped) {
      const message = this.corpus.byId.get(messageId);
      if (message && message.holdCount > 0) {
        message.holdCount--;
      }
    }
    hold.stamped.clear();

    hold.status = 'RELEASED';
    hold.releasedAt = new Date().toISOString();
    hold.releasedBy = releasedBy;

    const legalCase = this.cases.find((record) => record.id === hold.caseId);
    this.append('HOLD_RELEASED', 'HOLD', hold.id, hold.id, {
      caseId: hold.caseId,
      summary: `Hold ${hold.id} released${note ? ` (${note})` : ''}; protection retained where another hold overlaps`,
      before: { status: 'ACTIVE', heldMessages: hold.stamped.size },
      after: { status: 'RELEASED', releasedBy },
    });

    if (legalCase) {
      legalCase.updatedAt = hold.releasedAt;
    }

    return this.toHold(hold);
  }

  /**
   * Delete path used to prove FR-4.6. Anything covered by an active hold is
   * refused and the refusal is written to the audit log.
   */
  attemptDelete(messageId: string): DeletionAttemptResult {
    const message = this.getMessage(messageId);
    const blocking = this.holds
      .filter((hold) => hold.status !== 'RELEASED' && hold.stamped.has(messageId))
      .map((hold) => hold.id);

    const attemptedAt = new Date().toISOString();

    if (blocking.length > 0) {
      this.append('DELETION_BLOCKED', 'MESSAGE', messageId, message.subject, {
        summary: `Deletion of ${messageId} refused — covered by ${blocking.length} active hold(s)`,
        after: { blockingHoldIds: blocking },
      });

      return {
        messageId,
        deleted: false,
        reason: `Deletion refused: message is under legal hold (${blocking.join(', ')}). Held items cannot be deleted or modified by any part of the platform.`,
        blockingHoldIds: blocking,
        attemptedAt,
      };
    }

    if (message.dispositionStatus === 'DISPOSED') {
      return {
        messageId,
        deleted: false,
        reason: 'Message has already been disposed of by a retention run.',
        blockingHoldIds: [],
        attemptedAt,
      };
    }

    message.dispositionStatus = 'DISPOSED';
    this.disposedCount++;
    this.append('DISPOSITION_RUN', 'MESSAGE', messageId, message.subject, {
      summary: `Message ${messageId} deleted on request — no hold in force`,
      before: { dispositionStatus: 'ACTIVE' },
      after: { dispositionStatus: 'DISPOSED' },
    });

    return {
      messageId,
      deleted: true,
      reason: 'No active hold covers this message, so the delete was allowed.',
      blockingHoldIds: [],
      attemptedAt,
    };
  }

  // --------------------------------------------------------------- exports

  listExports(caseId?: string | null): ExportJob[] {
    return this.exports
      .filter((job) => !caseId || job.caseId === caseId)
      .map((job) => this.toExportJob(job))
      .sort((left, right) => Date.parse(right.requestedAt) - Date.parse(left.requestedAt));
  }

  getExport(id: string): ExportJob {
    return this.toExportJob(this.requireExport(id));
  }

  requestExport(request: CreateExportRequest): ExportJob {
    const record = this.requireCase(request.caseId);
    this.assertOpen(record, 'export from');

    const itemCount =
      request.scopeType === 'CASE'
        ? this.evidence.filter((item) => item.caseId === request.caseId).length
        : this.requireHold(request.holdId ?? '').stamped.size;

    if (itemCount === 0) {
      throw ApiError.badRequest(
        request.scopeType === 'CASE'
          ? 'This case has no evidence items to export'
          : 'That hold covers no messages yet',
      );
    }

    const now = new Date().toISOString();
    const job: ExportRecord = {
      id: `exp-${String(++this.exportCounter).padStart(4, '0')}`,
      caseId: request.caseId,
      caseName: record.name,
      scopeType: request.scopeType,
      holdId: request.holdId ?? null,
      status: 'QUEUED',
      requestedAt: now,
      requestedBy: CURRENT_ACTOR,
      startedAt: null,
      completedAt: null,
      progress: 0,
      itemCount,
      packageSizeBytes: 0,
      packageChecksum: null,
      downloadUrl: null,
      downloadExpiresAt: null,
      downloadCount: 0,
      attempt: 1,
      failureReason: null,
      manifest: null,
      tampered: false,
    };

    this.exports.push(job);
    this.append('EXPORT_REQUESTED', 'EXPORT', job.id, job.id, {
      caseId: job.caseId,
      summary: `Export ${job.id} requested for ${record.caseNumber} (${itemCount} item(s)); job queued`,
      after: { scopeType: job.scopeType, holdId: job.holdId, itemCount },
    });

    return this.toExportJob(job);
  }

  retryExport(id: string): ExportJob {
    const job = this.requireExport(id);
    if (job.status !== 'FAILED') {
      throw ApiError.conflict('Only a failed export job can be retried');
    }

    // Retry in place: the previous partial package is discarded rather than
    // left behind, so a retry can never produce a duplicate (FR-6.6).
    job.status = 'QUEUED';
    job.attempt++;
    job.progress = 0;
    job.failureReason = null;
    job.startedAt = null;
    job.completedAt = null;
    job.manifest = null;
    job.packageChecksum = null;
    job.packageSizeBytes = 0;
    job.tampered = false;

    this.append('EXPORT_REQUESTED', 'EXPORT', job.id, job.id, {
      caseId: job.caseId,
      summary: `Export ${job.id} retried (attempt ${job.attempt}); partial package discarded`,
      after: { attempt: job.attempt },
    });

    return this.toExportJob(job);
  }

  getManifest(id: string): ExportManifest {
    const job = this.requireExport(id);
    if (!job.manifest) {
      throw ApiError.conflict('The manifest is only available once the export has completed');
    }
    return job.manifest;
  }

  verifyExport(id: string): VerificationReport {
    const job = this.requireExport(id);
    if (!job.manifest) {
      throw ApiError.conflict('Nothing to verify until the export has completed');
    }

    const mismatches = job.tampered
      ? [
          {
            itemId: job.manifest.entries[0].itemId,
            expected: job.manifest.entries[0].sha256,
            actual: this.random.hex(64),
          },
        ]
      : [];

    const report: VerificationReport = {
      jobId: job.id,
      verifiedAt: new Date().toISOString(),
      passed: mismatches.length === 0,
      itemsChecked: job.manifest.entries.length,
      packageChecksumMatches: mismatches.length === 0,
      mismatches,
    };

    this.append('EXPORT_VERIFIED', 'EXPORT', job.id, job.id, {
      caseId: job.caseId,
      summary: report.passed
        ? `Export ${job.id} verified — ${report.itemsChecked} checksum(s) matched the manifest`
        : `Export ${job.id} FAILED verification — ${mismatches.length} checksum mismatch(es); package tampering detected`,
      after: { passed: report.passed, itemsChecked: report.itemsChecked },
    });

    return report;
  }

  /** Demo affordance: corrupts a package so verification has something to catch. */
  tamperWithPackage(id: string): void {
    const job = this.requireExport(id);
    if (!job.manifest) {
      throw ApiError.conflict('Only a completed package can be tampered with');
    }
    job.tampered = true;
  }

  registerDownload(id: string): ExportJob {
    const job = this.requireExport(id);
    if (job.status !== 'COMPLETED') {
      throw ApiError.conflict('The package is not ready for download yet');
    }

    if (!job.downloadExpiresAt || Date.parse(job.downloadExpiresAt) < Date.now()) {
      // Expired links are re-issued rather than resurrected, matching a
      // pre-signed URL being minted again.
      job.downloadUrl = this.buildDownloadUrl(job.id);
      job.downloadExpiresAt = new Date(Date.now() + 15 * MINUTES).toISOString();
    }

    job.downloadCount++;
    this.append('EXPORT_DOWNLOADED', 'EXPORT', job.id, job.id, {
      caseId: job.caseId,
      summary: `Export ${job.id} downloaded (download #${job.downloadCount})`,
      after: { downloadCount: job.downloadCount, expiresAt: job.downloadExpiresAt },
    });

    return this.toExportJob(job);
  }

  // ----------------------------------------------------------------- audit

  queryAudit(query: AuditQuery): AuditPage {
    const page = Math.max(0, query.page ?? 0);
    const size = Math.min(200, Math.max(1, query.size ?? 25));
    const needle = query.q?.toLowerCase().trim();

    const filtered = this.audit
      .filter((entry) => !query.caseId || entry.caseId === query.caseId)
      .filter((entry) => !query.actor || entry.actor === query.actor)
      .filter((entry) => !query.action || entry.action === query.action)
      .filter((entry) => !query.targetType || entry.targetType === query.targetType)
      .filter((entry) => !query.after || Date.parse(entry.timestamp) >= Date.parse(query.after))
      .filter((entry) => !query.before || Date.parse(entry.timestamp) <= Date.parse(query.before))
      .filter(
        (entry) =>
          !needle ||
          entry.summary.toLowerCase().includes(needle) ||
          entry.targetId.toLowerCase().includes(needle) ||
          entry.targetLabel.toLowerCase().includes(needle),
      )
      .sort((left, right) => right.sequence - left.sequence);

    return {
      total: filtered.length,
      page,
      size,
      entries: filtered.slice(page * size, page * size + size),
    };
  }

  getAuditEntry(id: string): AuditEntry {
    const entry = this.audit.find((candidate) => candidate.id === id);
    if (!entry) {
      throw ApiError.notFound(`No audit entry ${id}`);
    }
    return entry;
  }

  listActors(): string[] {
    return [...new Set(this.audit.map((entry) => entry.actor))].sort();
  }

  // -------------------------------------------------------------- platform

  counts(): DashboardCounts {
    const live = this.corpus.messages.filter(
      (message) => message.dispositionStatus !== 'DISPOSED',
    );

    return {
      totalMessages: live.length,
      indexedMessages: live.length,
      emailCount: live.filter((message) => message.communicationType === 'EMAIL').length,
      chatCount: live.filter((message) => message.communicationType === 'CHAT').length,
      withAttachments: live.filter((message) => message.attachments.length > 0).length,
      custodians: this.corpus.custodians.length,
      activeCases: this.cases.filter((record) => record.status !== 'CLOSED').length,
      totalCases: this.cases.length,
      activeHolds: this.holds.filter((hold) => hold.status !== 'RELEASED').length,
      heldMessages: live.filter((message) => message.holdCount > 0).length,
      exportsCompleted: this.exports.filter((job) => job.status === 'COMPLETED').length,
      exportsInFlight: this.exports.filter(
        (job) => job.status === 'QUEUED' || job.status === 'RUNNING',
      ).length,
      auditEntries: this.audit.length,
      disposedMessages: this.disposedCount,
    };
  }

  serviceStatus(): ServiceStatus[] {
    return this.serviceStatuses.map((status) => ({ ...status }));
  }

  /** Flips a service between UP and DOWN so the UI's degraded states are visible. */
  toggleService(id: string): ServiceStatus[] {
    const status = this.serviceStatuses.find((candidate) => candidate.id === id);
    if (status) {
      const down = status.health === 'DOWN';
      status.health = down ? 'UP' : 'DOWN';
      status.detail = down
        ? 'Healthy'
        : 'Simulated outage — dependent screens degrade, queued work resumes on recovery';
    }
    this.revision.update((value) => value + 1);
    return this.serviceStatus();
  }

  retentionPolicies(): RetentionPolicy[] {
    return this.retention.map((policy) => ({ ...policy }));
  }

  updateRetentionPolicy(
    communicationType: CommunicationType,
    retentionMinutes: number,
  ): RetentionPolicy {
    if (!Number.isFinite(retentionMinutes) || retentionMinutes < 1) {
      throw ApiError.badRequest('Retention must be at least one minute', 'retentionMinutes');
    }

    const policy = this.retention.find((candidate) => candidate.communicationType === communicationType);
    if (!policy) {
      throw ApiError.notFound(`No retention policy for ${communicationType}`);
    }

    const before = policy.retentionMinutes;
    policy.retentionMinutes = Math.floor(retentionMinutes);
    policy.updatedAt = new Date().toISOString();
    policy.updatedBy = CURRENT_ACTOR;
    this.applyRetentionStatus();

    this.append('RETENTION_POLICY_UPDATED', 'RETENTION_POLICY', communicationType, communicationType, {
      summary: `Retention for ${communicationType} changed from ${before} to ${policy.retentionMinutes} minute(s)`,
      before: { retentionMinutes: before },
      after: { retentionMinutes: policy.retentionMinutes },
    });

    return { ...policy };
  }

  listDispositionRuns(): DispositionRun[] {
    return [...this.dispositionRuns].sort(
      (left, right) => Date.parse(right.startedAt) - Date.parse(left.startedAt),
    );
  }

  /**
   * Deletes everything past retention except held messages, and records what it
   * did (FR-5.2, FR-5.3).
   */
  runDisposition(trigger: DispositionRun['trigger'] = 'MANUAL'): DispositionRun {
    this.applyRetentionStatus();

    const startedAt = new Date().toISOString();
    const candidates = this.corpus.messages.filter(
      (message) => message.dispositionStatus === 'PAST_RETENTION',
    );

    const deleted: string[] = [];
    const skipped: string[] = [];

    for (const message of candidates) {
      if (message.holdCount > 0) {
        skipped.push(message.id);
        continue;
      }
      message.dispositionStatus = 'DISPOSED';
      this.disposedCount++;
      deleted.push(message.id);
    }

    const run: DispositionRun = {
      id: `disp-${this.dispositionRuns.length + 1}`,
      startedAt,
      completedAt: new Date().toISOString(),
      trigger,
      scanned: this.corpus.messages.length,
      deleted: deleted.length,
      skippedOnHold: skipped.length,
      failed: 0,
      deletedSample: deleted.slice(0, 10),
      skippedSample: skipped.slice(0, 10),
    };

    this.dispositionRuns.push(run);
    this.append('DISPOSITION_RUN', 'SYSTEM', run.id, 'Disposition job', {
      summary: `Disposition run ${run.id}: ${run.deleted} deleted, ${run.skippedOnHold} skipped under hold, ${run.scanned} scanned`,
      after: {
        deleted: run.deleted,
        skippedOnHold: run.skippedOnHold,
        scanned: run.scanned,
        trigger,
      },
    });

    return run;
  }

  // ------------------------------------------------------ background ticks

  /**
   * Advances export jobs, the only asynchronous flow the services actually
   * have. Holds are applied and released synchronously, matching
   * case-hold-service.
   */
  private tick(): void {
    let changed = false;

    for (const job of this.exports) {
      if (job.status === 'QUEUED') {
        job.status = 'RUNNING';
        job.startedAt = new Date().toISOString();
        job.progress = 5;
        changed = true;
      } else if (job.status === 'RUNNING') {
        job.progress = Math.min(100, job.progress + this.random.int(18, 38));
        if (job.progress >= 100) {
          this.completeExport(job);
        }
        changed = true;
      }
    }

    if (changed) {
      this.revision.update((value) => value + 1);
    }
  }

  private completeExport(job: ExportRecord): void {
    // First attempt of every third job fails, so the retry path is reachable in
    // a demo without hand-editing state.
    const shouldFail = job.attempt === 1 && this.exportCounter % 3 === 0;

    if (shouldFail) {
      job.status = 'FAILED';
      job.progress = 100;
      job.completedAt = new Date().toISOString();
      job.failureReason =
        'Object store write failed while sealing the package (simulated). No partial package was published.';
      this.append('EXPORT_COMPLETED', 'EXPORT', job.id, job.id, {
        caseId: job.caseId,
        summary: `Export ${job.id} failed: ${job.failureReason}`,
        after: { status: 'FAILED', attempt: job.attempt },
      });
      return;
    }

    const messageIds = this.exportScopeMessageIds(job);
    const manifest = this.buildManifest(job, messageIds);

    job.status = 'COMPLETED';
    job.progress = 100;
    job.completedAt = new Date().toISOString();
    job.manifest = manifest;
    job.itemCount = manifest.itemCount;
    job.packageSizeBytes = manifest.totalSizeBytes;
    job.packageChecksum = manifest.packageChecksum;
    job.downloadUrl = this.buildDownloadUrl(job.id);
    job.downloadExpiresAt = new Date(Date.now() + 15 * MINUTES).toISOString();

    this.append('EXPORT_COMPLETED', 'EXPORT', job.id, job.id, {
      caseId: job.caseId,
      summary: `Export ${job.id} completed — ${manifest.itemCount} item(s), package checksum ${manifest.packageChecksum.slice(0, 12)}…`,
      after: {
        itemCount: manifest.itemCount,
        packageChecksum: manifest.packageChecksum,
        sizeBytes: manifest.totalSizeBytes,
      },
    });
  }

  private exportScopeMessageIds(job: ExportRecord): string[] {
    if (job.scopeType === 'CASE') {
      return this.evidence
        .filter((item) => item.caseId === job.caseId)
        .map((item) => item.messageId);
    }
    const hold = this.holds.find((candidate) => candidate.id === job.holdId);
    return hold ? [...hold.stamped] : [];
  }

  private buildManifest(job: ExportRecord, messageIds: string[]): ExportManifest {
    const entries: ManifestEntry[] = [];
    let totalSize = 0;

    for (const messageId of messageIds) {
      const message = this.corpus.byId.get(messageId);
      if (!message) {
        continue;
      }

      const messageSize = 1_200 + message.body.length * 2;
      totalSize += messageSize;
      entries.push({
        itemId: message.id,
        path: `messages/${message.id}.eml`,
        itemType: 'MESSAGE',
        sizeBytes: messageSize,
        sha256: this.digestFor(`${message.id}:${message.subject}:${message.body.length}`),
      });

      for (const attachment of message.attachments) {
        totalSize += attachment.sizeBytes;
        entries.push({
          itemId: attachment.attachmentId,
          path: `attachments/${message.id}/${attachment.filename}`,
          itemType: 'ATTACHMENT',
          sizeBytes: attachment.sizeBytes,
          sha256: attachment.sha256,
        });
      }
    }

    return {
      jobId: job.id,
      caseId: job.caseId,
      generatedAt: new Date().toISOString(),
      itemCount: entries.length,
      totalSizeBytes: totalSize,
      packageChecksum: this.digestFor(entries.map((entry) => entry.sha256).join('')),
      algorithm: 'SHA-256',
      entries,
    };
  }

  /**
   * Stable pseudo-digest. A real package is hashed with SHA-256 over the bytes;
   * here the point is only that the same content always yields the same value,
   * so re-verification is meaningful.
   */
  private digestFor(input: string): string {
    let h1 = 0x9e3779b9;
    let h2 = 0x85ebca6b;

    for (let i = 0; i < input.length; i++) {
      const code = input.charCodeAt(i);
      h1 = Math.imul(h1 ^ code, 0xcc9e2d51) >>> 0;
      h2 = Math.imul(h2 ^ code, 0x1b873593) >>> 0;
    }

    let out = '';
    let state = (h1 ^ h2) >>> 0;
    while (out.length < 64) {
      state = Math.imul(state ^ (state >>> 15), 0x2545f491) >>> 0;
      out += state.toString(16).padStart(8, '0');
    }
    return out.slice(0, 64);
  }

  private buildDownloadUrl(jobId: string): string {
    return `https://discoveryhub-exports.s3.local/${jobId}.zip?X-Amz-Expires=900&X-Amz-Signature=${this.random.hex(32)}`;
  }

  // --------------------------------------------------------------- helpers

  private resolveScope(scope: HoldScope): string[] {
    const emails = new Set(
      this.corpus.custodians
        .filter((custodian) => scope.custodianIds.includes(custodian.id))
        .map((custodian) => custodian.email),
    );

    const terms = scope.searchTerms ? parseQuery(scope.searchTerms) : [];
    const after = scope.after ? Date.parse(scope.after) : null;
    const before = scope.before ? Date.parse(scope.before) : null;

    const matched: string[] = [];

    for (let index = 0; index < this.corpus.messages.length; index++) {
      const message = this.corpus.messages[index];
      if (message.dispositionStatus === 'DISPOSED') {
        continue;
      }
      const touchesCustodian =
        emails.has(message.sender) ||
        message.recipients.some((recipient) => emails.has(recipient));
      if (!touchesCustodian) {
        continue;
      }

      const timestamp = Date.parse(message.messageTimestamp);
      if (after !== null && timestamp < after) {
        continue;
      }
      if (before !== null && timestamp > before) {
        continue;
      }
      if (terms.length > 0) {
        const haystack = this.corpus.searchText[index];
        if (!terms.some((term) => haystack.includes(term))) {
          continue;
        }
      }

      matched.push(message.id);
    }

    return matched;
  }

  private applyRetentionStatus(): void {
    const now = Date.now();
    const byType = new Map(
      this.retention.map((policy) => [policy.communicationType, policy.retentionMinutes]),
    );

    for (const message of this.corpus.messages) {
      if (message.dispositionStatus === 'DISPOSED') {
        continue;
      }
      const minutes = byType.get(message.communicationType) ?? Number.MAX_SAFE_INTEGER;
      const expiresAt = Date.parse(message.messageTimestamp) + minutes * MINUTES;
      message.dispositionStatus = expiresAt <= now ? 'PAST_RETENTION' : 'ACTIVE';
    }
  }

  private toCase(record: CaseRecord): LegalCase {
    const caseHolds = this.holds.filter((hold) => hold.caseId === record.id);
    const held = new Set<string>();
    for (const hold of caseHolds.filter((hold) => hold.status !== 'RELEASED')) {
      for (const messageId of hold.stamped) {
        held.add(messageId);
      }
    }

    return {
      ...record,
      custodianCount: this.caseCustodians.filter((link) => link.caseId === record.id).length,
      evidenceCount: this.evidence.filter((item) => item.caseId === record.id).length,
      activeHoldCount: caseHolds.filter((hold) => hold.status !== 'RELEASED').length,
      heldMessageCount: held.size,
    };
  }

  private toHold(record: HoldRecord): LegalHold {
    const legalCase = this.cases.find((candidate) => candidate.id === record.caseId);
    return {
      id: record.id,
      caseId: record.caseId,
      caseName: legalCase ? `${legalCase.caseNumber} — ${legalCase.name}` : record.caseId,
      reason: record.reason,
      status: record.status,
      scope: record.scope,
      placedAt: record.placedAt,
      placedBy: record.placedBy,
      releasedAt: record.releasedAt,
      releasedBy: record.releasedBy,
      matchedMessageCount: record.stamped.size,

    };
  }

  private toExportJob(record: ExportRecord): ExportJob {
    const { manifest, tampered, ...job } = record;
    void manifest;
    void tampered;
    return { ...job };
  }

  private requireCase(id: string): CaseRecord {
    const record = this.cases.find((candidate) => candidate.id === id);
    if (!record) {
      throw ApiError.notFound(`No case ${id}`);
    }
    return record;
  }

  private requireHold(id: string): HoldRecord {
    const record = this.holds.find((candidate) => candidate.id === id);
    if (!record) {
      throw ApiError.notFound(`No hold ${id}`);
    }
    return record;
  }

  private requireExport(id: string): ExportRecord {
    const record = this.exports.find((candidate) => candidate.id === id);
    if (!record) {
      throw ApiError.notFound(`No export job ${id}`);
    }
    return record;
  }

  /** A closed case is read-only (FR-2.5). */
  private assertOpen(record: CaseRecord, action: string): void {
    if (record.status === 'CLOSED') {
      throw ApiError.conflict(
        `Case ${record.caseNumber} is closed and read-only — you cannot ${action} it`,
      );
    }
  }

  private nextCaseNumber(): string {
    const year = new Date().getFullYear();
    return `CASE-${year}-${String(this.caseCounter).padStart(4, '0')}`;
  }

  private append(
    action: AuditAction,
    targetType: AuditTargetType,
    targetId: string,
    targetLabel: string,
    options: {
      caseId?: string | null;
      summary: string;
      actor?: string;
      before?: Record<string, unknown> | null;
      after?: Record<string, unknown> | null;
      timestamp?: string;
    },
  ): AuditEntry {
    const entry: AuditEntry = {
      id: `aud-${String(this.sequence).padStart(6, '0')}`,
      sequence: this.sequence++,
      timestamp: options.timestamp ?? new Date().toISOString(),
      actor: options.actor ?? CURRENT_ACTOR,
      action,
      targetType,
      targetId,
      targetLabel,
      caseId: options.caseId ?? null,
      summary: options.summary,
      before: options.before ?? null,
      after: options.after ?? null,
    };

    this.audit.push(entry);
    return entry;
  }

  // ------------------------------------------------------------------ seed

  private seedRetention(): void {
    const now = new Date(Date.now() - 30 * 24 * 60 * MINUTES).toISOString();
    this.retention.push(
      {
        communicationType: 'EMAIL',
        retentionMinutes: 7 * 365 * 24 * 60,
        updatedAt: now,
        updatedBy: 'system@discoveryhub',
      },
      {
        communicationType: 'CHAT',
        retentionMinutes: 3 * 365 * 24 * 60,
        updatedAt: now,
        updatedBy: 'system@discoveryhub',
      },
    );
  }

  private seedServiceStatus(): void {
    this.serviceStatuses.push(
      { id: 'ingestion', name: 'Ingestion & Archival', port: 8081, health: 'UP', detail: 'Healthy' },
      { id: 'search', name: 'Search', port: 8082, health: 'UP', detail: 'Healthy' },
      { id: 'case-hold', name: 'Case & Hold', port: 8083, health: 'UP', detail: 'Healthy' },
      { id: 'export-audit', name: 'Export & Audit', port: 8084, health: 'UP', detail: 'Healthy' },
    );
  }

  /** Builds a plausible history so the audit trail is not empty on first load. */
  private seedCases(): void {
    const dayMillis = 24 * 60 * MINUTES;

    CASE_SEEDS.forEach((seed, index) => {
      const createdAt = new Date(Date.now() - (18 - index * 3) * dayMillis).toISOString();
      const record: CaseRecord = {
        id: `case-${String(++this.caseCounter).padStart(4, '0')}`,
        caseNumber: `CASE-${new Date(createdAt).getFullYear()}-${String(this.caseCounter).padStart(4, '0')}`,
        name: seed.name,
        description: seed.description,
        matterType: seed.matterType,
        owner: ACTORS[index % 3],
        status: 'OPEN',
        createdAt,
        updatedAt: createdAt,
        closedAt: null,
      };
      this.cases.push(record);

      this.append('CASE_CREATED', 'CASE', record.id, record.caseNumber, {
        caseId: record.id,
        actor: record.owner,
        timestamp: createdAt,
        summary: `Case ${record.caseNumber} "${record.name}" created`,
        after: { name: record.name, matterType: record.matterType, owner: record.owner },
      });

      // Attach custodians weighted to the front of the directory so the seeded
      // holds resolve to a decent number of messages.
      const custodians = this.random.sample(this.corpus.custodians.slice(0, 12), this.random.int(2, 5));
      custodians.forEach((custodian, position) => {
        const addedAt = new Date(Date.parse(createdAt) + (position + 1) * 90 * MINUTES).toISOString();
        this.caseCustodians.push({
          caseId: record.id,
          custodianId: custodian.id,
          displayName: custodian.displayName,
          email: custodian.email,
          department: custodian.department,
          addedAt,
          addedBy: record.owner,
        });
        this.append('CUSTODIAN_ADDED', 'CUSTODIAN', custodian.id, custodian.displayName, {
          caseId: record.id,
          actor: record.owner,
          timestamp: addedAt,
          summary: `${custodian.displayName} attached to ${record.caseNumber} as custodian`,
          after: { custodianId: custodian.id, email: custodian.email },
        });
      });

      // Walk the lifecycle rather than assigning the status, so the transitions
      // appear in the audit trail like any other change. Cases are created
      // OPEN, so only a non-OPEN seed needs a transition recorded.
      const path: CaseStatus[] = seed.status === 'OPEN' ? [] : [seed.status];

      for (const [position, status] of path.entries()) {
        const at = new Date(Date.parse(createdAt) + (position + 1) * 6 * 60 * MINUTES).toISOString();
        const before = record.status;
        record.status = status;
        record.updatedAt = at;
        this.append('CASE_STATUS_CHANGED', 'CASE', record.id, record.caseNumber, {
          caseId: record.id,
          actor: record.owner,
          timestamp: at,
          summary: `Case ${record.caseNumber} moved ${before} → ${status}`,
          before: { status: before },
          after: { status },
        });
      }

      if (record.status === 'OPEN') {
        this.seedEvidence(record, this.random.int(6, 18));
      }

      // The last seed is the closed case; close it after its evidence exists so
      // the read-only rules have something to protect.
      if (seed.status === 'CLOSED') {
        const at = new Date(Date.parse(createdAt) + 9 * 24 * 60 * MINUTES).toISOString();
        const before = record.status;
        record.status = 'CLOSED';
        record.closedAt = at;
        record.updatedAt = at;
        this.append('CASE_STATUS_CHANGED', 'CASE', record.id, record.caseNumber, {
          caseId: record.id,
          actor: record.owner,
          timestamp: at,
          summary: `Case ${record.caseNumber} moved ${before} → CLOSED; case is now read-only`,
          before: { status: before },
          after: { status: 'CLOSED' },
        });
      }
    });
  }

  private seedEvidence(record: CaseRecord, count: number): void {
    const custodianEmails = new Set(
      this.caseCustodians
        .filter((link) => link.caseId === record.id)
        .map((link) => link.email),
    );

    const candidates = this.corpus.messages.filter(
      (message) =>
        custodianEmails.has(message.sender) ||
        message.recipients.some((recipient) => custodianEmails.has(recipient)),
    );

    const picked = this.random.sample(candidates.slice(0, 400), count);

    picked.forEach((message, index) => {
      const addedAt = new Date(Date.parse(record.updatedAt) + (index + 1) * 12 * MINUTES).toISOString();
      this.evidence.push({
        id: `ev-${this.evidence.length + 1}`,
        caseId: record.id,
        messageId: message.id,
        subject: message.subject,
        sender: message.sender,
        recipients: message.recipients,
        communicationType: message.communicationType,
        messageTimestamp: message.messageTimestamp,
        attachmentCount: message.attachments.length,
        onHold: false,
        addedAt,
        addedBy: record.owner,
        source: index % 3 === 0 ? 'SEARCH_RESULT_SET' : 'MANUAL',
      });
    });

    this.append('EVIDENCE_ADDED', 'CASE', record.id, record.caseNumber, {
      caseId: record.id,
      actor: record.owner,
      timestamp: new Date(Date.parse(record.updatedAt) + 15 * MINUTES).toISOString(),
      summary: `${picked.length} message(s) added to ${record.caseNumber} as evidence`,
      after: { count: picked.length },
    });
  }

  private seedHolds(): void {
    const openCases = this.cases.filter((record) => record.status === 'OPEN');

    for (const record of openCases.slice(0, 3)) {
      const custodianIds = this.caseCustodians
        .filter((link) => link.caseId === record.id)
        .map((link) => link.custodianId);

      const scope: HoldScope = {
        custodianIds,
        after: new Date(Date.now() - 400 * 24 * 60 * MINUTES).toISOString(),
        before: null,
        searchTerms: null,
      };

      const inScope = this.resolveScope(scope);
      const placedAt = new Date(Date.parse(record.updatedAt) + 3 * 60 * MINUTES).toISOString();
      const hold: HoldRecord = {
        id: `hold-${String(++this.holdCounter).padStart(4, '0')}`,
        caseId: record.id,
        reason: `Preservation obligation for ${record.name}`,
        status: 'ACTIVE',
        scope,
        placedAt,
        placedBy: record.owner,
        releasedAt: null,
        releasedBy: null,

        stamped: new Set(inScope),
        pending: [],
      };

      // Seeded holds are already propagated, so stamp the messages directly.
      for (const messageId of inScope) {
        const message = this.corpus.byId.get(messageId);
        if (message) {
          message.holdCount++;
        }
      }

      this.holds.push(hold);
      this.append('HOLD_PLACED', 'HOLD', hold.id, hold.id, {
        caseId: hold.caseId,
        actor: record.owner,
        timestamp: placedAt,
        summary: `Hold placed on ${record.caseNumber} covering ${inScope.length} message(s)`,
        after: { reason: hold.reason, scopeCount: inScope.length, custodians: custodianIds.length },
      });
    }
  }

  private seedExports(): void {
    const withEvidence = this.cases.filter((record) =>
      this.evidence.some((item) => item.caseId === record.id),
    );

    for (const [index, record] of withEvidence.slice(0, 2).entries()) {
      const requestedAt = new Date(Date.parse(record.updatedAt) + 5 * 60 * MINUTES).toISOString();
      const job: ExportRecord = {
        id: `exp-${String(++this.exportCounter).padStart(4, '0')}`,
        caseId: record.id,
        caseName: record.name,
        scopeType: 'CASE',
        holdId: null,
        status: 'COMPLETED',
        requestedAt,
        requestedBy: record.owner,
        startedAt: requestedAt,
        completedAt: new Date(Date.parse(requestedAt) + 4 * MINUTES).toISOString(),
        progress: 100,
        itemCount: 0,
        packageSizeBytes: 0,
        packageChecksum: null,
        downloadUrl: null,
        // Deliberately already expired on the first one, so the UI's expired
        // link handling is visible without waiting.
        downloadExpiresAt: null,
        downloadCount: index,
        attempt: 1,
        failureReason: null,
        manifest: null,
        tampered: false,
      };

      const manifest = this.buildManifest(
        job,
        this.evidence.filter((item) => item.caseId === record.id).map((item) => item.messageId),
      );

      job.manifest = manifest;
      job.itemCount = manifest.itemCount;
      job.packageSizeBytes = manifest.totalSizeBytes;
      job.packageChecksum = manifest.packageChecksum;

      if (index === 0) {
        job.downloadUrl = this.buildDownloadUrl(job.id);
        job.downloadExpiresAt = new Date(Date.parse(requestedAt) + 20 * MINUTES).toISOString();
      } else {
        job.downloadUrl = this.buildDownloadUrl(job.id);
        job.downloadExpiresAt = new Date(Date.now() + 12 * MINUTES).toISOString();
      }

      this.exports.push(job);
      this.append('EXPORT_REQUESTED', 'EXPORT', job.id, job.id, {
        caseId: job.caseId,
        actor: record.owner,
        timestamp: requestedAt,
        summary: `Export ${job.id} requested for ${record.caseNumber} (${manifest.itemCount} item(s))`,
        after: { scopeType: job.scopeType, itemCount: manifest.itemCount },
      });
      this.append('EXPORT_COMPLETED', 'EXPORT', job.id, job.id, {
        caseId: job.caseId,
        actor: 'system@discoveryhub',
        timestamp: job.completedAt ?? requestedAt,
        summary: `Export ${job.id} completed — package checksum ${manifest.packageChecksum.slice(0, 12)}…`,
        after: { itemCount: manifest.itemCount, packageChecksum: manifest.packageChecksum },
      });
    }
  }
}
