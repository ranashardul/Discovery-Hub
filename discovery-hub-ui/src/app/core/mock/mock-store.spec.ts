import { TestBed } from '@angular/core/testing';
import { ApiError } from '../api/api-error';
import { MockStore } from './mock-store';

/**
 * These cover the rules the UI depends on: the case lifecycle, the read-only
 * closed case, and the guarantee that a held message cannot be deleted.
 */
describe('MockStore', () => {
  let store: MockStore;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    store = TestBed.inject(MockStore);
  });

  function newCase() {
    return store.createCase({
      name: 'Test matter',
      description: 'Created by a unit test',
      matterType: 'INVESTIGATION',
      owner: 'tester@example.com',
    });
  }

  it('seeds a corpus of at least 10,000 messages with attachments on over 5%', () => {
    const counts = store.counts();

    expect(counts.totalMessages).toBeGreaterThanOrEqual(10_000);
    expect(counts.custodians).toBeGreaterThanOrEqual(20);
    expect(counts.withAttachments / counts.totalMessages).toBeGreaterThan(0.05);
  });

  it('rejects a blank query rather than returning everything', () => {
    expect(() => store.search({ q: '  ' })).toThrow(ApiError);
  });

  it('paginates and reports the full total', () => {
    const first = store.search({ q: 'restricted list', size: 5 });
    const second = store.search({ q: 'restricted list', size: 5, from: 5 });

    expect(first.results).toHaveLength(5);
    expect(first.total).toBeGreaterThan(5);
    expect(second.from).toBe(5);
    expect(second.results[0].messageId).not.toBe(first.results[0].messageId);
  });

  it('allows only the documented case transitions', () => {
    const created = newCase();
    expect(created.status).toBe('OPEN');

    // OPEN and CLOSED are the whole lifecycle, and closing is reversible.
    expect(store.changeStatus(created.id, 'CLOSED').status).toBe('CLOSED');
    expect(store.changeStatus(created.id, 'OPEN').status).toBe('OPEN');

    // Re-entering the status you are already in is not a transition.
    expect(() => store.changeStatus(created.id, 'OPEN')).toThrow(/Invalid transition/);
  });

  it('makes a closed case read-only', () => {
    const created = newCase();
    const messageId = store.search({ q: 'budget', size: 1 }).results[0].messageId;
    store.addEvidence(created.id, { messageIds: [messageId], source: 'MANUAL' });
    store.changeStatus(created.id, 'CLOSED');

    expect(() =>
      store.addEvidence(created.id, { messageIds: [messageId], source: 'MANUAL' }),
    ).toThrow(/read-only/);
  });

  it('never adds the same message to a case twice', () => {
    const created = newCase();

    const messageId = store.search({ q: 'budget', size: 1 }).results[0].messageId;

    expect(store.addEvidence(created.id, { messageIds: [messageId], source: 'MANUAL' })).toHaveLength(1);
    expect(store.addEvidence(created.id, { messageIds: [messageId], source: 'MANUAL' })).toHaveLength(0);
    expect(store.listEvidence(created.id)).toHaveLength(1);
  });

  it('refuses to delete a message that is under hold, and records the refusal', () => {
    const held = store.search({ q: 'the', onHold: true, size: 1 }).results[0];
    expect(held).toBeDefined();

    const result = store.attemptDelete(held.messageId);

    expect(result.deleted).toBe(false);
    expect(result.blockingHoldIds.length).toBeGreaterThan(0);
    expect(
      store.queryAudit({ action: 'DELETION_BLOCKED', size: 5 }).entries[0].targetId,
    ).toBe(held.messageId);
  });

  it('deletes a message that no hold covers', () => {
    const free = store.search({ q: 'the', onHold: false, size: 1 }).results[0];

    expect(store.attemptDelete(free.messageId).deleted).toBe(true);
    expect(store.getMessage(free.messageId).dispositionStatus).toBe('DISPOSED');
  });

  it('skips held messages during disposition', () => {
    // One minute of retention makes practically the whole corpus eligible.
    store.updateRetentionPolicy('EMAIL', 1);
    store.updateRetentionPolicy('CHAT', 1);

    const run = store.runDisposition('MANUAL');

    expect(run.deleted).toBeGreaterThan(0);
    expect(run.skippedOnHold).toBeGreaterThan(0);

    // Everything the run skipped is still there, and still held.
    for (const messageId of run.skippedSample) {
      const message = store.getMessage(messageId);
      expect(message.dispositionStatus).not.toBe('DISPOSED');
      expect(message.holdCount).toBeGreaterThan(0);
    }

    // Nothing on hold was deleted, so the surviving held count covers the skips.
    expect(store.counts().heldMessages).toBeGreaterThanOrEqual(run.skippedOnHold);
  });

  it('never rewrites an audit entry, only appends', () => {
    const before = store.queryAudit({ size: 1 }).total;
    newCase();
    const after = store.queryAudit({ size: 1 });

    expect(after.total).toBe(before + 1);
    expect(after.entries[0].action).toBe('CASE_CREATED');
    expect(after.entries[0].sequence).toBeGreaterThan(0);
  });

  it('places a hold as ACTIVE over its resolved scope', () => {
    const created = newCase();
    const custodian = store.listCustodianDirectory()[0];
    store.addCustodian(created.id, custodian.id);

    const hold = store.placeHold({
      caseId: created.id,
      reason: 'Unit test preservation',
      scope: { custodianIds: [custodian.id], after: null, before: null, searchTerms: null },
    });

    // case-hold-service writes a hold as ACTIVE in one transaction; there is
    // no PROPAGATING state to observe.
    expect(hold.status).toBe('ACTIVE');
    expect(hold.matchedMessageCount).toBeGreaterThan(0);
  });
});
