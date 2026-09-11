import { CommunicationType, DispositionStatus } from './message';

/** An attachment as the ingestion API accepts it: base64, not multipart. */
export interface DemoAttachmentDraft {
  filename: string;
  contentType: string;
  contentBase64: string;
  sizeBytes: number;
}

/**
 * A message to push through the real ingestion pipeline, with a retention
 * period measured in minutes.
 *
 * Every field the stored document carries is settable, because the point of
 * the screen is to watch a specific message through its whole lifecycle, and
 * a demo where the operator cannot say who sent what proves less.
 */
export interface DemoMessageDraft {
  communicationType: CommunicationType;
  sender: string;
  recipients: string[];
  subject: string;
  body: string;
  messageTimestamp: string;
  threadId: string | null;
  externalMessageId: string | null;
  /** 1 to 5; the service refuses anything longer. */
  retentionMinutes: number;
  attachments: DemoAttachmentDraft[];
}

/** Acceptance of an ingestion request. The message id arrives later. */
export interface DemoIngestAcceptance {
  requestId: string;
  deduplicationKey: string;
  status: string;
  duplicate: boolean;
  messageId: string | null;
}

/** Retention countdown and hold state for one message. */
export interface DemoRetentionStatus {
  messageId: string;
  communicationType: string;
  createdAt: string;
  retentionUntil: string;
  secondsUntilExpiry: number;
  retentionExpired: boolean;
  held: boolean;
  holdCount: number;
  dispositionStatus: DispositionStatus | string;
}

/**
 * Whether the binaries are still in object storage.
 *
 * Answerable after the message is gone, because the keys come from the
 * disposition audit — which is why disposition can be shown as complete
 * rather than merely claimed.
 */
export interface DemoStorageProof {
  messageId: string;
  messagePresent: boolean;
  source: 'MESSAGE' | 'DISPOSITION_AUDIT' | 'UNKNOWN';
  bucket: string;
  disposedAt: string | null;
  objects: { key: string; present: boolean }[];
  fullyDisposed: boolean;
}
