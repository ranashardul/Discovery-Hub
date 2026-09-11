import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { PlatformApi } from './core/api/platform-api';
import { CURRENT_ACTOR } from './core/mock/mock-store';
import { ToastHost } from './shared/notifications/toast-host';
import { environment } from '../environments/environment';
import { toSignal } from '@angular/core/rxjs-interop';
import { interval, startWith, switchMap } from 'rxjs';

interface NavItem {
  path: string;
  label: string;
  hint: string;
  icon: string;
}

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ToastHost],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly platform = inject(PlatformApi);

  protected readonly actor = CURRENT_ACTOR;
  protected readonly usingMockBackend = environment.useMockBackend;
  protected readonly navOpen = signal(false);

  protected readonly nav: NavItem[] = [
    { path: '/dashboard', label: 'Dashboard', hint: 'System counts and service health', icon: 'grid' },
    { path: '/search', label: 'Search', hint: 'Full-text search across the archive', icon: 'search' },
    { path: '/cases', label: 'Cases', hint: 'Matters, custodians and evidence', icon: 'folder' },
    { path: '/holds', label: 'Legal holds', hint: 'Preservation scopes', icon: 'lock' },
    { path: '/exports', label: 'Exports', hint: 'Evidence packages and manifests', icon: 'package' },
    { path: '/retention', label: 'Retention', hint: 'Policies and disposition runs', icon: 'clock' },
    { path: '/audit', label: 'Audit trail', hint: 'Append-only chain of custody', icon: 'shield' },
    { path: '/demo', label: 'Retention demo', hint: 'Expire and dispose one message', icon: 'timer' },
  ];

  /** Polled so a simulated outage shows up in the header without a reload. */
  private readonly status = toSignal(
    interval(4000).pipe(
      startWith(0),
      switchMap(() => this.platform.serviceStatus()),
    ),
    { initialValue: [] },
  );

  protected readonly degraded = computed(() =>
    this.status().filter((service) => service.health !== 'UP'),
  );

  protected readonly icons: Record<string, string> = {
    grid: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z',
    search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zm5.5 12.5L21 21',
    folder: 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
    lock: 'M7 11V8a5 5 0 0 1 10 0v3M5 11h14v9H5z',
    package: 'M12 3l9 5v8l-9 5-9-5V8zM3 8l9 5 9-5M12 13v8',
    clock: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zm0 4v5l4 2',
    shield: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z',
    timer: 'M10 3h4M12 7v6l3 2M5 13a7 7 0 1 0 14 0 7 7 0 0 0-14 0z',
  };

  protected toggleNav(): void {
    this.navOpen.update((open) => !open);
  }

  protected closeNav(): void {
    this.navOpen.set(false);
  }
}
