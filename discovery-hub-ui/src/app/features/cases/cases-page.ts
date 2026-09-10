import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { describeError } from '../../core/api/api-error';
import { CaseApi } from '../../core/api/case-api';
import { CaseStatus, LegalCase, MatterType } from '../../core/models/case';
import { CURRENT_ACTOR } from '../../core/mock/mock-store';
import { ToastService } from '../../shared/notifications/toast.service';
import { LabelPipe } from '../../shared/pipes/format.pipes';
import { Modal } from '../../shared/ui/modal';
import { Empty, ErrorBand, Loading } from '../../shared/ui/state-blocks';
import { StatusChip } from '../../shared/ui/status-chip';

/** Case dashboard (FR-2.1): the list of matters and the entry point to create one. */
@Component({
  selector: 'app-cases-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    DatePipe,
    LabelPipe,
    StatusChip,
    Modal,
    Loading,
    Empty,
    ErrorBand,
  ],
  templateUrl: './cases-page.html',
})
export class CasesPage {
  private readonly caseApi = inject(CaseApi);
  private readonly toast = inject(ToastService);
  private readonly fb = inject(FormBuilder);

  protected readonly statuses: CaseStatus[] = ['OPEN', 'CLOSED', 'ARCHIVED'];
  protected readonly matterTypes: MatterType[] = ['INVESTIGATION', 'LITIGATION', 'REGULATORY_INQUIRY'];

  protected readonly cases = signal<LegalCase[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly statusFilter = signal<CaseStatus | ''>('');
  protected readonly search = signal('');
  protected readonly createOpen = signal(false);
  protected readonly busy = signal(false);

  protected readonly createForm = this.fb.nonNullable.group({
    name: ['', Validators.required],
    description: '',
    matterType: 'INVESTIGATION' as MatterType,
    owner: [CURRENT_ACTOR, Validators.required],
  });

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.caseApi
      .listCases({ status: this.statusFilter() || null, q: this.search() || null })
      .subscribe({
        next: (cases) => {
          this.cases.set(cases);
          this.error.set(null);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.error.set(describeError(error));
          this.cases.set([]);
          this.loading.set(false);
        },
      });
  }

  protected applyStatus(status: string): void {
    this.statusFilter.set(status as CaseStatus | '');
    this.load();
  }

  protected applySearch(value: string): void {
    this.search.set(value);
    this.load();
  }

  protected create(): void {
    if (this.createForm.invalid) {
      this.createForm.markAllAsTouched();
      return;
    }

    this.busy.set(true);
    this.caseApi.createCase(this.createForm.getRawValue()).subscribe({
      next: (created) => {
        this.toast.success(`${created.caseNumber} created as Draft`);
        this.createOpen.set(false);
        this.createForm.reset({ matterType: 'INVESTIGATION', owner: CURRENT_ACTOR });
        this.busy.set(false);
        this.load();
      },
      error: (error: unknown) => {
        this.toast.error(error);
        this.busy.set(false);
      },
    });
  }
}
