import { Observable } from 'rxjs';
import { Message } from '../models/message';
import { SavedSearch, SearchCriteria, SearchResponse, SearchStats } from '../models/search';

/**
 * Contract of the search service (`:8082`).
 *
 * Declared as an abstract class so it doubles as an Angular DI token: the app
 * injects `SearchApi` and the provider decides whether that resolves to the
 * in-memory mock or the HTTP client.
 */
export abstract class SearchApi {
  /** `GET /api/search` */
  abstract search(criteria: SearchCriteria): Observable<SearchResponse>;

  /** `GET /api/search/messages/{messageId}` */
  abstract getMessage(messageId: string): Observable<Message>;

  /** `GET /api/search/stats` */
  abstract stats(): Observable<SearchStats>;

  /** Every message in a thread, for the conversation view. */
  abstract getThread(threadId: string): Observable<Message[]>;

  /**
   * Resolves a criteria set to the full list of matching message IDs. Backs
   * "add all results to case" without paging through the UI.
   */
  abstract resolveAllIds(criteria: SearchCriteria): Observable<string[]>;

  abstract listSavedSearches(caseId: string): Observable<SavedSearch[]>;

  abstract saveSearch(caseId: string, name: string, criteria: SearchCriteria): Observable<SavedSearch>;

  abstract deleteSavedSearch(id: string): Observable<void>;
}
