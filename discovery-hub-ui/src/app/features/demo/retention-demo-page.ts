import { ChangeDetectionStrategy, Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { describeError } from '../../core/api/api-error';
import { DemoApi } from '../../core/api/demo-api';
import { PlatformApi } from '../../core/api/platform-api';
import {
  DemoAttachmentDraft,
  DemoRetentionStatus,
  DemoStorageProof,
} from '../../core/models/demo';
import { CommunicationType } from '../../core/models/message';
import { ToastService } from '../../shared/notifications/toast.service';
import { Empty, ErrorBand, Loading } from '../../shared/ui/state-blocks';
import { StatusChip } from '../../shared/ui/status-chip';

/** Stages of the lifecycle this screen walks through, in order. */
type Stage = 'DRAFT' | 'ACCEPTED' | 'STORED' | 'EXPIRED' | 'DISPOSED';

const POLL_MS = 2000;

/** The ceiling the ingestion service enforces on a per-message period. */
const MAX_RETENTION_MINUTES = 5;

/** Largest attachment the demo will base64 into a JSON request. */
const MAX_ATTACHMENT_BYTES = 2 * 1024 * 1024;

/**
 * Demonstrates retention and disposition (FR-5) on a single message, end to
 * end, in the time available in a presentation.
 *
 * <p>The message goes through the real pipeline — the same
 * {@code POST /api/ingestion/messages} the corpus generator uses, so it is
 * published to Kafka, stored by the worker, and its attachment uploaded to
 * S3. What differs is that the request carries its own retention period, of
 * one to five minutes.
 *
 * <p>That per-message period is the whole reason this screen exists. The
 * other way to make a disposition run delete something soon is to shorten the
 * period for {@code EMAIL}, which applies to every email already archived: on
 * the next run the corpus goes with it. A period attached to one message
 * cannot touch anything ingested before it.
 *
 * <p>Disposition is not simulated here. The scheduled job deletes the
 * document and purges the objects; this screen polls until both are gone and
 * says which store each answer came from.
 */
@Component({
  selector: 'app-retention-demo-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    DatePipe,
    DecimalPipe,
    StatusChip,
    Loading,
    Empty,
    ErrorBand,
  ],
  templateUrl: './retention-demo-page.html',
})
export class RetentionDemoPage implements OnDestroy {
  private readonly demoApi = inject(DemoApi);
  private readonly platform = inject(PlatformApi);
  private readonly toast = inject(ToastService);
  private readonly fb = inject(FormBuilder);

  protected readonly maxRetentionMinutes = MAX_RETENTION_MINUTES;
  protected readonly retentionChoices = [1, 2, 3, 4, 5];
  protected readonly types: CommunicationType[] = ['EMAIL', 'CHAT'];

  /**
   * Every field the stored document carries, so the operator can narrate a
   * specific message rather than an anonymous one. Prefilled with something
   * plausible: a demo should not begin by typing an email body.
   */
  protected readonly form = this.fb.nonNullable.group({
    communicationType: 'EMAIL' as CommunicationType,
    sender: ['retention.demo@example-bank.test', [Validators.required, Validators.email]],
    recipients: [
      'compliance.reviewer@example-bank.test',
      [Validators.required],
    ],
    subject: ['Retention demonstration — disposition due shortly', [Validators.required]],
    body: [
      'This message exists to demonstrate retention and disposition.\n\n'
        + 'It carries its own retention period, so it expires on its own without\n'
        + 'changing the policy for anything else in the archive.',
      [Validators.required],
    ],
    threadId: 'thread-retention-demo',
    retentionMinutes: 1,
  });

  protected readonly attachment = signal<DemoAttachmentDraft | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly requestId = signal<string | null>(null);
  protected readonly messageId = signal<string | null>(null);
  protected readonly status = signal<DemoRetentionStatus | null>(null);
  protected readonly proof = signal<DemoStorageProof | null>(null);
  protected readonly ingestedAt = signal<Date | null>(null);
  protected readonly disposedAt = signal<Date | null>(null);

  private timer: ReturnType<typeof setInterval> | null = null;

  protected readonly stage = computed<Stage>(() => {
    if (this.proof()?.fullyDisposed) {
      return 'DISPOSED';
    }
    const status = this.status();
    if (status) {
      return status.retentionExpired ? 'EXPIRED' : 'STORED';
    }
    return this.requestId() ? 'ACCEPTED' : 'DRAFT';
  });

  /** Seconds left, floored at zero, or null before the message is stored. */
  protected readonly secondsRemaining = computed(() => {
    const status = this.status();
    return status ? Math.max(0, status.secondsUntilExpiry) : null;
  });

  protected readonly attachmentsPurged = computed(() => {
    const proof = this.proof();
    if (!proof) {
      return null;
    }
    return {
      total: proof.objects.length,
      remaining: proof.objects.filter((object) => object.present).length,
    };
  });

  ngOnDestroy(): void {
    this.stopPolling();
  }

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      this.attachment.set(null);
      return;
    }

    if (file.size > MAX_ATTACHMENT_BYTES) {
      this.toast.error(
        `That file is ${Math.round(file.size / 1024)} kB. The demo caps attachments at `
          + `${MAX_ATTACHMENT_BYTES / 1024 / 1024} MB because the request carries the bytes inline as base64.`,
      );
      input.value = '';
      return;
    }

    const reader = new FileReader();
    reader.onload = () => {
      // readAsDataURL gives "data:<type>;base64,<payload>"; the service wants
      // the payload alone.
      const result = String(reader.result ?? '');
      const payload = result.slice(result.indexOf(',') + 1);
      this.attachment.set({
        filename: file.name,
        contentType: file.type || 'application/octet-stream',
        contentBase64: payload,
        sizeBytes: file.size,
      });
    };
    reader.onerror = () => this.toast.error('Could not read that file');
    reader.readAsDataURL(file);
  }

  protected clearAttachment(): void {
    this.attachment.set(null);
  }

  protected async submit(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const value = this.form.getRawValue();
    const recipients = value.recipients
      .split(/[,;\s]+/)
      .map((recipient) => recipient.trim())
      .filter(Boolean);

    if (recipients.length === 0) {
      this.toast.error('At least one recipient is required');
      return;
    }

    this.reset();
    this.busy.set(true);

    try {
      const attachment = this.attachment();
      const acceptance = await firstValueFrom(
        this.demoApi.ingest({
          communicationType: value.communicationType,
          sender: value.sender.trim(),
          recipients,
          subject: value.subject.trim(),
          body: value.body,
          messageTimestamp: new Date().toISOString(),
          threadId: value.threadId.trim() || null,
          // Ingestion is idempotent on message content, so two identical
          // demo runs would resolve to the first message and its expired
          // retention. A unique source id per run keeps each one distinct.
          externalMessageId: `retention-demo-${Date.now()}`,
          retentionMinutes: value.retentionMinutes,
          attachments: attachment ? [attachment] : [],
        }),
      );

      this.requestId.set(acceptance.requestId);
      this.ingestedAt.set(new Date());
      this.toast.success('Accepted for ingestion — published to Kafka');
      this.startPolling();
    } catch (error) {
      this.error.set(describeError(error));
    } finally {
      this.busy.set(false);
    }
  }

  /**
   * Runs a disposition pass now instead of waiting for the scheduler.
   *
   * The job is on a timer, so a one-minute retention can still sit expired
   * for most of another interval. This only triggers the same pass the
   * scheduler runs: it does not delete anything the policy would not.
   */
  protected async disposeNow(): Promise<void> {
    this.busy.set(true);
    try {
      const run = await firstValueFrom(this.platform.runDisposition());
      this.toast.success(
        `Disposition run: ${run.deleted} deleted, ${run.skippedOnHold} skipped on hold`,
      );
      await this.pollOnce();
    } catch (error) {
      this.toast.error(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected reset(): void {
    this.stopPolling();
    this.requestId.set(null);
    this.messageId.set(null);
    this.status.set(null);
    this.proof.set(null);
    this.ingestedAt.set(null);
    this.disposedAt.set(null);
    this.error.set(null);
  }

  private startPolling(): void {
    this.stopPolling();
    void this.pollOnce();
    this.timer = setInterval(() => void this.pollOnce(), POLL_MS);
  }

  private stopPolling(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  /**
   * One pass of the lifecycle: resolve the id, then follow the countdown,
   * then confirm both stores are empty.
   *
   * Polling stops once the objects are gone, because nothing changes after
   * that and a demo left open should not keep asking.
   */
  private async pollOnce(): Promise<void> {
    try {
      let messageId = this.messageId();

      if (!messageId) {
        const requestId = this.requestId();
        if (!requestId) {
          return;
        }
        messageId = await firstValueFrom(this.demoApi.resolveMessageId(requestId));
        if (!messageId) {
          return;
        }
        this.messageId.set(messageId);
      }

      const [status, proof] = await Promise.all([
        firstValueFrom(this.demoApi.retentionStatus(messageId)),
        firstValueFrom(this.demoApi.storageProof(messageId)),
      ]);

      this.status.set(status);
      this.proof.set(proof);

      if (proof.fullyDisposed) {
        this.disposedAt.set(proof.disposedAt ? new Date(proof.disposedAt) : new Date());
        this.stopPolling();
      }
    } catch (error) {
      this.error.set(describeError(error));
      this.stopPolling();
    }
  }
}
