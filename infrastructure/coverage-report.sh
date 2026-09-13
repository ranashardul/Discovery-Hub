#!/usr/bin/env bash
#
# Runs every test suite in the repository and aggregates the coverage results
# into one JSON file the UI reads.
#
#   ./coverage-report.sh                  # all suites, integration included
#   ./coverage-report.sh --no-integration # skip Testcontainers, no Docker needed
#   ./coverage-report.sh --skip-tests     # re-aggregate existing reports only
#   ./coverage-report.sh --quiet          # exit code and the summary line only
#
# Output: discovery-hub-ui/public/coverage-summary.json
#
# The UI serves that file as a static asset, so the coverage screen needs no
# backend endpoint and no service was modified to provide it. Re-run this
# script to refresh the numbers.
#
# Two per-module quirks this handles so the caller does not have to:
#
#   * export-audit-service has no Maven wrapper. There is often no `mvn` on the
#     PATH either, so its build borrows ingestion-service's wrapper via `-f`.
#   * Integration tests are tagged `integration` and excluded by surefire by
#     default. Clearing excluded.test.groups includes them, which needs Docker
#     running because Testcontainers starts MongoDB, Kafka, Elasticsearch,
#     MinIO and PostgreSQL.

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
ROOT="$PWD"

QUIET=0
RUN_TESTS=1
INTEGRATION=1

for arg in "$@"; do
  case "$arg" in
    --quiet) QUIET=1 ;;
    --skip-tests) RUN_TESTS=0 ;;
    --no-integration) INTEGRATION=0 ;;
    -h|--help)
      sed -n '3,26p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      printf 'Unknown option: %s (try --help)\n' "$arg" >&2
      exit 2
      ;;
  esac
done

OUTPUT="$ROOT/discovery-hub-ui/public/coverage-summary.json"

JAVA_MODULES=(ingestion-service search-service case-hold-service export-audit-service)

say() { [ "$QUIET" -eq 1 ] || printf '%s\n' "$1"; }
bold() { [ "$QUIET" -eq 1 ] || printf '\033[1m%s\033[0m\n' "$1"; }
red() { printf '\033[31m%s\033[0m\n' "$1" >&2; }
green() { [ "$QUIET" -eq 1 ] || printf '\033[32m%s\033[0m\n' "$1"; }
dim() { [ "$QUIET" -eq 1 ] || printf '\033[2m%s\033[0m\n' "$1"; }

FAILED_MODULES=()

# Maven invocation for a module. Everything except export-audit-service has its
# own wrapper; that one borrows ingestion-service's.
run_maven() {
  local module="$1"
  shift

  if [ -x "$ROOT/$module/mvnw" ]; then
    (cd "$ROOT/$module" && ./mvnw "$@")
    return $?
  fi

  if [ -x "$ROOT/ingestion-service/mvnw" ]; then
    dim "  $module has no wrapper; borrowing ingestion-service/mvnw"
    (cd "$ROOT/ingestion-service" && ./mvnw -f "$ROOT/$module/pom.xml" "$@")
    return $?
  fi

  if command -v mvn >/dev/null 2>&1; then
    (cd "$ROOT/$module" && mvn "$@")
    return $?
  fi

  red "No Maven wrapper for $module and no mvn on the PATH."
  return 1
}

if [ "$RUN_TESTS" -eq 1 ]; then
  if [ "$INTEGRATION" -eq 1 ] && ! docker info >/dev/null 2>&1; then
    red "Docker is not running, and the integration tests need it (Testcontainers)."
    red "Start Docker, or re-run with --no-integration."
    exit 1
  fi

  bold "Running test suites with coverage"
  [ "$INTEGRATION" -eq 1 ] \
    && dim "  integration tests: included (Docker required)" \
    || dim "  integration tests: skipped"
  say ""

  MAVEN_ARGS=(-B test)
  # Clearing the property includes the tagged integration tests. Modules that
  # do not declare the property simply ignore it.
  [ "$INTEGRATION" -eq 1 ] && MAVEN_ARGS+=(-Dexcluded.test.groups=)

  for module in "${JAVA_MODULES[@]}"; do
    bold "  $module"
    if run_maven "$module" "${MAVEN_ARGS[@]}" >"/tmp/coverage-$module.log" 2>&1; then
      green "  ok"
    else
      red "  FAILED - see /tmp/coverage-$module.log"
      tail -25 "/tmp/coverage-$module.log" >&2
      FAILED_MODULES+=("$module")
    fi
  done

  bold "  discovery-hub-ui"
  if (cd "$ROOT/discovery-hub-ui" && npm run test:coverage) \
      >"/tmp/coverage-discovery-hub-ui.log" 2>&1; then
    green "  ok"
  else
    red "  FAILED - see /tmp/coverage-discovery-hub-ui.log"
    tail -25 "/tmp/coverage-discovery-hub-ui.log" >&2
    FAILED_MODULES+=(discovery-hub-ui)
  fi

  say ""
fi

bold "Aggregating coverage"

INTEGRATION="$INTEGRATION" RUN_TESTS="$RUN_TESTS" OUTPUT="$OUTPUT" ROOT="$ROOT" \
  python3 - <<'PY'
import csv
import json
import os
import glob
import datetime
import xml.etree.ElementTree as ET

root = os.environ["ROOT"]
output = os.environ["OUTPUT"]
integration = os.environ["INTEGRATION"] == "1"

# Metrics normalised across JaCoCo and V8 so an overall number is meaningful.
# JaCoCo reports instructions/lines/branches/methods; V8 reports
# statements/lines/branches/functions. Lines, branches and methods/functions
# are the three that mean the same thing on both sides, so those are the ones
# aggregated. Anything platform-specific is carried per module instead.
METRICS = ("lines", "branches", "methods")

JAVA_MODULES = [
    ("ingestion-service", "Ingestion", "Ingest, archive, retention and disposition"),
    ("search-service", "Search", "Elasticsearch projection and search API"),
    ("case-hold-service", "Case & Hold", "Cases, legal holds, saved searches"),
    ("export-audit-service", "Export & Audit", "Evidence packages and chain of custody"),
]


def ratio(covered, total):
    return round(covered * 100.0 / total, 2) if total else 0.0


def metric(covered, total):
    return {"covered": covered, "total": total, "pct": ratio(covered, total)}


def empty_tests():
    return {"total": 0, "passed": 0, "failed": 0, "skipped": 0}


def surefire_tests(module):
    """Test counts from the surefire XML reports."""
    counts = empty_tests()
    pattern = os.path.join(root, module, "target", "surefire-reports", "TEST-*.xml")

    for path in glob.glob(pattern):
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            continue

        total = int(suite.get("tests", 0))
        failures = int(suite.get("failures", 0))
        errors = int(suite.get("errors", 0))
        skipped = int(suite.get("skipped", 0))

        counts["total"] += total
        counts["failed"] += failures + errors
        counts["skipped"] += skipped

    counts["passed"] = counts["total"] - counts["failed"] - counts["skipped"]
    return counts


def jacoco_module(module, label, blurb):
    """Reads a module's jacoco.csv into normalised totals plus a package split."""
    csv_path = os.path.join(root, module, "target", "site", "jacoco", "jacoco.csv")

    if not os.path.exists(csv_path):
        return {
            "id": module,
            "name": module,
            "label": label,
            "description": blurb,
            "kind": "java",
            "stack": "Java 21 · Spring Boot 4",
            "tool": "JaCoCo",
            "available": False,
            "reason": "No jacoco.csv - the suite has not been run yet.",
            "lines": metric(0, 0),
            "branches": metric(0, 0),
            "methods": metric(0, 0),
            "extra": {},
            "tests": empty_tests(),
            "packages": [],
            "htmlReport": f"{module}/target/site/jacoco/index.html",
        }

    totals = {
        "line": [0, 0],
        "branch": [0, 0],
        "method": [0, 0],
        "instruction": [0, 0],
        "complexity": [0, 0],
    }
    packages = {}

    with open(csv_path, newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            package = row["PACKAGE"]
            bucket = packages.setdefault(package, {"line": [0, 0], "branch": [0, 0], "method": [0, 0]})

            for key, prefix in (
                ("line", "LINE"),
                ("branch", "BRANCH"),
                ("method", "METHOD"),
                ("instruction", "INSTRUCTION"),
                ("complexity", "COMPLEXITY"),
            ):
                missed = int(row[f"{prefix}_MISSED"])
                covered = int(row[f"{prefix}_COVERED"])
                totals[key][0] += covered
                totals[key][1] += covered + missed

                if key in bucket:
                    bucket[key][0] += covered
                    bucket[key][1] += covered + missed

    package_list = sorted(
        (
            {
                "name": name,
                "lines": metric(*values["line"]),
                "branches": metric(*values["branch"]),
                "methods": metric(*values["method"]),
            }
            for name, values in packages.items()
        ),
        key=lambda item: item["lines"]["total"],
        reverse=True,
    )

    return {
        "id": module,
        "name": module,
        "label": label,
        "description": blurb,
        "kind": "java",
        "stack": "Java 21 · Spring Boot 4",
        "tool": "JaCoCo",
        "available": True,
        "reason": None,
        "lines": metric(*totals["line"]),
        "branches": metric(*totals["branch"]),
        "methods": metric(*totals["method"]),
        "extra": {
            "instructions": metric(*totals["instruction"]),
            "complexity": metric(*totals["complexity"]),
        },
        "tests": surefire_tests(module),
        "packages": package_list,
        "htmlReport": f"{module}/target/site/jacoco/index.html",
    }


def vitest_tests():
    path = os.path.join(
        root, "discovery-hub-ui", "coverage", "discovery-hub-ui", "vitest-results.json"
    )
    counts = empty_tests()

    if not os.path.exists(path):
        return counts

    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)

    counts["total"] = data.get("numTotalTests", 0)
    counts["passed"] = data.get("numPassedTests", 0)
    counts["failed"] = data.get("numFailedTests", 0)
    counts["skipped"] = data.get("numPendingTests", 0) + data.get("numTodoTests", 0)
    return counts


def ui_module():
    """Reads the vitest json-summary, grouping files into feature areas."""
    path = os.path.join(
        root, "discovery-hub-ui", "coverage", "discovery-hub-ui", "coverage-summary.json"
    )

    base = {
        "id": "discovery-hub-ui",
        "name": "discovery-hub-ui",
        "label": "Web UI",
        "description": "Angular front end, screens and API clients",
        "kind": "web",
        "stack": "Angular 22 · TypeScript",
        "tool": "Vitest (v8)",
        "htmlReport": "discovery-hub-ui/coverage/discovery-hub-ui/index.html",
    }

    if not os.path.exists(path):
        base.update(
            available=False,
            reason="No coverage-summary.json - the suite has not been run yet.",
            lines=metric(0, 0),
            branches=metric(0, 0),
            methods=metric(0, 0),
            extra={},
            tests=empty_tests(),
            packages=[],
        )
        return base

    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)

    total = data.get("total", {})

    def part(name):
        entry = total.get(name, {})
        return metric(entry.get("covered", 0), entry.get("total", 0))

    # Group per-file entries into the same directory buckets the source tree
    # uses, so the breakdown reads like the project rather than like a file
    # listing.
    groups = {}
    for file_path, entry in data.items():
        if file_path == "total":
            continue

        relative = file_path.split("discovery-hub-ui/", 1)[-1]
        segments = relative.split("/")
        name = "/".join(segments[:3]) if len(segments) > 3 else "/".join(segments[:-1])
        name = name or relative

        bucket = groups.setdefault(name, {"line": [0, 0], "branch": [0, 0], "method": [0, 0]})
        for key, source in (("line", "lines"), ("branch", "branches"), ("method", "functions")):
            values = entry.get(source, {})
            bucket[key][0] += values.get("covered", 0)
            bucket[key][1] += values.get("total", 0)

    package_list = sorted(
        (
            {
                "name": name,
                "lines": metric(*values["line"]),
                "branches": metric(*values["branch"]),
                "methods": metric(*values["method"]),
            }
            for name, values in groups.items()
        ),
        key=lambda item: item["lines"]["total"],
        reverse=True,
    )

    base.update(
        available=True,
        reason=None,
        lines=part("lines"),
        branches=part("branches"),
        methods=part("functions"),
        extra={"statements": part("statements")},
        tests=vitest_tests(),
        packages=package_list,
    )
    return base


modules = [jacoco_module(*entry) for entry in JAVA_MODULES]
modules.append(ui_module())

# The overall figure is a true sum over every module, not an average of
# percentages: averaging would let a tiny module swing the headline number as
# much as the largest one.
overall = {}
for name in METRICS:
    covered = sum(m[name]["covered"] for m in modules if m["available"])
    total = sum(m[name]["total"] for m in modules if m["available"])
    overall[name] = metric(covered, total)

tests = empty_tests()
for module in modules:
    for key in tests:
        tests[key] += module["tests"][key]
overall["tests"] = tests

report = {
    "generatedAt": datetime.datetime.now(datetime.timezone.utc)
    .isoformat(timespec="seconds")
    .replace("+00:00", "Z"),
    "includedIntegrationTests": integration,
    "metrics": list(METRICS),
    "tools": {"java": "JaCoCo 0.8.12", "web": "Vitest 4.1.11 (v8 provider)"},
    "overall": overall,
    "modules": modules,
}

os.makedirs(os.path.dirname(output), exist_ok=True)
with open(output, "w", encoding="utf-8") as handle:
    json.dump(report, handle, indent=2)
    handle.write("\n")

print(f"  wrote {os.path.relpath(output, root)}")
print(
    "  overall lines {}% ({}/{}), branches {}%, methods {}%, {} tests".format(
        overall["lines"]["pct"],
        overall["lines"]["covered"],
        overall["lines"]["total"],
        overall["branches"]["pct"],
        overall["methods"]["pct"],
        tests["total"],
    )
)

for module in modules:
    state = f"{module['lines']['pct']}%" if module["available"] else "no report"
    print(f"    {module['name']:<24} {state:>10}  {module['tests']['total']} tests")
PY

AGGREGATED=$?

if [ "$AGGREGATED" -ne 0 ]; then
  say ""
  red "Aggregation failed."
  exit 1
fi

# Push the fresh report into a running UI container.
#
# The bundle is built into the image, and Angular copies public/ into it, so a
# regenerated report on the host does not reach a container that is already
# running. Compose now bind-mounts the file, which makes this unnecessary — but
# a container created before that mount existed still serves the baked copy,
# and rebuilding the image to change one JSON file is a three-minute loop.
#
# When the mount is in place the copy fails because the target is read-only.
# That is the good outcome, not an error: the container is already reading this
# exact file from the host.
UI_CONTAINER="${COVERAGE_UI_CONTAINER:-stown-discovery-hub-ui}"

if [ "$(docker inspect -f '{{.State.Running}}' "$UI_CONTAINER" 2>/dev/null)" = "true" ]; then
  if docker cp "$OUTPUT" \
      "$UI_CONTAINER:/usr/share/nginx/html/coverage-summary.json" >/dev/null 2>&1; then
    dim "  copied into $UI_CONTAINER - reload the Coverage screen"
  else
    dim "  $UI_CONTAINER reads this file directly (read-only mount) - nothing to copy"
  fi
else
  dim "  $UI_CONTAINER is not running; start the stack to view the screen"
fi

say ""

if [ "${#FAILED_MODULES[@]}" -gt 0 ]; then
  red "Test suites failed: ${FAILED_MODULES[*]}"
  red "The report above covers whatever did run, so treat those numbers as partial."
  exit 1
fi

green "Coverage report written. Open the Coverage screen in the UI, or read the"
green "per-module HTML report listed for each module."
