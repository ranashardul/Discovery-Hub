/** Communication types the archive can hold. Mirrors FR-1. */
export type CommunicationType = 'EMAIL' | 'CHAT';

/**
 * Retention/disposition state projected onto a message.
 *
 * `ACTIVE` and `ON_HOLD` are the only two the services ever write: ingestion
 * derives them from the hold set in the same update that maintains
 * `holdCount`. `PAST_RETENTION` and `DISPOSED` exist for the in-memory
 * prototype, which models the disposition lifecycle the services handle by
 * deleting the message outright — a disposed message is gone, not flagged.
 */
export type DispositionStatus = 'ACTIVE' | 'ON_HOLD' | 'PAST_RETENTION' | 'DISPOSED';

export interface AttachmentMetadata {
  attachmentId: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
}

/**
 * A single archived communication. `id` is the immutable message ID assigned by
 * the ingestion worker; nothing in the UI ever mints one.
 */
export interface Message {
  id: string;
  externalMessageId: string;
  communicationType: CommunicationType;
  sender: string;
  recipients: string[];
  subject: string;
  body: string;
  messageTimestamp: string;
  threadId: string;
  attachments: AttachmentMetadata[];
  createdAt: string;
  /** Number of active holds covering this message. Zero means deletable. */
  holdCount: number;
  /** Ids of the holds covering this message, projected from case-hold events. */
  holdIds?: string[];
  dispositionStatus: DispositionStatus;
}

/** A person whose communications can be brought into scope of a case. */
export interface Custodian {
  id: string;
  displayName: string;
  email: string;
  department: string;
  title: string;
  messageCount: number;
}
