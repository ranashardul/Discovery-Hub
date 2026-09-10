import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of, switchMap } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Message } from '../../models/message';
import {
  SavedSearch,
  SearchCriteria,
  SearchResponse,
  SearchResultItem,
  SearchStats,
} from '../../models/search';
import { ApiError } from '../api-error';
import { CaseApi } from '../case-api';
import { SearchApi } from '../search-api';
import { highlightToSafeMarkup, highlightToPlainText, toApiError, toParams } from './http-support';

/** Wire shape of a hit from GET /api/search. */
interface WireResultItem {
  messageId: string;
  score: number | null;
  communicationType: string;
  sender: string;
  recipients: string[];
  subject: string;
  snippet: string | null;
  threadId: string;
  messageTimestamp: string;
  attachmentCount: number;
  onHold: boolean;
  dispositionStatus: string;
}

interface WireSearchResponse {
  query: string | null;
  total: number;
  from: number;
  size: number;
  sort: string;
  tookMillis: number;
  results: WireResultItem[];
}

/** Wire shape of GET /api/search/messages/{id}: the indexed document. */
interface WireSearchDocument {
  messageId: string;
  deduplicationKey: string | null;
  externalMessageId: string | null;
  communicationType: string;
  sender: string;
  recipients: string[] | null;
  subject: string | null;
  body: string | null;
  threadId: string | null;
  messageTimestamp: string | null;
  attachmentCount: number;
  attachmentFilenames: string[] | null;
  indexedAt: string | null;
  holdCount: number;
  holdIds: string[] | null;
  dispositionStatus: string | null;
}

/** Wire shape of GET /api/search/ids. */
interface WireResolvedIds {
  total: number;
  truncated: boolean;
  messageIds: string[] | null;
}

/** Wire shape of a saved search, owned by the case service. */
interface WireSavedSearch {
  savedSearchId: string;
  caseId: string;
  name: string;
  criteria: Record<string, unknown> | null;
  createdBy: string;
  createdAt: string;
}

const ACTOR = 'discovery-hub-ui';

/** Matches the search service's default max-page-size. */
const THREAD_PAGE_SIZE = 100;

@Injectable()
export class HttpSearchApi extends SearchApi {
  private readonly http = inject(HttpClient);
  /** Saved searches are nested under a case, so resolving one needs the list. */
  private readonly cases = inject(CaseApi);

  private readonly base = environment.api.search;

  search(criteria: SearchCriteria): Observable<SearchResponse> {
    return this.http
      .get<WireSearchResponse>(this.base, { params: this.toQuery(criteria) })
      .pipe(map((response) => this.toSearchResponse(response)), catchError(toApiError));
  }

  getMessage(messageId: string): Observable<Message> {
    return this.http
      .get<WireSearchDocument>(`${this.base}/messages/${encodeURIComponent(messageId)}`)
      .pipe(map((document) => this.toMessage(document)), catchError(toApiError));
  }

  stats(): Observable<SearchStats> {
    return this.http.get<SearchStats>(`${this.base}/stats`).pipe(catchError(toApiError));
  }

  /**
   * Every message in a thread, oldest first so the conversation reads in
   * order.
   *
   * Relies on the search API accepting filters without `q`; a thread has no
   * text to match on.
   */
  getThread(threadId: string): Observable<Message[]> {
    // One page, because a conversation is not paged in the UI and the service
    // caps the page size anyway. A thread longer than that cap is truncated
    // rather than partially ordered.
    return this.search({ q: '', threadId, sort: 'oldest', size: THREAD_PAGE_SIZE }).pipe(
      switchMap((response) => {
        if (response.results.length === 0) {
          return of([]);
        }
        // A hit omits the body, so each document is fetched in full. Ingestion
        // has a batch endpoint, but its projection carries no hold state, and
        // showing a held message as unheld in the conversation view would be
        // worse than the extra round-trips. Threads are small.
        return forkJoin(response.results.map((item) => this.getMessage(item.messageId)));
      }),
    );
  }

  /**
   * One request rather than paging the archive through the browser.
   *
   * The service caps how many ids it will return and says when it had to
   * truncate. That is surfaced as an error instead of quietly scoping a case
   * to the first N matches — an incomplete evidence set that nobody knows is
   * incomplete is worse than a refusal.
   */
  resolveAllIds(criteria: SearchCriteria): Observable<string[]> {
    return this.http
      .get<WireResolvedIds>(`${this.base}/ids`, { params: this.toQuery(criteria) })
      .pipe(
        map((response) => {
          if (response.truncated) {
            throw new ApiError(
              400,
              `This search matches more than ${response.total} messages, which is the most ` +
                'that can be added at once. Narrow it and try again.',
            );
          }
          return response.messageIds ?? [];
        }),
        catchError(toApiError),
      );
  }

  /**
   * Saved searches are owned by the case service, not this one: a saved search
   * is a case artefact and is removed with the case. The contract groups it
   * with search because that is where a reviewer uses it.
   */
  listSavedSearches(caseId: string): Observable<SavedSearch[]> {
    return this.http
      .get<WireSavedSearch[]>(`${this.savedSearchBase(caseId)}`)
      .pipe(map((items) => items.map((item) => this.toSavedSearch(item))), catchError(toApiError));
  }

  saveSearch(caseId: string, name: string, criteria: SearchCriteria): Observable<SavedSearch> {
    return this.http
      .post<WireSavedSearch>(this.savedSearchBase(caseId), {
        name,
        criteria: this.toStoredCriteria(criteria),
        createdBy: ACTOR,
      })
      .pipe(map((item) => this.toSavedSearch(item)), catchError(toApiError));
  }

  /**
   * The delete route is nested under the case, so the id alone is not enough.
   * The saved search carries its case id, so it is looked up first rather than
   * changing a contract the components depend on.
   */
  deleteSavedSearch(id: string): Observable<void> {
    return this.savedSearchCaseId(id).pipe(
      switchMap((caseId) =>
        this.http.delete<void>(`${this.savedSearchBase(caseId)}/${encodeURIComponent(id)}`),
      ),
      catchError(toApiError),
    );
  }

  private savedSearchBase(caseId: string): string {
    return `${environment.api.caseHold}/cases/${encodeURIComponent(caseId)}/saved-searches`;
  }

  private savedSearchCaseId(id: string): Observable<string> {
    return this.cases.listCases().pipe(
      switchMap((cases) =>
        forkJoin(
          cases.length === 0
            ? [of<WireSavedSearch[]>([])]
            : cases.map((item) =>
                this.http
                  .get<WireSavedSearch[]>(this.savedSearchBase(item.id))
                  .pipe(catchError(() => of<WireSavedSearch[]>([]))),
              ),
        ),
      ),
      map((perCase) => {
        const match = perCase.flat().find((item) => item.savedSearchId === id);
        if (!match) {
          throw new ApiError(404, 'That saved search no longer exists');
        }
        return match.caseId;
      }),
    );
  }

  private toSavedSearch(item: WireSavedSearch): SavedSearch {
    return {
      id: item.savedSearchId,
      caseId: item.caseId,
      name: item.name,
      criteria: { q: '', ...(item.criteria ?? {}) } as SearchCriteria,
      createdAt: item.createdAt,
      // The service stores the criteria, not a run history.
      lastRunAt: null,
      lastRunTotal: null,
    };
  }

  /** Drops empty values so a stored criteria set has no meaningless keys. */
  private toStoredCriteria(criteria: SearchCriteria): Record<string, unknown> {
    return Object.fromEntries(
      Object.entries(criteria).filter(
        ([, value]) => value !== null && value !== undefined && value !== '',
      ),
    );
  }

  private toQuery(criteria: SearchCriteria) {
    return toParams({
      q: criteria.q,
      communicationType: criteria.communicationType,
      sender: criteria.sender,
      recipient: criteria.recipient,
      threadId: criteria.threadId,
      dispositionStatus: criteria.dispositionStatus,
      onHold: criteria.onHold,
      hasAttachments: criteria.hasAttachments,
      after: criteria.after,
      before: criteria.before,
      sort: criteria.sort,
      from: criteria.from,
      size: criteria.size,
    });
  }

  private toSearchResponse(response: WireSearchResponse): SearchResponse {
    return {
      query: response.query ?? '',
      total: response.total,
      from: response.from,
      size: response.size,
      sort: (response.sort as SearchResponse['sort']) ?? 'relevance',
      tookMillis: response.tookMillis,
      results: response.results.map((item) => this.toResultItem(item)),
    };
  }

  private toResultItem(item: WireResultItem): SearchResultItem {
    return {
      messageId: item.messageId,
      // Elasticsearch does not score when sorting by a field, so a sorted
      // search returns null here.
      score: item.score ?? 0,
      communicationType: item.communicationType as SearchResultItem['communicationType'],
      sender: item.sender,
      recipients: item.recipients ?? [],
      subject: item.subject,
      snippet: highlightToPlainText(item.snippet),
      // The API returns one fragment covering subject or body, not the two
      // separate fields the prototype produced.
      highlights: { body: highlightToSafeMarkup(item.snippet) },
      threadId: item.threadId,
      messageTimestamp: item.messageTimestamp,
      attachmentCount: item.attachmentCount,
      onHold: item.onHold,
      dispositionStatus: item.dispositionStatus as SearchResultItem['dispositionStatus'],
    };
  }

  /**
   * The indexed document carries what search needs, which is not everything a
   * message has: attachments are reduced to filenames, so the detail view
   * shows names without sizes or checksums.
   */
  private toMessage(document: WireSearchDocument): Message {
    return {
      id: document.messageId,
      externalMessageId: document.externalMessageId ?? '',
      communicationType: document.communicationType as Message['communicationType'],
      sender: document.sender,
      recipients: document.recipients ?? [],
      subject: document.subject ?? '',
      body: document.body ?? '',
      messageTimestamp: document.messageTimestamp ?? '',
      threadId: document.threadId ?? '',
      attachments: (document.attachmentFilenames ?? []).map((filename, index) => ({
        attachmentId: `${document.messageId}-${index}`,
        filename,
        contentType: '',
        sizeBytes: 0,
        sha256: '',
      })),
      createdAt: document.indexedAt ?? '',
      holdCount: document.holdCount ?? 0,
      holdIds: document.holdIds ?? [],
      dispositionStatus: (document.dispositionStatus ?? 'ACTIVE') as Message['dispositionStatus'],
    };
  }

}
