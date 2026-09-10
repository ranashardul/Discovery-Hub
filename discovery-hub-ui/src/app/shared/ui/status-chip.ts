import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { LabelPipe } from '../pipes/format.pipes';

/**
 * Colour-coded status pill. The palette follows the business workflow diagram:
 * amber for investigation states, red for holds, green for exports, grey for
 * terminal states.
 */
@Component({
  selector: 'app-status-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LabelPipe],
  template: `<span class="chip" [class]="'chip--' + tone()">{{ status() | label }}</span>`,
  styles: `
    .chip {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      padding: 0.15rem 0.55rem;
      border-radius: 999px;
      font-size: 0.72rem;
      font-weight: 600;
      letter-spacing: 0.02em;
      white-space: nowrap;
      border: 1px solid transparent;
    }

    .chip--neutral { background: var(--color-neutral-bg); color: var(--color-neutral-fg); border-color: var(--color-neutral-border); }
    .chip--info { background: var(--color-info-bg); color: var(--color-info-fg); border-color: var(--color-info-border); }
    .chip--active { background: var(--color-amber-bg); color: var(--color-amber-fg); border-color: var(--color-amber-border); }
    .chip--danger { background: var(--color-danger-bg); color: var(--color-danger-fg); border-color: var(--color-danger-border); }
    .chip--success { background: var(--color-success-bg); color: var(--color-success-fg); border-color: var(--color-success-border); }
    .chip--purple { background: var(--color-purple-bg); color: var(--color-purple-fg); border-color: var(--color-purple-border); }
  `,
})
export class StatusChip {
  readonly status = input.required<string>();

  protected readonly tone = computed(() => {
    switch (this.status()) {
      case 'OPEN':
      case 'ACTIVE':
      case 'RUNNING':
        return 'active';
      case 'QUEUED':
      case 'RELEASED':
      case 'CLOSED':
      case 'ARCHIVED':
      case 'DISPOSED':
        return 'neutral';
      case 'FAILED':
      case 'DOWN':
      case 'PAST_RETENTION':
        return 'danger';
      case 'ON_HOLD':
        return 'purple';
      case 'COMPLETED':
      case 'UP':
        return 'success';
      case 'EMAIL':
      case 'CHAT':
        return 'info';
      default:
        return 'purple';
    }
  });
}
