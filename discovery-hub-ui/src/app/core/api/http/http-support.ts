import { HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { ApiError } from '../api-error';

/**
 * Shape of the error body every Spring service returns. Sharing it means the
 * UI can surface the field a validation failure names instead of a generic
 * message.
 */
interface ApiErrorBody {
  status?: number;
  error?: string;
  message?: string;
  fieldErrors?: { field?: string; message?: string }[];
}

/**
 * Converts a transport failure into the same {@link ApiError} the in-memory
 * backend throws, so components handle both identically.
 */
export function toApiError(error: unknown): Observable<never> {
  if (!(error instanceof HttpErrorResponse)) {
    return throwError(() => error);
  }

  // status 0 means the request never reached a server: the service is down, or
  // the dev proxy is not running. Saying so beats "Unknown Error".
  if (error.status === 0) {
    return throwError(
      () => new ApiError(503, 'Service unreachable. Is the stack running and the dev proxy configured?'),
    );
  }

  const body = error.error as ApiErrorBody | string | null;
  if (typeof body === 'string' || !body) {
    return throwError(() => new ApiError(error.status, error.message));
  }

  const violation = body.fieldErrors?.[0];
  return throwError(
    () =>
      new ApiError(
        body.status ?? error.status,
        violation?.message ?? body.message ?? error.message,
        violation?.field,
      ),
  );
}

/**
 * Builds a query string, dropping null, undefined and empty values.
 *
 * An array becomes one repeated parameter per entry, which is how Spring
 * binds a `List<String>` request parameter. Collapsing it to a single
 * comma-joined value instead would arrive as one long string and match
 * nothing.
 */
export function toParams(values: Record<string, unknown>): HttpParams {
  let params = new HttpParams();

  for (const [key, value] of Object.entries(values)) {
    if (value === null || value === undefined || value === '') {
      continue;
    }

    if (Array.isArray(value)) {
      for (const entry of value) {
        if (entry !== null && entry !== undefined && entry !== '') {
          params = params.append(key, String(entry));
        }
      }
      continue;
    }

    params = params.set(key, String(value));
  }

  return params;
}

const ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
};

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => ESCAPES[character]);
}

/**
 * Converts an Elasticsearch highlight fragment into markup that is safe to
 * render.
 *
 * Elasticsearch wraps matches in `<em>` but does **not** escape the surrounding
 * text, and message bodies come from ingested email, so a message containing
 * markup would otherwise be injected verbatim into the page: the
 * `highlighted` pipe calls `bypassSecurityTrustHtml` and trusts whatever it is
 * given. The in-memory backend escapes before inserting its own tags, so this
 * keeps the HTTP path to the same guarantee.
 *
 * Everything outside the `<em>` markers is escaped; the markers themselves
 * become `<mark>`, which is what the UI styles.
 */
export function highlightToSafeMarkup(fragment: string | null | undefined): string {
  if (!fragment) {
    return '';
  }

  return fragment
    .split(/(<em>|<\/em>)/g)
    .map((part) => {
      if (part === '<em>') {
        return '<mark>';
      }
      if (part === '</em>') {
        return '</mark>';
      }
      return escapeHtml(part);
    })
    .join('');
}

/** Strips highlight markers, for contexts that want plain text. */
export function highlightToPlainText(fragment: string | null | undefined): string {
  return (fragment ?? '').replace(/<\/?em>/g, '');
}
