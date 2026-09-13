/**
 * Test-coverage reporting model.
 *
 * These shapes mirror `discovery-hub-ui/public/coverage-summary.json`, which
 * `infrastructure/coverage-report.sh` generates by running every suite in the
 * repository and aggregating JaCoCo (Java) and Vitest (web) output.
 *
 * It is a build artefact served as a static asset, deliberately not an API:
 * coverage is a property of the source tree at build time, not of a running
 * service, and reporting it needed no change to any of the four services.
 */

/** One measured dimension: how much of it exists, and how much is covered. */
export interface CoverageMetric {
  covered: number;
  total: number;
  /** Already rounded to two decimal places by the generator. */
  pct: number;
}

export interface TestCounts {
  total: number;
  passed: number;
  failed: number;
  skipped: number;
}

/** A package (Java) or source directory (web) within a module. */
export interface CoverageGroup {
  name: string;
  lines: CoverageMetric;
  branches: CoverageMetric;
  methods: CoverageMetric;
}

export type CoverageModuleKind = 'java' | 'web';

export interface CoverageModule {
  id: string;
  name: string;
  /** Short human label, e.g. "Case & Hold". */
  label: string;
  description: string;
  kind: CoverageModuleKind;
  /** e.g. "Java 21 · Spring Boot 4". */
  stack: string;
  /** e.g. "JaCoCo" or "Vitest (v8)". */
  tool: string;
  /**
   * False when the module has no report on disk yet, which is the normal
   * state before the script has been run. The page says so rather than
   * rendering it as genuine zero coverage — those two mean very different
   * things and conflating them would misreport a healthy module.
   */
  available: boolean;
  reason: string | null;
  lines: CoverageMetric;
  branches: CoverageMetric;
  methods: CoverageMetric;
  /**
   * Metrics only one toolchain reports: instructions and cyclomatic
   * complexity from JaCoCo, statements from V8. Kept per module rather than
   * aggregated, because there is no meaningful way to sum them across the two.
   */
  extra: Record<string, CoverageMetric>;
  tests: TestCounts;
  packages: CoverageGroup[];
  /** Repo-relative path to the module's own drill-down HTML report. */
  htmlReport: string;
}

export interface CoverageOverall {
  lines: CoverageMetric;
  branches: CoverageMetric;
  methods: CoverageMetric;
  tests: TestCounts;
}

export interface CoverageReport {
  generatedAt: string;
  /**
   * Whether the Testcontainers suites ran. Coverage is materially lower
   * without them, so a run that skipped them is labelled instead of being
   * compared like for like against one that did not.
   */
  includedIntegrationTests: boolean;
  metrics: string[];
  tools: Record<string, string>;
  overall: CoverageOverall;
  modules: CoverageModule[];
}
