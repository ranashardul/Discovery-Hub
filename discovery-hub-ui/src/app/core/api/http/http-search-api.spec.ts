import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { environment } from '../../../../environments/environment';
import { CaseApi } from '../case-api';
import { SearchCriteria, SearchResponse } from '../../models/search';
import { HttpSearchApi } from './http-search-api';

const emptyPage = {
  query: null,
  total: 0,
  from: 0,
  size: 20,
  sort: 'newest',
  tookMillis: 1,
  results: [],
};

/**
 * The mock backend produces well-formed highlights of its own, so a page spec
 * running against it passes whatever this client does with the real wire
 * shape. These cover the translation itself: a hit is only as good as the
 * fields this maps out of it, and dropping one is invisible — the row still
 * renders, just without a title or without the marks.
 */
describe('HttpSearchApi', () => {
  let api: HttpSearchApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        HttpSearchApi,
        // Only needed to resolve a saved search back to its case; unused here.
        { provide: CaseApi, useValue: {} },
      ],
    });

    api = TestBed.inject(HttpSearchApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function hit(overrides: Record<string, unknown> = {}) {
    return {
      messageId: 'm-1',
      score: 1.5,
      communicationType: 'EMAIL',
      sender: 'imogen.reddy@example-bank.test',
      recipients: ['kabir.rahman@example-bank.test'],
      subject: 'Cut-off breach: SWIFT MT103',
      subjectHighlight: 'Cut-off <em>breach</em>: SWIFT MT103',
      snippet: 'A SWIFT MT103 batch failed and the <em>breach</em> was reported',
      threadId: 'thread-1',
      messageTimestamp: '2026-09-08T00:00:00Z',
      attachmentCount: 0,
      onHold: false,
      dispositionStatus: 'ACTIVE',
      ...overrides,
    };
  }

  /** Runs a search, answers it with `results`, and returns the mapped hits. */
  function search(results: Record<string, unknown>[], criteria: SearchCriteria = { q: 'breach' }) {
    let response: SearchResponse | undefined;
    api.search(criteria).subscribe((value) => (response = value));

    expectSearch().flush({
      query: criteria.q || null,
      total: results.length,
      from: 0,
      size: 20,
      sort: 'relevance',
      tookMillis: 3,
      results,
    });

    return response!.results;
  }

  function expectSearch(): TestRequest {
    return http.expectOne((candidate) => candidate.url === environment.api.search);
  }

  it('keeps the highlight markers for the subject and the body', () => {
    const item = search([hit()])[0];

    // The template renders both through `highlighted`, which trusts its
    // input, so they have to arrive as <mark> rather than as the <em> the
    // service emits or as plain text.
    expect(item.highlights.subject).toBe('Cut-off <mark>breach</mark>: SWIFT MT103');
    expect(item.highlights.body).toContain('<mark>breach</mark>');
    // The plain form stays plain, for anything that is not rendered as HTML.
    expect(item.snippet).not.toContain('<mark>');
  });

  it('falls back to the plain subject when the subject did not match', () => {
    const item = search([hit({ subjectHighlight: null })])[0];

    // A blank title is what happened before: the client never populated
    // highlights.subject at all, and the template renders the title from it.
    expect(item.highlights.subject).toBe('Cut-off breach: SWIFT MT103');
  });

  it('escapes markup in a subject it did not highlight', () => {
    const item = search([hit({ subject: 'Re: <script>alert(1)</script>', subjectHighlight: null })])[0];

    // Archived mail reaches bypassSecurityTrustHtml through this field.
    expect(item.highlights.subject).not.toContain('<script>');
    expect(item.highlights.subject).toContain('&lt;script&gt;');
  });

  it('sends each custodian as a repeated participant parameter', () => {
    api
      .search({ q: '', participants: ['a@example.test', 'b@example.test'] })
      .subscribe({ error: () => undefined });

    const request = expectSearch();

    // Spring binds a List<String> from repeated keys. One comma-joined value
    // would arrive as a single identity and match nothing.
    expect(request.request.params.getAll('participant')).toEqual([
      'a@example.test',
      'b@example.test',
    ]);
    request.flush(emptyPage);
  });

  it('omits filters that are not set', () => {
    api.search({ q: 'breach' }).subscribe({ error: () => undefined });

    const request = expectSearch();

    expect(request.request.params.has('sender')).toBe(false);
    expect(request.request.params.has('participant')).toBe(false);
    expect(request.request.params.get('q')).toBe('breach');
    request.flush(emptyPage);
  });
});
