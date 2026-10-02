# E2E regression run, 27–28 September 2026

End-to-end API and UI testing of the automation engine, regions, maker-checker and as-of work
([PRD](../../requirements/automation-regions-approvals-asof.md)), on PostgreSQL 16 and Chromium. Six test rounds
(the first five followed by fixes), then a final test-only run. How this fits the rest of the work: [workflow architecture](../../workflow/WORKFLOW-ARCHITECTURE.md).

## Final result

| | Cases | Passed | Failed | Blocked |
|---|---|---|---|---|
| API | 767 | 767 | 0 | 0 |
| UI | 266 | 258 | 0 | 8 |
| Backend unit and integration tests | 852 | 852 | 0 | — |
| Mail-service tests | 24 | 24 | 0 | — |
| Flutter widget tests | 505 | 505 | 0 | — |

Flutter static analysis: 0 issues. Tested source: `refs/snapshots/FINAL` (`ff97402d`).

**Open defect TSK-F-1 (medium).** Opening a deep link such as `#/tasks/5` on a fresh page load lands on the
Dashboard; navigating after the app has loaded works. The pre-build router behaves the same way. It blocks the 8
UI cases above. Steps, evidence and the likely cause are in the UI report.

## Reports

- [`report/api-report.html`](report/api-report.html): every API case, filterable, with steps, expected and actual
  results, evidence and code references.
- [`report/ui-report.html`](report/ui-report.html): every UI case, plus a slide view of all 350 screenshots
  (`report/slides/`). Open the file directly in a browser.
- The same two reports are published as private pages:
  [API](https://claude.ai/artifact/UiSVYiQJXCsvqJrvNYdwXG) · [UI](https://claude.ai/artifact/47YnjP23SLhHSvsLLiWBFC).
- `report/tools/` holds the page template and the scripts that build both pages from `data/final/report-data.json`.

## Rounds

| Round | Passed | Product defects | Notes |
|---|---|---|---|
| 1 | 607 / 642¹ | 13 + 3 carried from hardening | Automation never ran on PostgreSQL; upgrading the dev database broke saves |
| 2 | 685 / 692 | 2 | |
| 3 | 707 / 717 | 3 | One high regression caused by a round-2 fix |
| 4 | 750 / 752 | 2 | |
| 5 | 789 / 791 | 2 | |
| 6 | 404 / 404 | 0 | Only the three areas the last fixes touched |
| Final | 1,025 / 1,033 | 1 (pre-existing) | Nothing fixed; suite as written plus an independent exploratory pass |

¹ From the testers' returns. The round-1 result files for asof and regions were not kept, so `data/runs/r1/` holds
only the other five areas (409 / 442); the reports' round history shows 528 / 561 for the same reason.

Every round's results, triage plan and fixes are in [`data/`](data/):

- `data/runs/r<N>/<area>.json`: case results per round (`runs/final/` for the final run). Round 1 has no
  `asof.json` or `regions.json`.
- `data/runs/r<N>/TRIAGE.md` and `data/runs/r<N>-TRIAGE.md`: what each defect was, how it was verified, the fix
  packages, and product decisions.
- `data/FIXLOG-backend.md`, `data/FIXLOG-frontend.md`: every fix, with the test that pins it.
- `data/final/`: the final build record (`build.json`) and the report data.

## Areas

| Area | Covers |
|---|---|
| regions | B1: read/manage/none matrix across three regions, the region selector, POC book per region, region admin and grants, region moves |
| asof | B3: every list, detail, tab, dashboard figure and export as of a date; region then; approvals outstanding then; read-only mode |
| approvals | B2: per-region thresholds, held changes (202), visibility of pending changes, approve / reject / withdraw, self-approval and cross-region refusals |
| tasks | A6: tasks on customers, invoices and payments; assignees by person or role; My tasks |
| automation | A1–A5: triggers, AND/OR conditions, schedules, run now, all four actions, placeholders, durability across a backend kill |
| regression | Every pre-existing feature, for a single-region admin and a POC user |
| migration | Upgrading a copy of the developer's real database, compared against the pre-build version |

## Running it again

The harness is in [`scripts/`](scripts/); its rules are in [`scripts/README.md`](scripts/README.md). You need
Docker, Java 17, Maven, Flutter and Node. It runs on macOS: `lib.js` looks for Chromium in
`~/Library/Caches/ms-playwright`, or wherever `E2E_CHROMIUM` points.

```sh
cd docs/regression/2026-09-28/scripts
npm install
npx playwright-core install chromium   # once, if that Chromium is not already cached
./build-dist.sh                         # builds the backend jar and the web app from this repository
suites/regions/run.sh 7                 # round label 7; likewise approvals, tasks, automation, regression

# asof has no run.sh: bring its environment up, run it, bring it down
env/up.sh asof 2 --fresh && source runtime/asof/env.sh && node suites/asof/run.js 7; env/down.sh asof

# migration also needs the pre-build jar and the developer's gene-invoice-db container
./build-baseline.sh                     # builds commit 321150e into baseline-src/
suites/migration/run.sh 7
```

Each `run.sh <round>` brings up a fresh environment, runs the suite and always tears it down. Results go to
`runs/r<round>/<area>.json` inside `scripts/` (git-ignored). Each area uses a fixed environment index; only
`approvals` and `regions` accept a different one as a second argument.

Each area starts its own PostgreSQL database in the `gi-e2e-db` container, a backend on port 181xx and a web
server on 182xx, and removes them afterwards. The migration area takes a read-only `pg_dump` of the
`gene-invoice-db` container into `suites/migration/data/`; that file is git-ignored and must not be committed.

Use a round label that is a number: `final` made every suite write to `runs/rNaN/`.
