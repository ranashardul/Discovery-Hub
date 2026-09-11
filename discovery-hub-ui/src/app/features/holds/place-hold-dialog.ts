import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { catchError, firstValueFrom, of } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CaseApi } from '../../core/api/case-api';
import { HoldApi } from '../../core/api/hold-api';
import { LegalCase } from '../../core/models/case';
import { LegalHold } from '../../core/models/hold';
import { Custodian } from '../../core/models/message';
import { ToastService } from '../../shared/notifications/toast.service';
import { Modal } from '../../shared/ui/modal';

/**
 * Places a legal hold, either on a case chosen here or on one supplied by the
 * caller.
 *
 * <p>One dialog rather than one per screen: a hold is a legal instrument, and
 * two copies of the form that scopes it would eventually disagree about what a
 * hold covers. The Holds page has no case in hand so it shows the picker; the
 * case detail page passes {@link presetCase} and the picker is hidden.
 *
 * <p>Scope is expressed as custodians plus an optional date range, which maps
 * onto the `criteria` the case service stores. Custodians come from the archive
 * directory (`GET /api/search/custodians`), because `criteria.participants` is
 * matched against message senders and recipients — not against any registry of
 * people, which no service models.
 */
@Component({
  selector: 'app-place-hold-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, DecimalPipe, Modal],
  templateUrl: './place-hold-dialog.html',
})
export class PlaceHoldDialog {
  /**
   * When supplied the hold is placed on this case and no picker is shown.
   * When null the dialog loads the eligible cases and asks which one.
   */
  readonly presetCase = input<LegalCase | null>(null);

  readonly placed = output<LegalHold>();
  readonly dismissed = output<void>();

  private readonly caseApi = inject(CaseApi);
  private readonly holdApi = inject(HoldApi);
  private readonly toast = inject(ToastService);
  private readonly fb = inject(FormBuilder);

  protected readonly cases = signal<LegalCase[]>([]);
  protected readonly directory = signal<Custodian[]>([]);
  protected readonly selectedCaseId = signal('');
  protected readonly scopePreview = signal<number | null>(null);
  protected readonly loadingCases = signal(false);
  protected readonly busy = signal(false);

  /**
   * The criteria model has no free-text term, so HttpHoldApi cannot send one.
   * Offering the field against the real services would silently widen a hold
   * beyond what was asked for.
   */
  protected readonly searchTermsSupported = environment.useMockBackend;

  protected readonly form = this.fb.nonNullable.group({
    reason: ['', Validators.required],
    custodianIds: [[] as string[]],
    after: '',
    before: '',
    searchTerms: '',
  });

  /** The case the hold will be placed on, whether preset or picked. */
  protected readonly targetCase = computed(() => {
    const preset = this.presetCase();
    if (preset) {
      return preset;
    }
    const id = this.selectedCaseId();
    return this.cases().find((item) => item.id === id) ?? null;
  });

  protected readonly needsCasePicker = computed(() => this.presetCase() === null);

  constructor() {
    this.caseApi
      .listAllCustodians()
      .pipe(catchError(() => of([] as Custodian[])))
      .subscribe((directory) => this.directory.set(directory));
  }

  ngOnInit(): void {
    const preset = this.presetCase();
    this.form.reset({
      reason: preset ? `Preservation obligation for ${preset.name}` : '',
      // Deliberately empty: the directory is the whole archive, so prefilling
      // it would offer a hold over every custodian by default.
      custodianIds: [],
      after: '',
      before: '',
      searchTerms: '',
    });

    if (preset) {
      return;
    }

    // A hold cannot be placed on an archived case (the service refuses it),
    // and a closed case is read-only everywhere else in the app, so neither is
    // offered here.
    this.loadingCases.set(true);
    this.caseApi
      .listCases()
      .pipe(catchError(() => of([] as LegalCase[])))
      .subscribe((cases) => {
        this.cases.set(cases.filter((item) => item.status === 'OPEN'));
        this.loadingCases.set(false);
      });
  }

  protected selectCase(caseId: string): void {
    this.selectedCaseId.set(caseId);
    this.scopePreview.set(null);

    const picked = this.cases().find((item) => item.id === caseId);
    if (picked && !this.form.getRawValue().reason.trim()) {
      this.form.patchValue({ reason: `Preservation obligation for ${picked.name}` });
    }
  }

  protected includes(custodianId: string): boolean {
    return this.form.getRawValue().custodianIds.includes(custodianId);
  }

  protected toggleCustodian(custodianId: string, checked: boolean): void {
    const current = new Set(this.form.getRawValue().custodianIds);
    if (checked) {
      current.add(custodianId);
    } else {
      current.delete(custodianId);
    }
    this.form.patchValue({ custodianIds: [...current] });
    this.scopePreview.set(null);
  }

  protected selectedCount(): number {
    return this.form.getRawValue().custodianIds.length;
  }

  protected async previewScope(): Promise<void> {
    if (!this.ready()) {
      return;
    }

    this.busy.set(true);
    try {
      this.scopePreview.set(await firstValueFrom(this.holdApi.previewScope(this.scope())));
    } catch (error) {
      this.toast.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async place(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    if (!this.ready()) {
      return;
    }

    const target = this.targetCase()!;
    this.busy.set(true);
    try {
      const hold = await firstValueFrom(
        this.holdApi.placeHold({
          caseId: target.id,
          reason: this.form.getRawValue().reason,
          scope: this.scope(),
        }),
      );
      this.placed.emit(hold);
    } catch (error) {
      this.toast.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * The service rejects criteria with no filter at all, and a hold over every
   * custodian is never what someone meant to click, so both are caught here
   * with a reason rather than surfacing a 400.
   */
  private ready(): boolean {
    if (!this.targetCase()) {
      this.toast.error('Choose a case to place the hold on');
      return false;
    }
    if (this.selectedCount() === 0) {
      this.toast.error('Select at least one custodian to scope the hold');
      return false;
    }
    return true;
  }

  private scope() {
    const value = this.form.getRawValue();
    return {
      custodianIds: value.custodianIds,
      after: value.after ? new Date(`${value.after}T00:00:00Z`).toISOString() : null,
      before: value.before ? new Date(`${value.before}T23:59:59Z`).toISOString() : null,
      searchTerms: this.searchTermsSupported ? value.searchTerms || null : null,
    };
  }
}
