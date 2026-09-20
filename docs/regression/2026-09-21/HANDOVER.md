# Handover — Gene Invoice, targeted regression, 21 Sep 2026

Start a new session with: *"Read `docs/regression/2026-09-21/HANDOVER.md` and continue."*

## Where things stand

- **Verdict: the three new features are ready.** 343 cases, 337 passed, 6 failed — 0 high, 4 medium,
  2 low. No high-severity defect exists in invoice due dates, ageing, or documents.
- **One high-severity defect (D-01) blocks upgrading an existing database**, and it predates this
  work. It is an email migration gap, not a feature defect. A fresh database is unaffected.
- The features under test came from
  [`docs/requirements/invoice-due-dates-and-documents.md`](../../requirements/invoice-due-dates-and-documents.md)
  and were already implemented, with an implementation doc, before this run began.
- Automated suites were green before and during: backend `mvn verify` **679 tests**, `flutter analyze`
  clean, `flutter test` **254 tests**.

## What is in this folder

| Path | What it is |
|---|---|
| `HANDOVER.md` | This file — start here |
| `report/index.html` | The interactive report (open in a browser; works offline). Reused from the 15 Sep run, driven by `report/data.js` |
| `defects.md` | All 6 defects: severity, repro, expected/actual, root cause, suggested fix. D-06 is recorded as NOT A DEFECT with its re-check |
| `test-results.md` | Totals per area, failures, acceptance-criterion coverage, every case, gaps and recommendations |
| `test-cases.csv` | All 343 cases (area, id, feature, kind, ac, title, status, severity, steps, expected, actual, evidence, codeRef) |
| `report/shots/` | Screenshot evidence |
| `data/area-[a-f].json` | Each tester's raw results |
| `data/role-matrix-observed.md` | The role × capability matrix as actually observed, for all 7 roles across all three features — lift this into the implementation doc to fix D-03/D-04 |
| `data/narrative.json` | Headline, summary, defects, gaps, recommendations — the hand-authored input to `compile.js` |
| `scripts/` | Each area's test scripts, `lib.js`, `TESTER-RULES.md`, and `compile.js` |

**To regenerate the report after editing `data/narrative.json` or any `data/area-*.json`:**
`cd scripts && node compile.js` — it rewrites `test-cases.csv`, `test-results.md` and `report/data.js`.

## The defects

| ID | Sev | Title | Status |
|---|---|---|---|
| D-01 | high | `emails` schema unmigrated on an existing database — every email endpoint 500s | OPEN |
| D-02 | medium | Null byte in an uploaded filename → 500, inconsistently by byte position | OPEN |
| D-03 | medium | Implementation doc's role matrix misstates POC manage scope and book scoping | OPEN |
| D-04 | low | No role × capability matrix for Features A and B | OPEN |
| D-05 | low | No explanation when Upload is hidden from a user without `DOCUMENT_MANAGE` | OPEN |
| D-06 | — | "File Files of this kind cannot be attached" | **NOT A DEFECT** — stale bundle, re-checked PASS |

**Fix D-01 before any deployment onto an existing database.** Everything else can ship.

## Running environments

Four things are listening. **The user's own deployment is 8081/8082 — do not test against it.**

| | The user's deployment | The regression environment |
|---|---|---|
| Backend | `:8082`, DB `geneinvoice` | `:8083`, DB `geneinvoice_rt` |
| Web | `:8081` from `frontend/build/web` | `:8084` from `/tmp/gene-invoice-web-rt` |
| Documents | `~/gene-invoice-documents` | `~/gene-invoice-documents-rt` |
| State | **Upgraded to current code during this run** | Fresh, created for this run |

Both run `backend/target/gene-invoice-backend-0.0.1-SNAPSHOT.jar` (built 20 Sep 23:58), Postgres
container `gene-invoice-db` on `localhost:5433`. Logins `admin/admin123`, `cashier/cashier123`.

**The regression environment (8083/8084 and the `geneinvoice_rt` database) is still up.** Per the
15 Sep handover's convention it should be removed once its results are accepted, leaving only the
user's one environment — but that is the user's call, not a session's.

### What was done to the user's deployment

It was serving code from 17 Sep — four days stale, with no due dates, no documents and the old
ageing. It was redeployed onto the current jar. **The database was backed up first:**
`~/gene-invoice-db-backups/2026-09-21/geneinvoice-pre-duedate.sql.gz` (467K). Restore with
`gunzip -c <file> | docker exec -i gene-invoice-db psql -U geneinvoice -d <db>`.

`InvoiceSchemaUpgrade` then ran against real data and worked correctly: **1,015 invoices backfilled
to Net 30, then `invoices.due_date` set NOT NULL**, with no change to `status` or `paid_amount`.
That is the only observation of the backfill against a populated database — the test database was
empty, so it filled 0 rows there.

## Things learned the hard way

- **The working tree moved under the run.** Thirteen frontend files changed while testing was in
  progress; `api_client.dart` was edited two minutes after the test bundle was built. One reported
  defect (D-06) turned out to be a stale-bundle artefact. **Before trusting any UI result, check
  the served `main.dart.js` mtime against the source.** No backend file changed after the jar was
  built, so the API results are sound.
- **Green tests do not cover migration.** 679 backend tests pass against `create-drop` schemas, so
  no test in the repository exercises an upgrade of an existing database. That is precisely the gap
  D-01 lives in.
- **Test authorization by direct id, not through the UI.** Area E's whole value came from requesting
  foreign document ids directly; a UI-driven test would have found nothing, because the UI never
  offers the link.
- The ageing endpoint scopes through the **sales** book, so assertions on it must be made as a
  Sales POC to be isolated from other testers' data.
- `COLLECTION_POC` and `CUSTOMER_SUCCESS_POC` carry `SCOPE_OVERRIDE`, so they report ageing
  `Coverage: ALL`. That is intended, and it is why D-03's doc error matters.

## Suggested next steps

1. **Fix D-01** — write the email backfill the way `InvoiceSchemaUpgrade` is written, and make failed
   DDL loud rather than a WARN nobody reads.
2. **Adopt Flyway.** Four hand-written `*SchemaUpgrade` classes now exist, one incomplete, and
   nothing verifies a database has had them all applied. D-01 is what that costs.
3. **Add CI** — `mvn verify`, `flutter analyze`, `flutter test`. There is still no `.github/workflows`.
4. **Fix D-02**, then the two doc defects (D-03, D-04) from `data/role-matrix-observed.md`.
5. **Resolve the AC-C20 / AC-C22 contradiction** in the PRD (D-05) so the next implementer is not
   asked to satisfy both.
6. Two product decisions that testing surfaced rather than defects: a customer login **can** upload
   documents (recorded in the implementation doc as a deliberate departure from AC-C12), and
   `PUT /api/customers/{id}` silently clears a customer's payment terms when the field is omitted.
   Both behave correctly as built; both deserve a conscious yes.

## What was not tested

Listed in full under "What was not tested" in `test-results.md`. The two that matter most: there was
**no independent verifier pass** (each tester reproduced its own failures, but no second agent
re-confirmed them), and after the mid-run rebuild only the invoice detail and Documents surfaces
were re-exercised — the dashboard, customer, payment and promise screens were tested only against
the earlier bundle.
