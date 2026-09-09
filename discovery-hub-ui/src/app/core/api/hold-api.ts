import { Observable } from 'rxjs';
import { DeletionAttemptResult, HoldScope, LegalHold, PlaceHoldRequest } from '../models/hold';

/** Contract of the case & hold service, hold half. */
export abstract class HoldApi {
  abstract listHolds(caseId?: string | null): Observable<LegalHold[]>;

  abstract getHold(id: string): Observable<LegalHold>;

  /** Returns immediately with a PROPAGATING hold; the worker fills in the scope. */
  abstract placeHold(request: PlaceHoldRequest): Observable<LegalHold>;

  abstract releaseHold(id: string, releasedBy: string): Observable<LegalHold>;

  /** Dry run: how many messages the scope would cover, before committing. */
  abstract previewScope(scope: HoldScope): Observable<number>;

  /**
   * Asks the platform to delete a message. Held messages are refused, which is
   * the demonstrable proof required by FR-4.6.
   */
  abstract attemptDelete(messageId: string): Observable<DeletionAttemptResult>;
}
