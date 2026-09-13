import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';
import { ApiError } from '../api-error';
import { CoverageReport } from '../../models/coverage';
import { CoverageApi } from '../coverage-api';

/**
 * Path of the generated report, served as a static asset from `public/`.
 *
 * Same origin as everything else: the gateway proxies `/` to the UI container,
 * whose nginx serves this file directly. No gateway route and no service
 * endpoint were added for it.
 */
const REPORT_URL = 'coverage-summary.json';

@Injectable()
export class HttpCoverageApi extends CoverageApi {
  private readonly http = inject(HttpClient);

  report(): Observable<CoverageReport> {
    // Cache-busted per request. The report is a static file whose contents
    // change while its URL does not, so a conditional request served from
    // cache is exactly the wrong outcome: the page would show a coverage
    // figure the reader has just regenerated and believes is current.
    return this.http.get<CoverageReport>(REPORT_URL, { params: { t: Date.now() } }).pipe(
      // Any failure here means the same thing in practice: the report has not
      // been generated. Saying how to produce it is more useful than relaying
      // a 404 from a static file fetch.
      catchError(() =>
        throwError(() =>
          ApiError.notFound(
            'No coverage report found. Generate one with ' +
              '`cd infrastructure && ./coverage-report.sh`, then reload this page.',
          ),
        ),
      ),
    );
  }
}
