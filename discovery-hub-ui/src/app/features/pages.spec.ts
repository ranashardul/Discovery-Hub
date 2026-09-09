import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Type } from '@angular/core';
import { provideDiscoveryHubApi } from '../core/api/providers';
import { AuditPage } from './audit/audit-page';
import { CaseDetailPage } from './cases/case-detail-page';
import { CasesPage } from './cases/cases-page';
import { DashboardPage } from './dashboard/dashboard-page';
import { ExportsPage } from './exports/exports-page';
import { HoldsPage } from './holds/holds-page';
import { MessageDetailPage } from './messages/message-detail-page';
import { RetentionPage } from './retention/retention-page';
import { SearchPage } from './search/search-page';

/**
 * Smoke coverage: every route component must mount, resolve its data and render
 * without throwing. Catches template and wiring breakage that a type check
 * alone will not.
 */
describe('route components', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideDiscoveryHubApi()],
    });
  });

  /** Lets the simulated network latency in the mock backend settle. */
  async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, 800));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  const simplePages: [string, Type<unknown>][] = [
    ['dashboard', DashboardPage],
    ['search', SearchPage],
    ['cases', CasesPage],
    ['holds', HoldsPage],
    ['exports', ExportsPage],
    ['retention', RetentionPage],
    ['audit', AuditPage],
  ];

  for (const [name, page] of simplePages) {
    it(`renders the ${name} page`, async () => {
      const fixture = TestBed.createComponent(page);
      fixture.detectChanges();
      await settle(fixture);

      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('.page')).toBeTruthy();
      expect(host.textContent).not.toContain('undefined');
    });
  }

  it('renders a seeded case with its tabs', async () => {
    const fixture = TestBed.createComponent(CaseDetailPage);
    fixture.componentRef.setInput('id', 'case-0002');
    fixture.detectChanges();
    await settle(fixture);

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.page__title')?.textContent).toBeTruthy();
    expect(host.querySelectorAll('.tab').length).toBe(6);
  });

  it('renders a message with its thread', async () => {
    const fixture = TestBed.createComponent(MessageDetailPage);
    fixture.componentRef.setInput('id', 'msg-000001');
    fixture.detectChanges();
    await settle(fixture);

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.page__title')?.textContent).toBeTruthy();
    expect(host.textContent).toContain('msg-000001');
  });
});
