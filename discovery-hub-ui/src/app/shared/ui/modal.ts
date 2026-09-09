import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/**
 * Lightweight modal shell. Content and footer actions are projected, so each
 * caller keeps its own form state and validation.
 */
@Component({
  selector: 'app-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="backdrop" (click)="dismissed.emit()">
      <div
        class="panel"
        [class.panel--wide]="wide()"
        role="dialog"
        aria-modal="true"
        (click)="$event.stopPropagation()"
      >
        <header class="panel__head">
          <div>
            <h2>{{ title() }}</h2>
            @if (subtitle()) {
              <p>{{ subtitle() }}</p>
            }
          </div>
          <button type="button" class="panel__close" (click)="dismissed.emit()" aria-label="Close">
            &times;
          </button>
        </header>

        <div class="panel__body">
          <ng-content />
        </div>

        <footer class="panel__foot">
          <ng-content select="[modalActions]" />
        </footer>
      </div>
    </div>
  `,
  styles: `
    .backdrop {
      position: fixed;
      inset: 0;
      z-index: 50;
      display: flex;
      align-items: flex-start;
      justify-content: center;
      padding: 4rem 1rem 2rem;
      background: rgb(15 23 42 / 45%);
      overflow-y: auto;
    }

    .panel {
      width: min(38rem, 100%);
      background: var(--color-surface);
      border-radius: var(--radius-lg);
      box-shadow: var(--shadow-lg);
      display: flex;
      flex-direction: column;
    }

    .panel--wide { width: min(64rem, 100%); }

    .panel__head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
      padding: 1.1rem 1.25rem;
      border-bottom: 1px solid var(--color-border);
    }

    .panel__head h2 { margin: 0; font-size: 1.05rem; }
    .panel__head p { margin: 0.2rem 0 0; font-size: 0.82rem; color: var(--color-text-muted); }

    .panel__close {
      border: 0;
      background: none;
      font-size: 1.4rem;
      line-height: 1;
      cursor: pointer;
      color: var(--color-text-muted);
    }

    .panel__body { padding: 1.25rem; }

    .panel__foot {
      display: flex;
      justify-content: flex-end;
      gap: 0.6rem;
      padding: 1rem 1.25rem;
      border-top: 1px solid var(--color-border);
      background: var(--color-surface-muted);
      border-radius: 0 0 var(--radius-lg) var(--radius-lg);
    }
  `,
})
export class Modal {
  readonly title = input.required<string>();
  readonly subtitle = input('');
  readonly wide = input(false);
  readonly dismissed = output<void>();
}
