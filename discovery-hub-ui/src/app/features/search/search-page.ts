import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { CaseApi } from '../../core/api/case-api';
import { SearchApi } from '../../core/api/search-api';
import { describeError } from '../../core/api/api-error';
import { LegalCase } from '../../core/models/case';
import { Custodian } from '../../core/models/message';
import { SearchCriteria, SearchResponse, SearchSort } from '../../core/models/search';
import { ToastService } from '../../shared/notifications/toast.service';
import { HighlightedPipe } from '../../shared/pipes/format.pipes';
import { Modal } from '../../shared/ui/modal';
import { Empty, ErrorBand, Loading } from '../../shared/ui/state-blocks';
import { StatusChip } from '../../shared/ui/status-chip';

const PAGE_SIZES = [10, 20, 50, 100];

/**
 * Search across the archive (FR-3): full text with filters, highlighted hits,
 * pagination, sorting, saved searches and bulk evidence capture.
 */
@Component({
  selector: 'app-search-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    DatePipe,
    DecimalPipe,
    HighlightedPipe,
    StatusChip,
    Modal,
    Loading,
    Empty,
    ErrorBand,
  ],
  templateUrl: './search-page.html',
})
export class SearchPage {
  private readonly searchApi = inject(SearchApi);
  private readonly caseApi = inject(CaseApi);
  private readonly toast = inject(ToastService);
  private readonly fb = inject(FormBuilder);

  protected readonly pageSizes = PAGE_SIZES;
  protected readonly sorts: { value: SearchSort; label: string }[] = [
    { value: 'relevance', label: 'Relevance' },
    { value: 'newest', label: 'Newest first' },
    { value: 'oldest', label: 'Oldest first' },
  ];

  protected readonly form = this.fb.nonNullable.group({
    q: '',
    communicationType: '',
    sender: '',
    recipient: '',
    hasAttachments: false,
    onHold: '',
    after: '',
    before: '',
    sort: 'relevance' as SearchSort,
    size: 20,
  });

  protected readonly response = signal<SearchResponse | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly from = signal(0);
  protected readonly selected = signal<Set<string>>(new Set());
  protected readonly filtersOpen = signal(true);

  protected readonly openCases = signal<LegalCase[]>([]);
  protected readonly custodians = signal<Custodian[]>([]);
  protected readonly evidenceModalOpen = signal(false);
  protected readonly saveModalOpen = signal(false);
  protected readonly busy = signal(false);

  protected readonly evidenceForm = this.fb.nonNullable.group({
    caseId: '',
    scope: 'PAGE' as 'PAGE' | 'ALL' | 'SELECTED',
  });

  protected readonly saveForm = this.fb.nonNullable.group({ caseId: '', name: '' });

  protected readonly page = computed(() => {
    const response = this.response();
    if (!response || response.size === 0) {
      return { current: 1, total: 1 };
    }
    return {
      current: Math.floor(response.from / response.size) + 1,
      total: Math.max(1, Math.ceil(response.total / response.size)),
    };
  });

  protected readonly hasNext = computed(() => {
    const response = this.response();
    return !!response && response.from + response.size < response.total;
  });

  constructor() {
    this.caseApi.listCases().subscribe({
      next: (cases) => this.openCases.set(cases.filter((item) => item.status !== 'CLOSED')),
      error: () => this.openCases.set([]),
    });

    this.caseApi.listAllCustodians().subscribe({
      next: (custodians) => this.custodians.set(custodians),
      error: () => this.custodians.set([]),
    });

    // Saved searches and hold scopes deep-link into this page, so a query
    // string is treated as a criteria set to run immediately.
    const params = inject(ActivatedRoute).snapshot.queryParamMap;
    if (params.get('q')) {
      this.applyCriteria({
        q: params.get('q') ?? '',
        communicationType: params.get('communicationType') as 'EMAIL' | 'CHAT' | null,
        sender: params.get('sender'),
        recipient: params.get('recipient'),
        onHold: params.get('onHold') === null ? null : params.get('onHold') === 'true',
        hasAttachments: params.get('hasAttachments') === 'true' ? true : null,
        after: params.get('after'),
        before: params.get('before'),
        sort: (params.get('sort') as SearchSort | null) ?? 'relevance',
      });
    }
  }

  protected submit(): void {
    this.from.set(0);
    this.run();
  }

  protected reset(): void {
    this.form.reset({ sort: 'relevance', size: 20, hasAttachments: false });
    this.response.set(null);
    this.error.set(null);
    this.selected.set(new Set());
  }

  protected goToPage(offset: number): void {
    const response = this.response();
    if (!response) {
      return;
    }
    this.from.set(Math.max(0, response.from + offset * response.size));
    this.run();
  }

  /** Re-runs a saved query. Filters are restored into the form first. */
  protected applyCriteria(criteria: SearchCriteria): void {
    this.form.patchValue({
      q: criteria.q,
      communicationType: criteria.communicationType ?? '',
      sender: criteria.sender ?? '',
      recipient: criteria.recipient ?? '',
      hasAttachments: criteria.hasAttachments ?? false,
      onHold: criteria.onHold === null || criteria.onHold === undefined ? '' : String(criteria.onHold),
      after: criteria.after ? criteria.after.slice(0, 10) : '',
      before: criteria.before ? criteria.before.slice(0, 10) : '',
      sort: criteria.sort ?? 'relevance',
      size: criteria.size ?? 20,
    });
    this.submit();
  }

  protected toggleSelection(messageId: string): void {
    this.selected.update((current) => {
      const next = new Set(current);
      if (!next.delete(messageId)) {
        next.add(messageId);
      }
      return next;
    });
  }

  protected toggleAllOnPage(checked: boolean): void {
    const ids = this.response()?.results.map((result) => result.messageId) ?? [];
    this.selected.update((current) => {
      const next = new Set(current);
      for (const id of ids) {
        if (checked) {
          next.add(id);
        } else {
          next.delete(id);
        }
      }
      return next;
    });
  }

  protected allOnPageSelected(): boolean {
    const results = this.response()?.results ?? [];
    return results.length > 0 && results.every((result) => this.selected().has(result.messageId));
  }

  protected openEvidenceModal(scope: 'PAGE' | 'ALL' | 'SELECTED'): void {
    if (this.openCases().length === 0) {
      this.toast.error('No open case to add evidence to — create one first');
      return;
    }
    this.evidenceForm.setValue({ caseId: this.openCases()[0].id, scope });
    this.evidenceModalOpen.set(true);
  }

  protected openSaveModal(): void {
    if (this.openCases().length === 0) {
      this.toast.error('Saved searches are attached to a case — create one first');
      return;
    }
    this.saveForm.setValue({
      caseId: this.openCases()[0].id,
      name: this.form.getRawValue().q.slice(0, 60),
    });
    this.saveModalOpen.set(true);
  }

  protected async confirmAddEvidence(): Promise<void> {
    const { caseId, scope } = this.evidenceForm.getRawValue();
    const response = this.response();
    if (!response) {
      return;
    }

    this.busy.set(true);
    try {
      let messageIds: string[];
      if (scope === 'SELECTED') {
        messageIds = [...this.selected()];
      } else if (scope === 'PAGE') {
        messageIds = response.results.map((result) => result.messageId);
      } else {
        // "All results" is resolved server side: the browser never pages
        // through thousands of hits to build the list.
        messageIds = await firstValueFrom(this.searchApi.resolveAllIds(this.criteria()));
      }

      const added = await firstValueFrom(
        this.caseApi.addEvidence(caseId, {
          messageIds,
          source: scope === 'SELECTED' ? 'MANUAL' : 'SEARCH_RESULT_SET',
        }),
      );

      const skipped = messageIds.length - added.length;
      this.toast.success(
        `${added.length} message(s) added as evidence${skipped > 0 ? `; ${skipped} already on the case` : ''}`,
      );
      this.evidenceModalOpen.set(false);
      this.selected.set(new Set());
      this.refreshCases();
    } catch (error) {
      this.toast.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async confirmSaveSearch(): Promise<void> {
    const { caseId, name } = this.saveForm.getRawValue();
    if (!name.trim()) {
      this.toast.error('Give the saved search a name');
      return;
    }

    this.busy.set(true);
    try {
      await firstValueFrom(this.searchApi.saveSearch(caseId, name.trim(), this.criteria()));
      this.toast.success(`Search saved to the case and re-runnable from case detail`);
      this.saveModalOpen.set(false);
    } catch (error) {
      this.toast.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected criteria(): SearchCriteria {
    const value = this.form.getRawValue();
    return {
      q: value.q.trim(),
      communicationType: value.communicationType ? (value.communicationType as 'EMAIL' | 'CHAT') : null,
      sender: value.sender.trim() || null,
      recipient: value.recipient.trim() || null,
      hasAttachments: value.hasAttachments ? true : null,
      onHold: value.onHold === '' ? null : value.onHold === 'true',
      after: value.after ? new Date(`${value.after}T00:00:00Z`).toISOString() : null,
      before: value.before ? new Date(`${value.before}T23:59:59Z`).toISOString() : null,
      sort: value.sort,
      from: this.from(),
      size: Number(value.size),
    };
  }

  private run(): void {
    const criteria = this.criteria();
    if (!criteria.q) {
      this.error.set('Enter at least one search term.');
      this.response.set(null);
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.searchApi.search(criteria).subscribe({
      next: (response) => {
        this.response.set(response);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.error.set(describeError(error));
        this.response.set(null);
        this.loading.set(false);
      },
    });
  }

  private refreshCases(): void {
    this.caseApi.listCases().subscribe({
      next: (cases) => this.openCases.set(cases.filter((item) => item.status !== 'CLOSED')),
      error: () => undefined,
    });
  }
}
