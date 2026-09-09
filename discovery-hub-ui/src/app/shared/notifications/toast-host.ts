import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ToastService } from './toast.service';

@Component({
  selector: 'app-toast-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toast-host" role="status" aria-live="polite">
      @for (toast of toasts.toasts(); track toast.id) {
        <div class="toast" [class]="'toast--' + toast.kind">
          <span>{{ toast.message }}</span>
          <button type="button" class="toast__close" (click)="toasts.dismiss(toast.id)" aria-label="Dismiss">
            &times;
          </button>
        </div>
      }
    </div>
  `,
  styles: `
    .toast-host {
      position: fixed;
      right: 1.25rem;
      bottom: 1.25rem;
      z-index: 60;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      max-width: 26rem;
    }

    .toast {
      display: flex;
      gap: 0.75rem;
      align-items: flex-start;
      padding: 0.75rem 0.9rem;
      border-radius: var(--radius-md);
      border-left: 3px solid var(--color-accent);
      background: var(--color-surface);
      box-shadow: var(--shadow-lg);
      font-size: 0.85rem;
      line-height: 1.4;
    }

    .toast--success { border-left-color: var(--color-success); }
    .toast--error { border-left-color: var(--color-danger); }

    .toast__close {
      border: 0;
      background: none;
      cursor: pointer;
      font-size: 1.1rem;
      line-height: 1;
      color: var(--color-text-muted);
    }
  `,
})
export class ToastHost {
  protected readonly toasts = inject(ToastService);
}
