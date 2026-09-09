import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, expand, forkJoin, map, of, reduce, switchMap } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { MockSearchApi } from '../../mock/mock-apis';
import { Message } from '../../models/message';
import {
  SavedSearch,
  SearchCriteria,
  SearchResponse,
  SearchResultItem,
  SearchStats,
} from '../../models/search';
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

/** Page size used when walking every match for "add all results to case". */
const RESOLVE_PAGE_SIZE = 100;

@Injectable()
export class HttpSearchApi extends SearchApi {
  private readonly http = inject(HttpClient);
  /** Saved searches have no backend; see environment.mockBacked. */
  private readonly fallback = inject(MockSearchApi);

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
    return this.searchAll({ q: '', threadId, sort: 'oldest' }).pipe(
      switchMap((items) => {
        if (items.length === 0) {
          return of([]);
        }
        // A hit omits the body, so each document is fetched in full. Ingestion
        // has a batch endpoint, but its projection carries no hold state, and
        // showing a held message as unheld in the conversation view would be
        // worse than the extra round-trips. Threads are small.
        return forkJoin(items.map((item) => this.getMessage(item.messageId)));
      }),
    );
  }

  resolveAllIds(criteria: SearchCriteria): Observable<string[]> {
    return this.searchAll(criteria).pipe(map((items) => items.map((item) => item.messageId)));
  }

  listSavedSearches(caseId: string): Observable<SavedSearch[]> {
    return this.fallback.listSavedSearches(caseId);
  }

  saveSearch(caseId: string, name: string, criteria: SearchCriteria): Observable<SavedSearch> {
    return this.fallback.saveSearch(caseId, name, criteria);
  }

  deleteSavedSearch(id: string): Observable<void> {
    return this.fallback.deleteSavedSearch(id);
  }

  /** Pages through every match, following `total` rather than guessing. */
  private searchAll(criteria: SearchCriteria): Observable<SearchResultItem[]> {
    const page = (from: number) =>
      this.search({ ...criteria, from, size: RESOLVE_PAGE_SIZE });

    return page(0).pipe(
      expand((response) => {
        const next = response.from + response.results.length;
        return next >= response.total || response.results.length === 0 ? [] : page(next);
      }),
      reduce((all: SearchResultItem[], response) => all.concat(response.results), []),
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
