import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Inline loading placeholder. */
@Component({
  selector: 'app-loading',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="block">
      <span class="spinner" aria-hidden="true"></span>
      <span>{{ message() }}</span>
    </div>
  `,
  styles: `
    .block {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      padding: 1.5rem;
      color: var(--color-text-muted);
      font-size: 0.85rem;
    }

    .spinner {
      width: 1rem;
      height: 1rem;
      border: 2px solid var(--color-border);
      border-top-color: var(--color-accent);
      border-radius: 50%;
      animation: spin 0.7s linear infinite;
    }

    @keyframes spin { to { transform: rotate(360deg); } }
  `,
})
export class Loading {
  readonly message = input('Loading…');
}

/** Empty result placeholder. */
@Component({
  selector: 'app-empty',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="block">
      <p class="title">{{ title() }}</p>
      @if (hint()) {
        <p class="hint">{{ hint() }}</p>
      }
      <ng-content />
    </div>
  `,
  styles: `
    .block {
      padding: 2rem 1.5rem;
      text-align: center;
      color: var(--color-text-muted);
    }

    .title { margin: 0; font-weight: 600; color: var(--color-text); }
    .hint { margin: 0.35rem 0 0; font-size: 0.85rem; }
  `,
})
export class Empty {
  readonly title = input.required<string>();
  readonly hint = input('');
}

/**
 * Error surface used when a dependency is unavailable. Renders as a contained
 * band rather than replacing the page, so one failed service does not blank the
 * whole screen (NFR-2).
 */
@Component({
  selector: 'app-error-band',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="band" role="alert">
      <strong>{{ title() }}</strong>
      <span>{{ message() }}</span>
      <ng-content />
    </div>
  `,
  styles: `
    .band {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem 0.75rem;
      padding: 0.75rem 1rem;
      border-radius: var(--radius-md);
      background: var(--color-danger-bg);
      color: var(--color-danger-fg);
      border: 1px solid var(--color-danger-border);
      font-size: 0.85rem;
    }
  `,
})
export class ErrorBand {
  readonly title = input('Unavailable');
  readonly message = input('');
}
