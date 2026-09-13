import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { ApiError } from '../../core/api/api-error';
import { CoverageApi } from '../../core/api/coverage-api';
import { CoverageMetric, CoverageReport } from '../../core/models/coverage';
import { CoveragePage } from './coverage-page';

function metric(covered: number, total: number): CoverageMetric {
  return { covered, total, pct: total ? Math.round((covered / total) * 10000) / 100 : 0 };
}

/**
 * A report with one well-covered module, one poorly covered one, and one that
 * was never measured — the three cases the page has to tell apart.
 */
function stubReport(): CoverageReport {
  return {
    generatedAt: '2026-01-01T00:00:00Z',
    includedIntegrationTests: true,
    metrics: ['lines', 'branches', 'methods'],
    tools: { java: 'JaCoCo 0.8.12', web: 'Vitest 4.1.11 (v8 provider)' },
    overall: {
      // 900 of 1000 lines, deliberately not the average of the module
      // percentages, so the test would catch the page recomputing it.
      lines: metric(900, 1000),
      branches: metric(400, 1000),
      methods: metric(300, 1000),
      tests: { total: 120, passed: 118, failed: 0, skipped: 2 },
    },
    modules: [
      {
        id: 'well-covered',
        name: 'well-covered',
        label: 'Well covered',
        description: 'A module with high coverage',
        kind: 'java',
        stack: 'Java 21 · Spring Boot 4',
        tool: 'JaCoCo',
        available: true,
        reason: null,
        lines: metric(880, 900),
        branches: metric(390, 400),
        methods: metric(290, 300),
        extra: { instructions: metric(1000, 1200) },
        tests: { total: 100, passed: 100, failed: 0, skipped: 0 },
        packages: [{ name: 'com.stown.well', ...groupMetrics(880, 900) }],
        htmlReport: 'well-covered/target/site/jacoco/index.html',
      },
      {
        id: 'thin',
        name: 'thin',
        label: 'Thinly covered',
        description: 'A module with low coverage',
        kind: 'web',
        stack: 'Angular 22 · TypeScript',
        tool: 'Vitest (v8)',
        available: true,
        reason: null,
        lines: metric(20, 100),
        branches: metric(10, 600),
        methods: metric(10, 700),
        extra: {},
        tests: { total: 20, passed: 18, failed: 0, skipped: 2 },
        packages: [{ name: 'src/app/thin', ...groupMetrics(20, 100) }],
        htmlReport: 'thin/coverage/index.html',
      },
      {
        id: 'never-run',
        name: 'never-run',
        label: 'Never run',
        description: 'A module with no report yet',
        kind: 'java',
        stack: 'Java 21 · Spring Boot 4',
        tool: 'JaCoCo',
        available: false,
        reason: 'No jacoco.csv - the suite has not been run yet.',
        lines: metric(0, 0),
        branches: metric(0, 0),
        methods: metric(0, 0),
        extra: {},
        tests: { total: 0, passed: 0, failed: 0, skipped: 0 },
        packages: [],
        htmlReport: 'never-run/target/site/jacoco/index.html',
      },
    ],
  };
}

function groupMetrics(covered: number, total: number) {
  return {
    lines: metric(covered, total),
    branches: metric(covered, total),
    methods: metric(covered, total),
  };
}

class StubCoverageApi extends CoverageApi {
  constructor(private readonly source: () => Observable<CoverageReport>) {
    super();
  }

  override report(): Observable<CoverageReport> {
    return this.source();
  }
}

function mount(source: () => Observable<CoverageReport>) {
  TestBed.configureTestingModule({
    providers: [{ provide: CoverageApi, useFactory: () => new StubCoverageApi(source) }],
  });

  const fixture = TestBed.createComponent(CoveragePage);
  fixture.detectChanges();
  return fixture;
}

describe('CoveragePage', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('shows the overall figure exactly as reported, without recomputing it', () => {
    const fixture = mount(() => of(stubReport()));
    const host = fixture.nativeElement as HTMLElement;

    // 90% is the reported total. The two measured modules are at 97.78% and
    // 20%, so an averaged headline would read ~59% and this would fail.
    expect(host.textContent).toContain('90%');
    expect(host.textContent).toContain('900 of 1,000 lines');
  });

  it('reports the tests that ran', () => {
    const fixture = mount(() => of(stubReport()));
    const host = fixture.nativeElement as HTMLElement;

    expect(host.textContent).toContain('120');
    expect(host.textContent).toContain('118 passed');
  });

  it('separates a module that was never measured from one with low coverage', () => {
    const fixture = mount(() => of(stubReport()));
    const host = fixture.nativeElement as HTMLElement;

    // The thinly covered module is a real measurement and belongs in the table.
    expect(host.textContent).toContain('Thinly covered');
    // The unmeasured one must be called out as absent, not rendered as 0%.
    expect(host.textContent).toContain('Not measured in this run');
    expect(host.textContent).toContain('the suite has not been run yet');
  });

  it('excludes unmeasured modules from the measured list', () => {
    const fixture = mount(() => of(stubReport()));
    const component = fixture.componentInstance as unknown as {
      measured: () => unknown[];
      missing: () => unknown[];
    };

    expect(component.measured().length).toBe(2);
    expect(component.missing().length).toBe(1);
  });

  it('bands percentages red, amber and green at 50 and 80', () => {
    const fixture = mount(() => of(stubReport()));
    const component = fixture.componentInstance as unknown as {
      band: (pct: number) => string;
    };

    expect(component.band(0)).toBe('low');
    expect(component.band(49.99)).toBe('low');
    expect(component.band(50)).toBe('medium');
    expect(component.band(79.99)).toBe('medium');
    expect(component.band(80)).toBe('high');
    expect(component.band(100)).toBe('high');
  });

  it('labels a run that skipped the integration suites', () => {
    const report = stubReport();
    report.includedIntegrationTests = false;

    const fixture = mount(() => of(report));
    const host = fixture.nativeElement as HTMLElement;

    expect(host.textContent).toContain('Unit tests only');
  });

  it('warns when the run contained a failing test', () => {
    const report = stubReport();
    report.overall.tests.failed = 3;

    const fixture = mount(() => of(report));
    const host = fixture.nativeElement as HTMLElement;

    expect(host.textContent).toContain('Failing tests');
  });

  it('explains how to generate a report when none exists', () => {
    const fixture = mount(() =>
      throwError(() => ApiError.notFound('No coverage report found. Generate one with x.')),
    );
    const host = fixture.nativeElement as HTMLElement;

    expect(host.textContent).toContain('No coverage report');
    expect(host.textContent).toContain('Generate one with');
  });

  it('expands and collapses a module package breakdown', () => {
    const fixture = mount(() => of(stubReport()));
    const component = fixture.componentInstance as unknown as {
      isExpanded: (id: string) => boolean;
      toggle: (id: string) => void;
    };

    expect(component.isExpanded('well-covered')).toBe(false);

    component.toggle('well-covered');
    fixture.detectChanges();
    expect(component.isExpanded('well-covered')).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('com.stown.well');

    component.toggle('well-covered');
    fixture.detectChanges();
    expect(component.isExpanded('well-covered')).toBe(false);
  });

  it('reports uncovered lines as the remainder', () => {
    const fixture = mount(() => of(stubReport()));
    const component = fixture.componentInstance as unknown as {
      uncovered: (m: CoverageMetric) => number;
    };

    expect(component.uncovered(metric(880, 900))).toBe(20);
    expect(component.uncovered(metric(0, 0))).toBe(0);
  });
});
