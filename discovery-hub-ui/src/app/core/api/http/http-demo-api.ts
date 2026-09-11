import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  DemoIngestAcceptance,
  DemoMessageDraft,
  DemoRetentionStatus,
  DemoStorageProof,
} from '../../models/demo';
import { DemoApi } from '../demo-api';
import { toApiError } from './http-support';

/** Wire shape of POST /api/ingestion/messages. */
interface WireAcceptance {
  requestId: string;
  deduplicationKey: string;
  status: string;
  duplicate: boolean;
  messageId: string | null;
}

interface WireRequestStatus {
  requestId: string;
  messageId: string | null;
  status: string;
  lastError: string | null;
}

/** As above, minus the derived flag the UI computes. */
type WireStorageProof = Omit<DemoStorageProof, 'fullyDisposed'>;

@Injectable()
export class HttpDemoApi extends DemoApi {
  private readonly http = inject(HttpClient);
  private readonly base = environment.api.ingestion;

  ingest(draft: DemoMessageDraft): Observable<DemoIngestAcceptance> {
    return this.http
      .post<WireAcceptance>(`${this.base}/messages`, {
        communicationType: draft.communicationType,
        sender: draft.sender,
        recipients: draft.recipients,
        subject: draft.subject,
        body: draft.body,
        messageTimestamp: draft.messageTimestamp,
        threadId: draft.threadId,
        externalMessageId: draft.externalMessageId,
        retentionMinutes: draft.retentionMinutes,
        attachments: draft.attachments.map((attachment) => ({
          filename: attachment.filename,
          contentType: attachment.contentType,
          contentBase64: attachment.contentBase64,
        })),
      })
      .pipe(catchError(toApiError));
  }

  resolveMessageId(requestId: string): Observable<string | null> {
    return this.http
      .get<WireRequestStatus>(`${this.base}/requests/${encodeURIComponent(requestId)}`)
      .pipe(
        map((status) => status.messageId ?? null),
        // The request document is written before the event is published, but
        // a poll can still race it. An absent request is "not yet", not a
        // failure, so the caller keeps waiting instead of showing an error.
        catchError((error: unknown) =>
          error instanceof HttpErrorResponse && error.status === 404
            ? of(null)
            : toApiError(error),
        ),
      );
  }

  /**
   * Null once the message is gone, which is the expected end state here
   * rather than an error: the countdown reaching zero and the document being
   * deleted is the thing being demonstrated.
   */
  retentionStatus(messageId: string): Observable<DemoRetentionStatus | null> {
    return this.http
      .get<DemoRetentionStatus>(`${this.base}/messages/${encodeURIComponent(messageId)}/retention`)
      .pipe(
        map((status) => status),
        catchError((error: unknown) =>
          error instanceof HttpErrorResponse && error.status === 404
            ? of(null)
            : toApiError(error),
        ),
      );
  }

  storageProof(messageId: string): Observable<DemoStorageProof> {
    return this.http
      .get<WireStorageProof>(`${this.base}/messages/${encodeURIComponent(messageId)}/storage`)
      .pipe(
        map((proof) => ({
          ...proof,
          objects: proof.objects ?? [],
          // Derived here rather than read off the wire: Jackson serialises a
          // record's components, not its other methods, so a `fullyDisposed`
          // helper on the response would arrive as undefined and read as
          // "not disposed" for every message.
          fullyDisposed:
            !proof.messagePresent && (proof.objects ?? []).every((object) => !object.present),
        })),
        catchError(toApiError),
      );
  }
}
