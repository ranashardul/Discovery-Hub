import { Observable } from 'rxjs';
import { CoverageReport } from '../models/coverage';

/**
 * Reads the aggregated test-coverage report.
 *
 * Behind a contract like every other capability, so the page never knows
 * whether the numbers came from a generated file or from somewhere else. The
 * HTTP implementation fetches a static asset; no service exposes coverage and
 * none had to be changed to add this screen.
 */
export abstract class CoverageApi {
  abstract report(): Observable<CoverageReport>;
}
