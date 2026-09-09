import { AttachmentMetadata, CommunicationType, Custodian, Message } from '../models/message';
import {
  ATTACHMENT_TEMPLATES,
  CHAT_BODIES,
  CHAT_SUBJECTS,
  CLIENTS,
  DEPARTMENTS,
  EMAIL_BODIES,
  EMAIL_SUBJECTS,
  FIRST_NAMES,
  LAST_NAMES,
  TICKERS,
  TITLES,
} from './corpus-vocabulary';
import { SeededRandom } from './random';

export interface CorpusOptions {
  messageCount: number;
  custodianCount: number;
  seed: number;
  /** Newest message in the corpus; everything is generated backwards from here. */
  now: Date;
  /** Days of history the corpus spans. */
  spanDays: number;
}

export const DEFAULT_CORPUS_OPTIONS: CorpusOptions = {
  messageCount: 10_000,
  custodianCount: 24,
  seed: 42,
  now: new Date(),
  spanDays: 540,
};

export interface Corpus {
  custodians: Custodian[];
  messages: Message[];
  /** Lower-cased `subject + body + participants`, parallel to `messages`. */
  searchText: string[];
  byId: Map<string, Message>;
}

const EMAIL_DOMAIN = 'stonewall-bank.example';

function slug(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '-');
}

function fillTemplate(template: string, random: SeededRandom): string {
  return template
    .replace(/\{desk\}/g, random.pick(DEPARTMENTS))
    .replace(/\{client\}/g, random.pick(CLIENTS))
    .replace(/\{ticker\}/g, random.pick(TICKERS))
    .replace(/\{pct\}/g, String(random.int(2, 38)));
}

function buildCustodians(random: SeededRandom, count: number): Custodian[] {
  const custodians: Custodian[] = [];
  const used = new Set<string>();

  for (let i = 0; i < count; i++) {
    let first = random.pick(FIRST_NAMES);
    let last = random.pick(LAST_NAMES);
    let email = `${slug(first)}.${slug(last)}@${EMAIL_DOMAIN}`;

    // Distinct mailboxes matter: the corpus is keyed on sender address.
    let guard = 0;
    while (used.has(email) && guard++ < 50) {
      first = random.pick(FIRST_NAMES);
      last = random.pick(LAST_NAMES);
      email = `${slug(first)}.${slug(last)}@${EMAIL_DOMAIN}`;
    }
    used.add(email);

    custodians.push({
      id: `cust-${String(i + 1).padStart(3, '0')}`,
      displayName: `${first} ${last}`,
      email,
      department: random.pick(DEPARTMENTS),
      title: random.pick(TITLES),
      messageCount: 0,
    });
  }

  return custodians;
}

function buildAttachments(random: SeededRandom, messageId: string): AttachmentMetadata[] {
  const count = random.bool(0.75) ? 1 : random.int(2, 4);
  const templates = random.sample(ATTACHMENT_TEMPLATES, count);

  return templates.map((template, index) => ({
    attachmentId: `${messageId}-att-${index + 1}`,
    filename: `${random.pick(TICKERS).toLowerCase()}-${template.suffix}`,
    contentType: template.contentType,
    sizeBytes: random.int(template.min, template.max),
    sha256: random.hex(64),
  }));
}

/**
 * Generates the synthetic archive the prototype reads from: ~10k messages
 * across two communication types, threaded, with attachments on roughly 8% of
 * items (FR-1 asks for at least 5%).
 */
export function generateCorpus(options: Partial<CorpusOptions> = {}): Corpus {
  const config = { ...DEFAULT_CORPUS_OPTIONS, ...options };
  const random = new SeededRandom(config.seed);
  const custodians = buildCustodians(random, config.custodianCount);
  const byEmail = new Map(custodians.map((custodian) => [custodian.email, custodian]));

  const messages: Message[] = [];
  const searchText: string[] = [];
  const spanMillis = config.spanDays * 24 * 60 * 60 * 1000;
  const endMillis = config.now.getTime();

  // Threads are pre-allocated so replies can share a subject and participants.
  const threadCount = Math.max(1, Math.floor(config.messageCount / 4));
  const threads = Array.from({ length: threadCount }, (_, index) => {
    const type: CommunicationType = random.bool(0.55) ? 'EMAIL' : 'CHAT';
    const participants = random.sample(custodians, random.int(2, 6));
    const subjectTemplate =
      type === 'EMAIL' ? random.pick(EMAIL_SUBJECTS) : random.pick(CHAT_SUBJECTS);

    return {
      id: `thr-${String(index + 1).padStart(5, '0')}`,
      type,
      participants,
      subject: fillTemplate(subjectTemplate, random),
      startMillis: endMillis - random.int(0, spanMillis),
    };
  });

  for (let i = 0; i < config.messageCount; i++) {
    const thread = threads[random.int(0, threads.length)];
    const sender = random.pick(thread.participants);
    const recipients = thread.participants
      .filter((participant) => participant.email !== sender.email)
      .map((participant) => participant.email);

    if (recipients.length === 0) {
      recipients.push(random.pick(custodians).email);
    }

    const id = `msg-${String(i + 1).padStart(6, '0')}`;
    // Replies land after the thread opened, within a fortnight of it.
    const timestamp = Math.min(
      endMillis,
      thread.startMillis + random.int(0, 14 * 24 * 60 * 60 * 1000),
    );

    const isReply = random.bool(0.6);
    const subject =
      thread.type === 'EMAIL' && isReply ? `RE: ${thread.subject}` : thread.subject;
    const body = fillTemplate(
      thread.type === 'EMAIL' ? random.pick(EMAIL_BODIES) : random.pick(CHAT_BODIES),
      random,
    );

    const message: Message = {
      id,
      externalMessageId: `${thread.type.toLowerCase()}-${random.hex(12)}`,
      communicationType: thread.type,
      sender: sender.email,
      recipients,
      subject,
      body,
      messageTimestamp: new Date(timestamp).toISOString(),
      threadId: thread.id,
      attachments: random.bool(0.08) ? buildAttachments(random, id) : [],
      createdAt: new Date(timestamp + random.int(1_000, 25_000)).toISOString(),
      holdCount: 0,
      dispositionStatus: 'ACTIVE',
    };

    messages.push(message);
    searchText.push(
      `${message.subject} ${message.body} ${message.sender} ${recipients.join(' ')} ${message.attachments
        .map((attachment) => attachment.filename)
        .join(' ')}`.toLowerCase(),
    );

    const senderRecord = byEmail.get(sender.email);
    if (senderRecord) {
      senderRecord.messageCount++;
    }
  }

  return {
    custodians,
    messages,
    searchText,
    byId: new Map(messages.map((message) => [message.id, message])),
  };
}
