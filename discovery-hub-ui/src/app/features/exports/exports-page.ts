import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, firstValueFrom, interval, of } from 'rxjs';
import { environment } from '../../../environments/environment';
import { describeError } from '../../core/api/api-error';
import { ExportApi } from '../../core/api/export-api';
import { ExportJob, ExportManifest, VerificationReport } from '../../core/models/export';
import { MockStore } from '../../core/mock/mock-store';
import { ToastService } from '../../shared/notifications/toast.service';
import { AgoPipe, BytesPipe, LabelPipe } from '../../shared/pipes/format.pipes';
import { Modal } from '../../shared/ui/modal';
import { Empty, ErrorBand, Loading } from '../../shared/ui/state-blocks';
import { StatusChip } from '../../shared/ui/status-chip';

/**
 * Export jobs with live status (FR-6). Packages are verifiable: the manifest
 * carries a checksum per item plus one for the package, and verification
 * re-computes both.
 */
@Component({
  selector: 'app-exports-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
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
  templateUrl: './exports-page.html',
})
export class ExportsPage {
  private readonly exportApi = inject(ExportApi);
  private readonly store = inject(MockStore);
  private readonly toast = inject(ToastService);

  protected readonly jobs = signal<ExportJob[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly manifest = signal<ExportManifest | null>(null);
  protected readonly verification = signal<VerificationReport | null>(null);
  protected readonly canTamper = environment.useMockBackend;

  protected readonly counts = computed(() => ({
    completed: this.jobs().filter((job) => job.status === 'COMPLETED').length,
    inFlight: this.jobs().filter((job) => job.status === 'QUEUED' || job.status === 'RUNNING').length,
    failed: this.jobs().filter((job) => job.status === 'FAILED').length,
  }));

  constructor() {
    this.load();

    // Job status is polled: the request only returns an ID, progress arrives
    // afterwards (FR-6.2).
    interval(environment.jobPollIntervalMs)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        if (this.jobs().some((job) => job.status === 'QUEUED' || job.status === 'RUNNING')) {
          this.load(true);
        }
      });
  }

  protected load(quiet = false): void {
    if (!quiet) {
      this.loading.set(true);
    }
    this.exportApi
      .listJobs()
      .pipe(
        catchError((error: unknown) => {
          this.error.set(describeError(error));
          return of([] as ExportJob[]);
        }),
      )
      .subscribe((jobs) => {
        this.jobs.set(jobs);
        if (jobs.length > 0) {
          this.error.set(null);
        }
        this.loading.set(false);
      });
  }

  protected expired(job: ExportJob): boolean {
    return !job.downloadExpiresAt || Date.parse(job.downloadExpiresAt) < Date.now();
  }

  protected async download(job: ExportJob): Promise<void> {
    try {
      const updated = await firstValueFrom(this.exportApi.registerDownload(job.id));
      this.toast.success(
        this.expired(job)
          ? `Link had expired — a fresh one was issued and the download recorded (${updated.downloadCount} total)`
          : `Download recorded (${updated.downloadCount} total)`,
      );
      this.load(true);
    } catch (error) {
      this.toast.error(error);
    }
  }

  protected async retry(job: ExportJob): Promise<void> {
    try {
      await firstValueFrom(this.exportApi.retryJob(job.id));
      this.toast.info('Job re-queued. The partial package was discarded, so no duplicate is produced.');
      this.load(true);
    } catch (error) {
      this.toast.error(error);
    }
  }

  protected async openManifest(job: ExportJob): Promise<void> {
    try {
      this.manifest.set(await firstValueFrom(this.exportApi.getManifest(job.id)));
    } catch (error) {
      this.toast.error(error);
    }
  }

  protected async verify(job: ExportJob): Promise<void> {
    this.busy.set(true);
    this.verification.set(null);
    try {
      const report = await firstValueFrom(this.exportApi.verifyPackage(job.id));
      this.verification.set(report);
      if (report.passed) {
        this.toast.success(`${report.itemsChecked} checksum(s) matched the manifest`);
      } else {
        this.toast.error(`Verification failed — ${report.mismatches.length} mismatch(es) detected`);
      }
    } catch (error) {
      this.toast.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  /** Demo affordance: corrupt a package so verification has something to catch. */
  protected tamper(job: ExportJob): void {
    try {
      this.store.tamperWithPackage(job.id);
      this.toast.info(`Package ${job.id} corrupted. Run "Verify" to see the tampering detected.`);
    } catch (error) {
      this.toast.error(error);
    }
  }
}
