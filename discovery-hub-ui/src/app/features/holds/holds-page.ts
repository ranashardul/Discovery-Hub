import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, firstValueFrom, interval, of } from 'rxjs';
import { environment } from '../../../environments/environment';
import { describeError } from '../../core/api/api-error';
import { HoldApi } from '../../core/api/hold-api';
import { SearchApi } from '../../core/api/search-api';
import { DeletionAttemptResult, HoldStatus, LegalHold } from '../../core/models/hold';
import { CURRENT_ACTOR } from '../../core/mock/mock-store';
import { ToastService } from '../../shared/notifications/toast.service';
import { AgoPipe, LabelPipe } from '../../shared/pipes/format.pipes';
import { Empty, ErrorBand, Loading } from '../../shared/ui/state-blocks';
import { StatusChip } from '../../shared/ui/status-chip';

/**
 * Hold management across every case (FR-4), plus the deletion test that proves
 * held material cannot be removed (FR-4.6).
 */
@Component({
  selector: 'app-holds-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    DatePipe,
    DecimalPipe,
    AgoPipe,
    LabelPipe,
    StatusChip,
    Loading,
    Empty,
    ErrorBand,
  ],
  templateUrl: './holds-page.html',
})
export class HoldsPage {
  private readonly holdApi = inject(HoldApi);
  private readonly searchApi = inject(SearchApi);
  private readonly toast = inject(ToastService);
  private readonly fb = inject(FormBuilder);

  protected readonly statuses: HoldStatus[] = ['PROPAGATING', 'ACTIVE', 'RELEASING', 'RELEASED'];

  protected readonly holds = signal<LegalHold[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly statusFilter = signal<HoldStatus | ''>('');
  protected readonly deletionResult = signal<DeletionAttemptResult | null>(null);
  protected readonly checking = signal(false);

  protected readonly deleteForm = this.fb.nonNullable.group({ messageId: '' });

  protected readonly visible = computed(() => {
    const filter = this.statusFilter();
    return filter ? this.holds().filter((hold) => hold.status === filter) : this.holds();
  });

  protected readonly totals = computed(() => {
    const active = this.holds().filter((hold) => hold.status !== 'RELEASED');
    return {
      active: active.length,
      propagating: this.holds().filter((hold) => hold.status === 'PROPAGATING').length,
      held: active.reduce((total, hold) => total + hold.matchedMessageCount, 0),
    };
  });

  constructor() {
    this.load();

    interval(environment.jobPollIntervalMs)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        if (this.holds().some((hold) => hold.status === 'PROPAGATING' || hold.status === 'RELEASING')) {
          this.load(true);
        }
      });
  }

  protected load(quiet = false): void {
    if (!quiet) {
      this.loading.set(true);
    }
    this.holdApi
      .listHolds()
      .pipe(catchError((error: unknown) => {
        this.error.set(describeError(error));
        return of([] as LegalHold[]);
      }))
      .subscribe((holds) => {
        if (holds.length > 0 || !this.error()) {
          this.error.set(null);
        }
        this.holds.set(holds);
        this.loading.set(false);
      });
  }

  protected async release(holdId: string): Promise<void> {
    try {
      await firstValueFrom(this.holdApi.releaseHold(holdId, CURRENT_ACTOR));
      this.toast.info(
        'Hold released. Messages still covered by another active hold remain protected.',
      );
      this.load(true);
    } catch (error) {
      this.toast.error(error);
    }
  }

  /** Runs the delete attempt against whatever message ID the user supplies. */
  protected async testDeletion(): Promise<void> {
    const messageId = this.deleteForm.getRawValue().messageId.trim();
    if (!messageId) {
      this.toast.error('Enter a message ID, e.g. msg-000123');
      return;
    }

    this.checking.set(true);
    this.deletionResult.set(null);
    try {
      const result = await firstValueFrom(this.holdApi.attemptDelete(messageId));
      this.deletionResult.set(result);
    } catch (error) {
      this.toast.error(error);
    } finally {
      this.checking.set(false);
    }
  }

  /** Picks a message that is genuinely on hold, so the test has a subject. */
  protected async pickHeldMessage(): Promise<void> {
    try {
      const response = await firstValueFrom(
        this.searchApi.search({ q: 'the', onHold: true, size: 1, sort: 'newest' }),
      );
      const hit = response.results[0];
      if (!hit) {
        this.toast.info('No held messages found — place a hold first.');
        return;
      }
      this.deleteForm.patchValue({ messageId: hit.messageId });
      this.toast.info(`Loaded held message ${hit.messageId}`);
    } catch (error) {
      this.toast.error(error);
    }
  }
}
