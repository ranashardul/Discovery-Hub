import { Observable } from 'rxjs';
import { AuditEntry, AuditPage, AuditQuery } from '../models/audit';

/** Contract of the audit service. Read and append only, by design. */
export abstract class AuditApi {
  abstract query(query: AuditQuery): Observable<AuditPage>;

  abstract getEntry(id: string): Observable<AuditEntry>;

  /** Distinct actors, for the filter dropdown. */
  abstract listActors(): Observable<string[]>;
}
