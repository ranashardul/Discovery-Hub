import { Observable } from 'rxjs';
import { CommunicationType } from '../models/message';
import {
  DashboardCounts,
  DispositionRun,
  RetentionPolicy,
  ServiceStatus,
} from '../models/retention';

/**
 * Cross-cutting reads that the dashboard and the retention screen need. In a
 * real deployment these fan out to several services' `/stats` and
 * `/actuator/health` endpoints; keeping them behind one contract stops every
 * page from knowing the service topology.
 */
export abstract class PlatformApi {
  abstract counts(): Observable<DashboardCounts>;

  abstract serviceStatus(): Observable<ServiceStatus[]>;

  abstract retentionPolicies(): Observable<RetentionPolicy[]>;

  abstract updateRetentionPolicy(
    communicationType: CommunicationType,
    retentionMinutes: number,
  ): Observable<RetentionPolicy>;

  abstract dispositionRuns(): Observable<DispositionRun[]>;

  /** Kicks the disposition job by hand so a demo does not wait on the scheduler. */
  abstract runDisposition(): Observable<DispositionRun>;
}
