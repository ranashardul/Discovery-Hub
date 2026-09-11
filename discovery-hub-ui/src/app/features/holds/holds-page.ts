import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { catchError, firstValueFrom, of } from 'rxjs';
import { describeError } from '../../core/api/api-error';
import { HoldApi } from '../../core/api/hold-api';
import { SearchApi } from '../../core/api/search-api';
import { DeletionAttemptResult, HoldStatus, LegalHold } from '../../core/models/hold';
import { CURRENT_ACTOR } from '../../core/mock/mock-store';
import { ToastService } from '../../shared/notifications/toast.service';
import { AgoPipe, LabelPipe } from '../../shared/pipes/format.pipes';
import { Empty, ErrorBand, Loading } from '../../shared/ui/state-blocks';
import { StatusChip } from '../../shared/ui/status-chip';
import { PlaceHoldDialog } from './place-hold-dialog';

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
    PlaceHoldDialog,
  ],
  templateUrl: './holds-page.html',
})
export class HoldsPage {
  private readonly holdApi = inject(HoldApi);
  private readonly searchApi = inject(SearchApi);
  private readonly toast = inject(ToastService);
  private readonly fb = inject(FormBuilder);

  protected readonly statuses: HoldStatus[] = ['ACTIVE', 'RELEASED'];

  protected readonly holds = signal<LegalHold[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly statusFilter = signal<HoldStatus | ''>('');
  protected readonly deletionResult = signal<DeletionAttemptResult | null>(null);
  protected readonly checking = signal(false);
  protected readonly dialogOpen = signal(false);

  protected readonly deleteForm = this.fb.nonNullable.group({ messageId: '' });

  protected readonly visible = computed(() => {
    const filter = this.statusFilter();
    return filter ? this.holds().filter((hold) => hold.status === filter) : this.holds();
  });

  protected readonly totals = computed(() => {
    const active = this.holds().filter((hold) => hold.status === 'ACTIVE');
    return {
      active: active.length,
      held: active.reduce((total, hold) => total + hold.matchedMessageCount, 0),
    };
  });

  constructor() {
    this.load();
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

  protected onPlaced(hold: LegalHold): void {
    this.dialogOpen.set(false);
    this.toast.success(
      `Hold ${hold.id} placed over ${hold.matchedMessageCount} communication(s). ` +
        'Preservation is applied to the message data in the background.',
    );
    this.load(true);
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

  /**
   * The exact scope of a hold, as search criteria.
   *
   * The link used to pass `q: searchTerms || 'the'`, because search demanded
   * query text. A hold is usually scoped by custodians and dates with no
   * terms at all, so "the" was an invented word standing in for "everything":
   * it quietly dropped every held message that does not contain it, and the
   * count on this row never matched the results the link opened.
   */
  protected heldLinkParams(hold: LegalHold): Record<string, string | string[]> {
    const params: Record<string, string | string[]> = { onHold: 'true' };

    if (hold.scope.searchTerms) {
      params['q'] = hold.scope.searchTerms;
    }
    if (hold.scope.custodianIds.length > 0) {
      params['participant'] = hold.scope.custodianIds;
    }
    if (hold.scope.after) {
      params['after'] = hold.scope.after;
    }
    if (hold.scope.before) {
      params['before'] = hold.scope.before;
    }

    return params;
  }

  /** Picks a message that is genuinely on hold, so the test has a subject. */
  protected async pickHeldMessage(): Promise<void> {
    try {
      const response = await firstValueFrom(
        this.searchApi.search({ q: '', onHold: true, size: 1, sort: 'newest' }),
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
