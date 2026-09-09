import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe, JsonPipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { describeError } from '../../core/api/api-error';
import { AuditApi } from '../../core/api/audit-api';
import { CaseApi } from '../../core/api/case-api';
import { AuditAction, AuditEntry, AuditPage as AuditPageModel } from '../../core/models/audit';
import { LegalCase } from '../../core/models/case';
import { AgoPipe, LabelPipe } from '../../shared/pipes/format.pipes';
import { Empty, ErrorBand, Loading } from '../../shared/ui/state-blocks';
import { StatusChip } from '../../shared/ui/status-chip';

const ACTIONS: AuditAction[] = [
  'CASE_CREATED',
  'CASE_UPDATED',
  'CASE_STATUS_CHANGED',
  'CUSTODIAN_ADDED',
  'CUSTODIAN_REMOVED',
  'EVIDENCE_ADDED',
  'EVIDENCE_REMOVED',
  'HOLD_PLACED',
  'HOLD_RELEASED',
  'SEARCH_EXECUTED',
  'SEARCH_SAVED',
  'EXPORT_REQUESTED',
  'EXPORT_COMPLETED',
  'EXPORT_DOWNLOADED',
  'EXPORT_VERIFIED',
  'DELETION_BLOCKED',
  'DISPOSITION_RUN',
  'RETENTION_POLICY_UPDATED',
];

/**
 * Audit trail viewer (FR-7.4). Read-only by construction — there is no API to
 * change an entry, so this screen offers none.
 */
@Component({
  selector: 'app-audit-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    DatePipe,
    DecimalPipe,
    JsonPipe,
    AgoPipe,
    LabelPipe,
    StatusChip,
    Loading,
    Empty,
    ErrorBand,
  ],
  templateUrl: './audit-page.html',
})
export class AuditPage {
  private readonly auditApi = inject(AuditApi);
  private readonly caseApi = inject(CaseApi);
  private readonly fb = inject(FormBuilder);

  protected readonly actions = ACTIONS;
  protected readonly page = signal<AuditPageModel | null>(null);
  protected readonly cases = signal<LegalCase[]>([]);
  protected readonly actors = signal<string[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly expanded = signal<string | null>(null);
  protected readonly pageIndex = signal(0);

  protected readonly form = this.fb.nonNullable.group({
    caseId: '',
    actor: '',
    action: '',
    q: '',
    after: '',
    before: '',
    size: 25,
  });

  protected readonly pagination = computed(() => {
    const page = this.page();
    if (!page) {
      return { current: 1, total: 1, showing: 0 };
    }
    return {
      current: page.page + 1,
      total: Math.max(1, Math.ceil(page.total / page.size)),
      showing: page.entries.length,
    };
  });

  constructor() {
    const caseId = inject(ActivatedRoute).snapshot.queryParamMap.get('caseId');
    if (caseId) {
      this.form.patchValue({ caseId });
    }

    this.caseApi.listCases().subscribe({
      next: (cases) => this.cases.set(cases),
      error: () => this.cases.set([]),
    });

    this.auditApi.listActors().subscribe({
      next: (actors) => this.actors.set(actors),
      error: () => this.actors.set([]),
    });

    this.load();
  }

  protected apply(): void {
    this.pageIndex.set(0);
    this.load();
  }

  protected reset(): void {
    this.form.reset({ size: 25 });
    this.apply();
  }

  protected step(offset: number): void {
    this.pageIndex.update((current) => Math.max(0, current + offset));
    this.load();
  }

  protected toggle(entry: AuditEntry): void {
    this.expanded.update((current) => (current === entry.id ? null : entry.id));
  }

  protected hasDetail(entry: AuditEntry): boolean {
    return !!entry.before || !!entry.after;
  }

  protected load(): void {
    const value = this.form.getRawValue();
    this.loading.set(true);

    this.auditApi
      .query({
        caseId: value.caseId || null,
        actor: value.actor || null,
        action: (value.action as AuditAction) || null,
        q: value.q || null,
        after: value.after ? new Date(`${value.after}T00:00:00Z`).toISOString() : null,
        before: value.before ? new Date(`${value.before}T23:59:59Z`).toISOString() : null,
        page: this.pageIndex(),
        size: Number(value.size),
      })
      .subscribe({
        next: (page) => {
          this.page.set(page);
          this.error.set(null);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.error.set(describeError(error));
          this.page.set(null);
          this.loading.set(false);
        },
      });
  }
}
