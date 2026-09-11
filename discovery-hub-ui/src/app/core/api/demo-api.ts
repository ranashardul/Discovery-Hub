import { Observable } from 'rxjs';
import {
  DemoIngestAcceptance,
  DemoMessageDraft,
  DemoRetentionStatus,
  DemoStorageProof,
} from '../models/demo';

/**
 * Drives a retention and disposition demonstration against the real pipeline.
 *
 * <p>Nothing here is a shortcut around the services: {@link #ingest} posts to
 * the same ingestion endpoint the corpus generator uses, so the message
 * travels API → Kafka → worker → MongoDB → S3 like any other. The only
 * difference is that the request carries its own retention period, which is
 * what lets one message expire in minutes while the archive around it keeps
 * its seven-year policy.
 */
export abstract class DemoApi {
  /** Publishes the message for ingestion. Returns as soon as it is accepted. */
  abstract ingest(draft: DemoMessageDraft): Observable<DemoIngestAcceptance>;

  /**
   * Resolves the request to the message id the worker assigned.
   *
   * Separate from {@link #ingest} because ingestion is asynchronous: the id
   * does not exist yet when the request is accepted.
   */
  abstract resolveMessageId(requestId: string): Observable<string | null>;

  /** Retention countdown, or null once the message has been disposed. */
  abstract retentionStatus(messageId: string): Observable<DemoRetentionStatus | null>;

  /** Whether the document and its attachment objects still exist. */
  abstract storageProof(messageId: string): Observable<DemoStorageProof>;
}
