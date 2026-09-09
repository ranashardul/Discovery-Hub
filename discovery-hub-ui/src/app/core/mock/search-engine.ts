import { Message } from '../models/message';
import { SearchCriteria, SearchResultItem, SearchSort } from '../models/search';
import { Corpus } from './corpus';

export interface EngineHit {
  index: number;
  score: number;
}

const STOP_WORDS = new Set(['the', 'a', 'an', 'and', 'or', 'of', 'to', 'in', 'for', 'on']);

/** Splits a query into bare terms and quoted phrases. */
export function parseQuery(query: string): string[] {
  const terms: string[] = [];
  const pattern = /"([^"]+)"|(\S+)/g;
  let match: RegExpExecArray | null;

  while ((match = pattern.exec(query)) !== null) {
    const term = (match[1] ?? match[2]).toLowerCase().trim();
    if (term.length > 1 && !STOP_WORDS.has(term)) {
      terms.push(term);
    }
  }

  return terms;
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Wraps every occurrence of the terms in `<mark>`. The input is escaped first,
 * so the only markup in the result is the highlighting itself.
 */
export function highlight(text: string, terms: string[]): string {
  const escaped = escapeHtml(text);
  if (terms.length === 0) {
    return escaped;
  }
  const pattern = new RegExp(`(${terms.map(escapeRegExp).join('|')})`, 'gi');
  return escaped.replace(pattern, '<mark>$1</mark>');
}

/**
 * A window of body text around the first matching term, highlighted. Keeps the
 * result list readable when a body runs to several hundred characters.
 */
export function buildSnippet(body: string, terms: string[], window = 220): string {
  const lower = body.toLowerCase();
  let position = -1;

  for (const term of terms) {
    const found = lower.indexOf(term);
    if (found >= 0 && (position < 0 || found < position)) {
      position = found;
    }
  }

  if (position < 0) {
    const head = body.slice(0, window);
    return highlight(body.length > window ? `${head}…` : head, terms);
  }

  const start = Math.max(0, position - Math.floor(window / 3));
  const end = Math.min(body.length, start + window);
  const fragment = `${start > 0 ? '…' : ''}${body.slice(start, end)}${end < body.length ? '…' : ''}`;

  return highlight(fragment, terms);
}

function matchesFilters(message: Message, criteria: SearchCriteria): boolean {
  if (message.dispositionStatus === 'DISPOSED') {
    return false;
  }
  if (criteria.communicationType && message.communicationType !== criteria.communicationType) {
    return false;
  }
  if (criteria.threadId && message.threadId !== criteria.threadId) {
    return false;
  }
  if (criteria.dispositionStatus && message.dispositionStatus !== criteria.dispositionStatus) {
    return false;
  }
  if (criteria.sender && !message.sender.toLowerCase().includes(criteria.sender.toLowerCase())) {
    return false;
  }
  if (criteria.recipient) {
    const needle = criteria.recipient.toLowerCase();
    if (!message.recipients.some((recipient) => recipient.toLowerCase().includes(needle))) {
      return false;
    }
  }
  if (criteria.onHold !== null && criteria.onHold !== undefined) {
    if (criteria.onHold !== message.holdCount > 0) {
      return false;
    }
  }
  if (criteria.hasAttachments !== null && criteria.hasAttachments !== undefined) {
    if (criteria.hasAttachments !== message.attachments.length > 0) {
      return false;
    }
  }

  const timestamp = Date.parse(message.messageTimestamp);
  if (criteria.after && timestamp < Date.parse(criteria.after)) {
    return false;
  }
  if (criteria.before && timestamp > Date.parse(criteria.before)) {
    return false;
  }

  return true;
}

function countOccurrences(haystack: string, needle: string): number {
  let count = 0;
  let index = haystack.indexOf(needle);
  while (index >= 0) {
    count++;
    index = haystack.indexOf(needle, index + needle.length);
  }
  return count;
}

/**
 * Scores a document against the terms. Deliberately simple: term frequency
 * with a subject boost. Elasticsearch does the real thing with BM25 — this only
 * has to rank plausibly for a prototype.
 */
function score(corpus: Corpus, index: number, terms: string[]): number {
  if (terms.length === 0) {
    return 1;
  }

  const haystack = corpus.searchText[index];
  const subject = corpus.messages[index].subject.toLowerCase();
  let total = 0;
  let matchedTerms = 0;

  for (const term of terms) {
    const occurrences = countOccurrences(haystack, term);
    if (occurrences === 0) {
      continue;
    }
    matchedTerms++;
    total += 1 + Math.log(occurrences);
    if (subject.includes(term)) {
      total += 1.6;
    }
  }

  if (matchedTerms === 0) {
    return 0;
  }

  // Documents matching more of the query outrank ones that match a single term
  // many times.
  return total * (matchedTerms / terms.length);
}

/** All hits for a criteria set, ordered per `sort`, before pagination. */
export function executeSearch(corpus: Corpus, criteria: SearchCriteria): EngineHit[] {
  const terms = parseQuery(criteria.q ?? '');
  const hits: EngineHit[] = [];

  for (let index = 0; index < corpus.messages.length; index++) {
    const message = corpus.messages[index];
    if (!matchesFilters(message, criteria)) {
      continue;
    }
    const relevance = score(corpus, index, terms);
    if (relevance <= 0) {
      continue;
    }
    hits.push({ index, score: relevance });
  }

  return sortHits(corpus, hits, criteria.sort ?? 'relevance');
}

function sortHits(corpus: Corpus, hits: EngineHit[], sort: SearchSort): EngineHit[] {
  const timestampOf = (hit: EngineHit) => Date.parse(corpus.messages[hit.index].messageTimestamp);

  return hits.sort((left, right) => {
    if (sort === 'newest') {
      return timestampOf(right) - timestampOf(left);
    }
    if (sort === 'oldest') {
      return timestampOf(left) - timestampOf(right);
    }
    return right.score - left.score || timestampOf(right) - timestampOf(left);
  });
}

export function toResultItem(message: Message, relevance: number, terms: string[]): SearchResultItem {
  return {
    messageId: message.id,
    score: Math.round(relevance * 1000) / 1000,
    communicationType: message.communicationType,
    sender: message.sender,
    recipients: message.recipients,
    subject: message.subject,
    snippet: buildSnippet(message.body, terms),
    highlights: {
      subject: highlight(message.subject, terms),
      body: buildSnippet(message.body, terms),
    },
    threadId: message.threadId,
    messageTimestamp: message.messageTimestamp,
    attachmentCount: message.attachments.length,
    onHold: message.holdCount > 0,
    dispositionStatus: message.dispositionStatus,
  };
}
