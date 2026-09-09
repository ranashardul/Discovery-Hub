import { Pipe, PipeTransform } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { inject } from '@angular/core';

/** Bytes as a human-readable size. */
@Pipe({ name: 'bytes' })
export class BytesPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    if (value === null || value === undefined || Number.isNaN(value)) {
      return '—';
    }
    if (value < 1024) {
      return `${value} B`;
    }
    const units = ['kB', 'MB', 'GB', 'TB'];
    let size = value / 1024;
    let unit = 0;
    while (size >= 1024 && unit < units.length - 1) {
      size /= 1024;
      unit++;
    }
    return `${size.toFixed(size < 10 ? 1 : 0)} ${units[unit]}`;
  }
}

/** Relative time, e.g. "4 min ago". Falls back to "—" for empty input. */
@Pipe({ name: 'ago' })
export class AgoPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (!value) {
      return '—';
    }

    const millis = Date.now() - Date.parse(value);
    const future = millis < 0;
    const seconds = Math.abs(Math.round(millis / 1000));

    const units: [number, string][] = [
      [60, 'sec'],
      [3600, 'min'],
      [86400, 'hr'],
      [2592000, 'day'],
      [31536000, 'mo'],
    ];

    let text = `${Math.round(seconds / 31536000)} yr`;
    let previous = 1;

    for (const [limit, label] of units) {
      if (seconds < limit) {
        const amount = Math.max(1, Math.round(seconds / previous));
        text = `${amount} ${label}`;
        break;
      }
      previous = limit;
    }

    return future ? `in ${text}` : `${text} ago`;
  }
}

/** Turns UPPER_SNAKE enum values into readable labels. */
@Pipe({ name: 'label' })
export class LabelPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (!value) {
      return '—';
    }
    return value
      .toLowerCase()
      .split('_')
      .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
      .join(' ');
  }
}

/** Retention minutes rendered as years/days/minutes. */
@Pipe({ name: 'duration' })
export class DurationPipe implements PipeTransform {
  transform(minutes: number | null | undefined): string {
    if (!minutes) {
      return '—';
    }
    if (minutes < 60) {
      return `${minutes} min`;
    }
    if (minutes < 1440) {
      return `${(minutes / 60).toFixed(1)} hr`;
    }
    const days = minutes / 1440;
    if (days < 365) {
      return `${Math.round(days)} days`;
    }
    return `${(days / 365).toFixed(1)} years`;
  }
}

/**
 * Marks search-highlight markup as trusted.
 *
 * The only HTML that reaches this pipe is produced by the search layer, which
 * escapes the message text before inserting `<mark>` tags — so the message body
 * itself can never inject markup.
 */
@Pipe({ name: 'highlighted' })
export class HighlightedPipe implements PipeTransform {
  private readonly sanitizer = inject(DomSanitizer);

  transform(value: string | null | undefined): SafeHtml {
    return this.sanitizer.bypassSecurityTrustHtml(value ?? '');
  }
}
