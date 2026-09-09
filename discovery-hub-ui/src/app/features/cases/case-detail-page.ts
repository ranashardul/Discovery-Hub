import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, firstValueFrom, interval, of } from 'rxjs';
import { environment } from '../../../environments/environment';
import { describeError } from '../../core/api/api-error';
import { AuditApi } from '../../core/api/audit-api';
import { CaseApi } from '../../core/api/case-api';
import { ExportApi } from '../../core/api/export-api';
import { HoldApi } from '../../core/api/hold-api';
import { SearchApi } from '../../core/api/search-api';
import { AuditEntry } from '../../core/models/audit';
import {
  ALLOWED_CASE_TRANSITIONS,
  CaseCustodian,
  CaseStatus,
  EvidenceItem,
  LegalCase,
} from '../../core/models/case';
import { ExportJob } from '../../core/models/export';
import { DeletionAttemptResult, LegalHold } from '../../core/models/hold';
import { Custodian } from '../../core/models/message';
import { SavedSearch } from '../../core/models/search';
import { CURRENT_ACTOR } from '../../core/mock/mock-store';
import { ToastService } from '../../shared/notifications/toast.service';
import { AgoPipe, BytesPipe, LabelPipe } from '../../shared/pipes/format.pipes';
import { Modal } from '../../shared/ui/modal';
import { Empty, ErrorBand, Loading } from '../../shared/ui/state-blocks';
import { StatusChip } from '../../shared/ui/status-chip';

type Tab = 'evidence' | 'custodians' | 'holds' | 'searches' | 'exports' | 'audit';

/**
 * Everything about one matter (FR-2, FR-4, FR-6, FR-7 in case scope). The case
 * status governs the whole screen: once CLOSED, every mutating control is
 * disabled and the API would refuse the call anyway.
 */
@Component({
  selector: 'app-case-detail-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    DatePipe,
    DecimalPipe,
    AgoPipe,
    BytesPipe,
    LabelPipe,
    StatusChip,
    Modal,
    Loading,
    Empty,
    ErrorBand,
  ],
  templateUrl: './case-detail-page.html',
})
export class CaseDetailPage {
  /** Bound from the `:id` route parameter. */
  readonly id = input.required<string>();

  private readonly caseApi = inject(CaseApi);
  private readonly holdApi = inject(HoldApi);
  private readonly exportApi = inject(ExportApi);
  private readonly auditApi = inject(AuditApi);
  private readonly searchApi = inject(SearchApi);
  private readonly toast = inject(ToastService);
  private readonly fb = inject(FormBuilder);

  protected readonly legalCase = signal<LegalCase | null>(null);
  protected readonly custodians = signal<CaseCustodian[]>([]);
  protected readonly evidence = signal<EvidenceItem[]>([]);
  protected readonly holds = signal<LegalHold[]>([]);
  protected readonly exports = signal<ExportJob[]>([]);
  protected readonly savedSearches = signal<SavedSearch[]>([]);
  protected readonly auditEntries = signal<AuditEntry[]>([]);
  protected readonly directory = signal<Custodian[]>([]);

  protected readonly tab = signal<Tab>('evidence');
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly custodianModalOpen = signal(false);
  protected readonly holdModalOpen = signal(false);
  protected readonly deletionResult = signal<DeletionAttemptResult | null>(null);
  protected readonly scopePreview = signal<number | null>(null);

  protected readonly custodianForm = this.fb.nonNullable.group({ custodianId: '' });

  protected readonly holdForm = this.fb.nonNullable.group({
    reason: ['', Validators.required],
    custodianIds: [[] as string[]],
    after: '',
    before: '',
    searchTerms: '',
  });

  protected readonly readOnly = computed(() => this.legalCase()?.status === 'CLOSED');

  protected readonly allowedTransitions = computed<readonly CaseStatus[]>(() => {
    const status = this.legalCase()?.status;
    return status ? ALLOWED_CASE_TRANSITIONS[status] : [];
  });

  protected readonly availableCustodians = computed(() => {
    const attached = new Set(this.custodians().map((link) => link.custodianId));
    return this.directory().filter((custodian) => !attached.has(custodian.id));
  });

  protected readonly activeHolds = computed(() =>
    this.holds().filter((hold) => hold.status !== 'RELEASED'),
  );

  protected readonly heldMessages = computed(() =>
    this.activeHolds().reduce((total, hold) => total + hold.matchedMessageCount, 0),
  );

  constructor() {
    // Asynchronous work (hold propagation, export jobs) is polled, exactly as
    // it would be against the real services.
    interval(environment.jobPollIntervalMs)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        if (!this.legalCase()) {
          return;
        }
        const inFlight =
          this.holds().some((hold) => hold.status === 'PROPAGATING' || hold.status === 'RELEASING') ||
          this.exports().some((job) => job.status === 'QUEUED' || job.status === 'RUNNING');

        if (inFlight) {
          this.refreshHolds();
          this.refreshExports();
          this.refreshCase();
        }
      });
  }

  ngOnInit(): void {
    this.loadAll();
  }

  protected loadAll(): void {
    this.loading.set(true);
    this.caseApi.getCase(this.id()).subscribe({
      next: (legalCase) => {
        this.legalCase.set(legalCase);
        this.error.set(null);
        this.loading.set(false);
        this.refreshCustodians();
        this.refreshEvidence();
        this.refreshHolds();
        this.refreshExports();
        this.refreshSavedSearches();
        this.refreshAudit();
        this.caseApi.listAllCustodians().subscribe({
          next: (directory) => this.directory.set(directory),
          error: () => this.directory.set([]),
        });
      },
      error: (error: unknown) => {
        this.error.set(describeError(error));
        this.loading.set(false);
      },
    });
  }

  protected async changeStatus(status: CaseStatus): Promise<void> {
    this.busy.set(true);
    try {
      const updated = await firstValueFrom(this.caseApi.changeStatus(this.id(), status));
      this.legalCase.set(updated);
      this.toast.success(`Case moved to ${status.replace('_', ' ').toLowerCase()}`);
      this.refreshHolds();
      this.refreshAudit();
    } catch (error) {
      this.toast.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async addCustodian(): Promise<void> {
    const custodianId = this.custodianForm.getRawValue().custodianId;
    if (!custodianId) {
      this.toast.error('Choose a custodian to attach');
      return;
    }

    this.busy.set(true);
    try {
      await firstValueFrom(this.caseApi.addCustodian(this.id(), custodianId));
      this.toast.success('Custodian attached to the case');
      this.custodianModalOpen.set(false);
      this.custodianForm.reset({ custodianId: '' });
      this.refreshCustodians();
      this.refreshCase();
      this.refreshAudit();
    } catch (error) {
      this.toast.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async removeCustodian(custodianId: string): Promise<void> {
    try {
      await firstValueFrom(this.caseApi.removeCustodian(this.id(), custodianId));
      this.toast.info('Custodian removed. Existing holds are unaffected.');
      this.refreshCustodians();
      this.refreshCase();
      this.refreshAudit();
    } catch (error) {
      this.toast.error(error);
    }
  }

  protected async removeEvidence(evidenceId: string): Promise<void> {
    try {
      await firstValueFrom(this.caseApi.removeEvidence(this.id(), evidenceId));
      this.toast.info('Evidence item removed; the removal is in the audit trail.');
      this.refreshEvidence();
      this.refreshCase();
      this.refreshAudit();
    } catch (error) {
      this.toast.error(error);
    }
  }

  protected openHoldModal(): void {
    this.holdForm.reset({
      reason: `Preservation obligation for ${this.legalCase()?.name ?? 'this matter'}`,
      custodianIds: this.custodians().map((link) => link.custodianId),
      after: '',
      before: '',
      searchTerms: '',
    });
    this.scopePreview.set(null);
    this.holdModalOpen.set(true);
  }

  protected toggleHoldCustodian(custodianId: string, checked: boolean): void {
    const current = new Set(this.holdForm.getRawValue().custodianIds);
    if (checked) {
      current.add(custodianId);
    } else {
      current.delete(custodianId);
    }
    this.holdForm.patchValue({ custodianIds: [...current] });
    this.scopePreview.set(null);
  }

  protected holdIncludes(custodianId: string): boolean {
    return this.holdForm.getRawValue().custodianIds.includes(custodianId);
  }

  protected async previewScope(): Promise<void> {
    this.busy.set(true);
    try {
      const count = await firstValueFrom(this.holdApi.previewScope(this.holdScope()));
      this.scopePreview.set(count);
    } catch (error) {
      this.toast.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async placeHold(): Promise<void> {
    if (this.holdForm.invalid) {
      this.holdForm.markAllAsTouched();
      return;
    }

    this.busy.set(true);
    try {
      const hold = await firstValueFrom(
        this.holdApi.placeHold({
          caseId: this.id(),
          reason: this.holdForm.getRawValue().reason,
          scope: this.holdScope(),
        }),
      );
      this.toast.success(
        `Hold ${hold.id} accepted — propagating across ${hold.estimatedScopeCount} message(s) in the background`,
      );
      this.holdModalOpen.set(false);
      this.tab.set('holds');
      this.refreshHolds();
      this.refreshAudit();
    } catch (error) {
      this.toast.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async releaseHold(holdId: string): Promise<void> {
    try {
      await firstValueFrom(this.holdApi.releaseHold(holdId, CURRENT_ACTOR));
      this.toast.info('Hold released. Messages covered by another active hold stay protected.');
      this.refreshHolds();
      this.refreshCase();
      this.refreshAudit();
    } catch (error) {
      this.toast.error(error);
    }
  }

  /** FR-4.6 proof: ask the platform to delete an evidence message. */
  protected async testDeletion(messageId: string): Promise<void> {
    try {
      const result = await firstValueFrom(this.holdApi.attemptDelete(messageId));
      this.deletionResult.set(result);
      this.refreshEvidence();
      this.refreshAudit();
    } catch (error) {
      this.toast.error(error);
    }
  }

  protected async requestExport(holdId: string | null = null): Promise<void> {
    this.busy.set(true);
    try {
      const job = await firstValueFrom(
        this.exportApi.requestExport({
          caseId: this.id(),
          scopeType: holdId ? 'HOLD_SCOPE' : 'CASE_EVIDENCE',
          holdId,
        }),
      );
      this.toast.success(`Export job ${job.id} queued — track its progress here or on Exports`);
      this.tab.set('exports');
      this.refreshExports();
      this.refreshAudit();
    } catch (error) {
      this.toast.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async rerunSavedSearch(saved: SavedSearch): Promise<void> {
    try {
      const response = await firstValueFrom(this.searchApi.search({ ...saved.criteria, size: 1 }));
      this.toast.info(`"${saved.name}" now matches ${response.total} message(s)`);
    } catch (error) {
      this.toast.error(error);
    }
  }

  protected async deleteSavedSearch(id: string): Promise<void> {
    try {
      await firstValueFrom(this.searchApi.deleteSavedSearch(id));
      this.refreshSavedSearches();
    } catch (error) {
      this.toast.error(error);
    }
  }

  protected searchLinkParams(saved: SavedSearch): Record<string, string> {
    const params: Record<string, string> = { q: saved.criteria.q };
    for (const [key, value] of Object.entries(saved.criteria)) {
      if (key !== 'q' && value !== null && value !== undefined && value !== '') {
        params[key] = String(value);
      }
    }
    return params;
  }

  private holdScope() {
    const value = this.holdForm.getRawValue();
    return {
      custodianIds: value.custodianIds,
      after: value.after ? new Date(`${value.after}T00:00:00Z`).toISOString() : null,
      before: value.before ? new Date(`${value.before}T23:59:59Z`).toISOString() : null,
      searchTerms: value.searchTerms.trim() || null,
    };
  }

  private refreshCase(): void {
    this.caseApi
      .getCase(this.id())
      .pipe(catchError(() => of(null)))
      .subscribe((legalCase) => legalCase && this.legalCase.set(legalCase));
  }

  private refreshCustodians(): void {
    this.caseApi
      .listCustodians(this.id())
      .pipe(catchError(() => of([])))
      .subscribe((custodians) => this.custodians.set(custodians));
  }

  private refreshEvidence(): void {
    this.caseApi
      .listEvidence(this.id())
      .pipe(catchError(() => of([])))
      .subscribe((evidence) => this.evidence.set(evidence));
  }

  private refreshHolds(): void {
    this.holdApi
      .listHolds(this.id())
      .pipe(catchError(() => of([])))
      .subscribe((holds) => this.holds.set(holds));
  }

  private refreshExports(): void {
    this.exportApi
      .listJobs(this.id())
      .pipe(catchError(() => of([])))
      .subscribe((jobs) => this.exports.set(jobs));
  }

  private refreshSavedSearches(): void {
    this.searchApi
      .listSavedSearches(this.id())
      .pipe(catchError(() => of([])))
      .subscribe((searches) => this.savedSearches.set(searches));
  }

  private refreshAudit(): void {
    this.auditApi
      .query({ caseId: this.id(), size: 50 })
      .pipe(catchError(() => of({ entries: [] as AuditEntry[], total: 0, page: 0, size: 0 })))
      .subscribe((page) => this.auditEntries.set(page.entries));
  }
}
