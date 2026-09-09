import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { describeError } from '../../core/api/api-error';
import { PlatformApi } from '../../core/api/platform-api';
import { CommunicationType } from '../../core/models/message';
import { DispositionRun, RetentionPolicy } from '../../core/models/retention';
import { ToastService } from '../../shared/notifications/toast.service';
import { AgoPipe, DurationPipe, LabelPipe } from '../../shared/pipes/format.pipes';
import { Empty, ErrorBand, Loading } from '../../shared/ui/state-blocks';

/**
 * Retention configuration and disposition history (FR-5). Periods are edited in
 * minutes so a demo can set a policy short enough to see a disposition run
 * actually delete something.
 */
@Component({
  selector: 'app-retention-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    DatePipe,
    DecimalPipe,
    AgoPipe,
    DurationPipe,
    LabelPipe,
    Loading,
    Empty,
    ErrorBand,
  ],
  templateUrl: './retention-page.html',
})
export class RetentionPage {
  private readonly platform = inject(PlatformApi);
  private readonly toast = inject(ToastService);
  private readonly fb = inject(FormBuilder);

  protected readonly policies = signal<RetentionPolicy[]>([]);
  protected readonly runs = signal<DispositionRun[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly running = signal(false);

  /** One control per communication type, keyed by type. */
  protected readonly form = this.fb.nonNullable.group<Record<string, unknown>>({});

  protected readonly presets = [
    { label: '5 minutes (demo)', minutes: 5 },
    { label: '1 day', minutes: 1440 },
    { label: '3 years', minutes: 3 * 365 * 24 * 60 },
    { label: '7 years', minutes: 7 * 365 * 24 * 60 },
  ];

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.platform.retentionPolicies().subscribe({
      next: (policies) => {
        this.policies.set(policies);
        for (const policy of policies) {
          const control = this.fb.nonNullable.control(policy.retentionMinutes);
          if (this.form.contains(policy.communicationType)) {
            this.form.setControl(policy.communicationType, control);
          } else {
            this.form.addControl(policy.communicationType, control);
          }
        }
        this.error.set(null);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.error.set(describeError(error));
        this.loading.set(false);
      },
    });

    this.platform.dispositionRuns().subscribe({
      next: (runs) => this.runs.set(runs),
      error: () => this.runs.set([]),
    });
  }

  protected minutesFor(type: CommunicationType): number {
    return Number(this.form.get(type)?.value ?? 0);
  }

  protected applyPreset(type: CommunicationType, minutes: number): void {
    this.form.get(type)?.setValue(minutes);
  }

  protected async save(type: CommunicationType): Promise<void> {
    try {
      const updated = await firstValueFrom(
        this.platform.updateRetentionPolicy(type, this.minutesFor(type)),
      );
      this.toast.success(
        `${type} retention set to ${updated.retentionMinutes} minute(s). Messages past retention are now eligible for disposition.`,
      );
      this.load();
    } catch (error) {
      this.toast.error(error);
    }
  }

  protected async runNow(): Promise<void> {
    this.running.set(true);
    try {
      const run = await firstValueFrom(this.platform.runDisposition());
      this.toast.success(
        `Disposition complete — ${run.deleted} deleted, ${run.skippedOnHold} skipped because they are on hold`,
      );
      this.load();
    } catch (error) {
      this.toast.error(error);
    } finally {
      this.running.set(false);
    }
  }
}
