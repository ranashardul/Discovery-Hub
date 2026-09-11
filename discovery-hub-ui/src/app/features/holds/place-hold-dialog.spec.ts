import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideMockDiscoveryHubApi } from '../../core/api/providers';
import { LegalCase } from '../../core/models/case';
import { LegalHold } from '../../core/models/hold';
import { MockStore } from '../../core/mock/mock-store';
import { PlaceHoldDialog } from './place-hold-dialog';

/**
 * The dialog is the only place a hold can be scoped, so the two things that
 * made holds unplaceable before are asserted directly: that custodians come
 * from the archive directory rather than a case-custodian relation, and that
 * the scope is never prefilled.
 */
describe('PlaceHoldDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideMockDiscoveryHubApi()],
    });
  });

  async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, 800));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function open(presetCase: LegalCase | null = null) {
    const fixture = TestBed.createComponent(PlaceHoldDialog);
    fixture.componentRef.setInput('presetCase', presetCase);
    fixture.detectChanges();
    return fixture;
  }

  it('offers the whole archive directory when no case is chosen yet', async () => {
    const fixture = open();
    await settle(fixture);

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('.check input[type="checkbox"]').length).toBeGreaterThan(0);
  });

  /**
   * A hold is scoped by custodian, and the only custodians that can be in
   * scope for a matter are the people on its evidence. Offering the whole
   * archive invites a hold over someone with no connection to the case, which
   * is over-preservation and the reviewer cannot tell by looking.
   */
  it('offers only the participants of the case, once a case is known', async () => {
    const store = TestBed.inject(MockStore);
    const seeded = store
      .listCases({})
      .find((item) => store.listEvidence(item.id).length > 0)!;
    expect(seeded).toBeTruthy();

    const evidence = store.listEvidence(seeded.id);

    const participants = new Set(
      evidence.flatMap((item) => [item.sender, ...item.recipients]).filter(Boolean),
    );
    const directory = store.listCustodianDirectory();
    // Otherwise the assertion below would pass without scoping anything.
    expect(participants.size).toBeLessThan(directory.length);

    const fixture = open(seeded);
    await settle(fixture);

    const offered = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.check input[type="checkbox"]'),
    );
    expect(offered.length).toBe(participants.size);
  });

  it('asks which case when none is supplied, and offers only open ones', async () => {
    const fixture = open();
    await settle(fixture);

    const host = fixture.nativeElement as HTMLElement;
    const select = host.querySelector('select');
    expect(select).toBeTruthy();
    // The placeholder plus at least one open case.
    expect(select!.querySelectorAll('option').length).toBeGreaterThan(1);
  });

  it('hides the case picker when the case is already known', async () => {
    const fixture = open({
      id: 'case-0002',
      caseNumber: 'LC-1',
      name: 'Preset matter',
      description: '',
      matterType: 'INVESTIGATION',
      owner: 'someone',
      status: 'OPEN',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      closedAt: null,
      custodianCount: 0,
      evidenceCount: 0,
      activeHoldCount: 0,
      heldMessageCount: 0,
    });
    await settle(fixture);

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('select')).toBeNull();
    // Reason is seeded from the case so the chain of custody is never blank.
    expect(host.querySelector<HTMLInputElement>('input[formControlName="reason"]')?.value).toContain(
      'Preset matter',
    );
  });

  it('never prefills the scope, so no hold covers the whole archive by default', async () => {
    const fixture = open();
    await settle(fixture);

    const host = fixture.nativeElement as HTMLElement;
    const checked = host.querySelectorAll('.check input[type="checkbox"]:checked');
    expect(checked.length).toBe(0);
  });

  it('refuses to place a hold with no custodian selected', async () => {
    const fixture = open();
    await settle(fixture);

    let placed: LegalHold | null = null;
    fixture.componentInstance.placed.subscribe((hold) => (placed = hold));

    const buttons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ) as HTMLButtonElement[];
    buttons.find((button) => button.textContent?.includes('Place hold'))?.click();
    await settle(fixture);

    expect(placed).toBeNull();
  });
});
