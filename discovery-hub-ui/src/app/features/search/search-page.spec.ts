import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideMockDiscoveryHubApi } from '../../core/api/providers';
import { SearchPage } from './search-page';

/**
 * The search API answers a request with filters and no `q` — "everything this
 * custodian sent" has no term to score against — and the page used to refuse
 * to send one, so FR-3.2's filters were unusable on their own. These assert
 * the page agrees with the service about what counts as a valid request.
 */
describe('SearchPage', () => {
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

  async function open() {
    const fixture = TestBed.createComponent(SearchPage);
    fixture.detectChanges();
    // The custodian directory arrives over the simulated network, and the
    // filters cannot be exercised before its options exist.
    await settle(fixture);
    return fixture;
  }

  function host(fixture: ComponentFixture<SearchPage>): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function submit(fixture: ComponentFixture<SearchPage>): void {
    host(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
  }

  function select(fixture: ComponentFixture<SearchPage>, control: string): HTMLSelectElement {
    return host(fixture).querySelector<HTMLSelectElement>(`select[formControlName="${control}"]`)!;
  }

  function choose(element: HTMLSelectElement, value: string): void {
    element.value = value;
    element.dispatchEvent(new Event('change'));
  }

  function resultRows(fixture: ComponentFixture<SearchPage>): HTMLTableRowElement[] {
    return Array.from(host(fixture).querySelectorAll('tbody tr'));
  }

  it('searches on a sender with no query text', async () => {
    const fixture = await open();

    const sender = select(fixture, 'sender');
    const identity = sender.options[1].value;
    choose(sender, identity);
    submit(fixture);
    await settle(fixture);

    expect(host(fixture).textContent).not.toContain('Enter a search term');
    const rows = resultRows(fixture);
    expect(rows.length).toBeGreaterThan(0);
    for (const row of rows) {
      expect(row.textContent).toContain(identity);
    }
  });

  it('matches a custodian on either side of the conversation', async () => {
    const fixture = await open();

    const checkbox = host(fixture).querySelector<HTMLInputElement>(
      '.field .check input[type="checkbox"]',
    )!;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));
    submit(fixture);
    await settle(fixture);

    expect(host(fixture).textContent).not.toContain('Enter a search term');
    expect(resultRows(fixture).length).toBeGreaterThan(0);
  });

  it('refuses a request with neither terms nor filters', async () => {
    const fixture = await open();

    submit(fixture);
    await settle(fixture);

    // Without this the request is "page the entire archive", which is far more
    // likely to be a slip than an intent — and the service rejects it too.
    expect(host(fixture).textContent).toContain('choose at least one filter');
    expect(resultRows(fixture).length).toBe(0);
  });

  it('reports a filter-only search as date ordered, not by relevance', async () => {
    const fixture = await open();

    choose(select(fixture, 'communicationType'), 'EMAIL');
    submit(fixture);
    await settle(fixture);

    // Nothing scores without query text, so claiming relevance order would be
    // a lie about an ordering that is really chronological.
    expect(host(fixture).textContent).toContain('sorted by newest');
  });
});
