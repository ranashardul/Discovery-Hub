import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, interval, of, startWith, switchMap } from 'rxjs';
import { AuditApi } from '../../core/api/audit-api';
import { PlatformApi } from '../../core/api/platform-api';
import { AuditEntry } from '../../core/models/audit';
import { DashboardCounts, ServiceStatus } from '../../core/models/retention';
import { MockStore } from '../../core/mock/mock-store';
import { AgoPipe } from '../../shared/pipes/format.pipes';
import { Loading } from '../../shared/ui/state-blocks';
import { StatusChip } from '../../shared/ui/status-chip';
import { environment } from '../../../environments/environment';

/**
 * Home screen (FR-8.2). Answers "what is in the platform right now" and
 * doubles as the resiliency console: each service can be switched off to show
 * that the rest of the app degrades rather than collapses.
 */
@Component({
  selector: 'app-dashboard-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DecimalPipe, RouterLink, AgoPipe, StatusChip, Loading],
  templateUrl: './dashboard-page.html',
})
export class DashboardPage {
  private readonly platform = inject(PlatformApi);
  private readonly audit = inject(AuditApi);
  private readonly store = inject(MockStore);

  protected readonly counts = signal<DashboardCounts | null>(null);
  protected readonly services = signal<ServiceStatus[]>([]);
  protected readonly recent = signal<AuditEntry[]>([]);
  protected readonly canSimulateOutage = environment.useMockBackend;

  constructor() {
    // One poll drives the whole page; the numbers move while background work
    // (hold propagation, export jobs) is in flight.
    interval(3000)
      .pipe(
        startWith(0),
        switchMap(() => this.platform.counts().pipe(catchError(() => of(null)))),
        takeUntilDestroyed(),
      )
      .subscribe((counts) => counts && this.counts.set(counts));

    interval(3000)
      .pipe(
        startWith(0),
        switchMap(() => this.platform.serviceStatus().pipe(catchError(() => of([])))),
        takeUntilDestroyed(),
      )
      .subscribe((services) => this.services.set(services));

    interval(3000)
      .pipe(
        startWith(0),
        switchMap(() =>
          this.audit.query({ size: 8 }).pipe(catchError(() => of({ entries: [] as AuditEntry[] }))),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((page) => this.recent.set(page.entries));
  }

  protected toggleService(id: string): void {
    this.services.set(this.store.toggleService(id));
  }

  protected percentage(part: number, whole: number): number {
    return whole === 0 ? 0 : Math.round((part / whole) * 100);
  }
}
