import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { describeError } from '../../core/api/api-error';
import { CaseApi } from '../../core/api/case-api';
import { HoldApi } from '../../core/api/hold-api';
import { SearchApi } from '../../core/api/search-api';
import { LegalCase } from '../../core/models/case';
import { DeletionAttemptResult } from '../../core/models/hold';
import { Message } from '../../core/models/message';
import { ToastService } from '../../shared/notifications/toast.service';
import { BytesPipe, LabelPipe } from '../../shared/pipes/format.pipes';
import { Empty, ErrorBand, Loading } from '../../shared/ui/state-blocks';
import { StatusChip } from '../../shared/ui/status-chip';

/**
 * One archived message with its thread, attachments and preservation state.
 * Also the quickest place to add a single item to a case as evidence.
 */
@Component({
  selector: 'app-message-detail-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    DatePipe,
    BytesPipe,
    LabelPipe,
    StatusChip,
    Loading,
    Empty,
    ErrorBand,
  ],
  templateUrl: './message-detail-page.html',
})
export class MessageDetailPage {
  readonly id = input.required<string>();

  private readonly searchApi = inject(SearchApi);
  private readonly caseApi = inject(CaseApi);
  private readonly holdApi = inject(HoldApi);
  private readonly toast = inject(ToastService);
  private readonly fb = inject(FormBuilder);

  protected readonly message = signal<Message | null>(null);
  protected readonly thread = signal<Message[]>([]);
  protected readonly openCases = signal<LegalCase[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly deletionResult = signal<DeletionAttemptResult | null>(null);

  protected readonly evidenceForm = this.fb.nonNullable.group({ caseId: '' });

  ngOnInit(): void {
    this.load();

    this.caseApi.listCases().subscribe({
      next: (cases) => {
        const open = cases.filter((item) => item.status !== 'CLOSED');
        this.openCases.set(open);
        if (open.length > 0) {
          this.evidenceForm.patchValue({ caseId: open[0].id });
        }
      },
      error: () => this.openCases.set([]),
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.searchApi.getMessage(this.id()).subscribe({
      next: (message) => {
        this.message.set(message);
        this.error.set(null);
        this.loading.set(false);
        this.searchApi.getThread(message.threadId).subscribe({
          next: (thread) => this.thread.set(thread),
          error: () => this.thread.set([]),
        });
      },
      error: (error: unknown) => {
        this.error.set(describeError(error));
        this.loading.set(false);
      },
    });
  }

  protected async addToCase(): Promise<void> {
    const caseId = this.evidenceForm.getRawValue().caseId;
    if (!caseId) {
      this.toast.error('Choose a case first');
      return;
    }

    this.busy.set(true);
    try {
      const added = await firstValueFrom(
        this.caseApi.addEvidence(caseId, { messageIds: [this.id()], source: 'MANUAL' }),
      );
      this.toast.success(
        added.length > 0 ? 'Added to the case as evidence' : 'Already on that case',
      );
    } catch (error) {
      this.toast.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async testDeletion(): Promise<void> {
    try {
      const result = await firstValueFrom(this.holdApi.attemptDelete(this.id()));
      this.deletionResult.set(result);
      this.load();
    } catch (error) {
      this.toast.error(error);
    }
  }
}
