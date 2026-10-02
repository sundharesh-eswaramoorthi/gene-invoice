# GeneInvoice E2E harness: rules for testers

This harness is for end-to-end testing of the automation / regions / maker-checker / as-of build
(PRD Part A: A1–A6; Part B: B1–B3). What the software should do comes from the PRD, `DESIGN.md` §6
(REST contract), §7 (frontend) and §8 (tests), and the code. Anything the PRD leaves open is settled
by the defaults in `docs/implementation/hardening/TRIAGE.md` (userDecisions) and the round triage files in
`../data/`, so a behaviour that follows one of those
defaults is not a defect.

Paths used below:

| | |
|---|---|
| `E2E` | this folder, `docs/regression/2026-09-28/scripts` |
| `REPO` | the repository root, four levels up from `E2E` |
| Design | `docs/design/DESIGN.md` |
| Build logs | `docs/implementation/build/LOG-*.md`, `docs/implementation/hardening/FIXLOG-*.md` and `TRIAGE.md` |

## 1. Isolation (absolute)

- The developer's own services and databases may be running on the same machine. **Never send any of them a
  request. Never stop, restart or modify any of them.** The one exception is the migration suite's read-only
  `pg_dump` of the developer's `gene-invoice-db` container.
- You may use only these:
  - the Docker container **`gi-e2e-db`** (Postgres, host port **18432**, user/password `e2e`/`e2e`);
  - backends on ports **18100–18198**;
  - web servers on ports **18200–18298**.
- Only create or drop databases named `e2e_*`, and only inside `gi-e2e-db`.
- Never kill a process you did not start. `env/down.sh` stops only the PIDs recorded in
  `runtime/<area>/pids`, and only after checking each PID's command line.
- Git is read-only: `status`, `diff`, `log` and `show` only. Never add, commit, stash, checkout, restore,
  reset or clean.
- **Report only.** Do not edit `backend/` or `frontend/`, and do not build inside `REPO`. Only the
  harness's `build-dist.sh` (the setup step, and rebuild steps) builds there.

## 2. Environments: one per area, never shared

Every area gets its own database (`e2e_<area>`), its own backend and its own web server, so testers
never see each other's data. Totals, counts and dashboards are therefore exact for your area and
need no before/after deltas.

```bash
E2E=docs/regression/2026-09-28/scripts
$E2E/env/up.sh <area> <index> [--fresh]   # area: [a-z][a-z0-9_]*, index: 0..98
source $E2E/runtime/<area>/env.sh          # exports E2E_AREA, E2E_API, E2E_WEB, E2E_DB, E2E_RUNTIME
node my-script.js
$E2E/env/down.sh <area>                    # stops this area's processes, drops e2e_<area>
$E2E/env/psql.sh <area> "select ..."       # SQL against e2e_<area> only
```

- The backend is at `http://localhost:<18100+index>` and the web app at `http://localhost:<18200+index>/`.
  The backend runs `dist/backend.jar` with profile `dev`, `MAIL_TRANSPORT=none`, a Hikari pool of 5 and
  `-Xmx512m`. Its log is `runtime/<area>/backend.log` and its documents go to `runtime/<area>/documents`.
- **Start state.** The database is empty apart from what the seeder writes: the `admin`/`admin123` and
  `cashier`/`cashier123` logins, the seeded roles and the `DEFAULT` region.
  Startup takes about 5 seconds.
- **Idempotent.** Running `up.sh` again on the same index while the area is healthy only prints the
  URLs. `--fresh` (or `E2E_FRESH=1`) tears the area down first and gives you a new, empty database.
  `down.sh` is safe to run any number of times.
- The web bundle is built once with `API_BASE_URL=http://localhost:18199`. `up.sh` copies it into
  `runtime/<area>/web`, rewrites that URL to the area's own backend (the same length, and the replacement
  count is checked), and serves it with `env/serve.js` without caching.
- **Rebuild** (only when the orchestrator says so): `$E2E/build-dist.sh [backend|web]`, then
  `up.sh <area> <index> --fresh`.
- **Smoke test of the harness:** `$E2E/smoke.sh` (use `KEEP=1` to leave the env up). It writes
  `runs/r0/smoke.json` and its screenshots.

## 3. Helper library: `lib.js`

`const rt = require('../../lib.js')` from a `suites/<area>/` file.
It reads `E2E_API`, `E2E_WEB` and `E2E_DB` from the environment, so source `env.sh` first. It refuses
any API port outside 18100–18199. It resolves `playwright-core` from its own `node_modules`, so your
scripts can live anywhere.

### 3.1 API

| Helper | Notes |
|---|---|
| `api(method, path, {token, body, query, headers})` → `{status, ok, json, text, headers, ms}` | **Never throws on a status.** A network failure returns status 0. Arrays in `query` become comma lists, except `filter`, which is repeated. |
| `list(token, path, query, {regionIds, asOf})` | Pages through a whole list. The API accepts page sizes 10, 20 and 50 only. The envelope (`regionIds`, `bookRegionIds`, `asOf`, `totalElements`, `lockedFilters`) is the first page's. |
| `scoped(query, {regionIds, asOf})` | Adds the view scope (§6.0) to any query. |
| `need(r, what, allowedStatuses?)` | Throws unless the status is 2xx. Use it for fixtures only. |
| `login(u, p=Passw0rd!)`, `loginFull`, `adminToken()`, `cashierToken()`, `me(token)` | |
| `uniq(prefix)`, `day(offsetDays)` → `yyyy-MM-dd` (UTC), `sleep(ms)` | |
| `waitFor(fn, {timeoutMs=30000, intervalMs=1000})` | Polls until `fn` returns something truthy, and returns it; returns **null** on timeout. |
| `ensureRole`, `roleId`, `findUser`, `createStaff(token, role, prefix, {username, grants})` | **A user created without `grants` holds no region** (§4.6) and sees nothing regional. |
| `setGrants(token, userId, [{regionId, accessLevel, recordScope}])`, `getGrants` | Levels are `READ`, `MANAGE` and `APPROVE`. Scopes are `ALL` and `BOOK`. Returns the raw response, so you can assert on a 403 or 400. |
| `regions`, `defaultRegion`, `createRegion(token, {code, name})`, `getOrCreateRegion` | The creator is auto-granted APPROVE/ALL in the new region. |
| `setThresholds(token, regionId, [{operation, amount, windowHours}])`, `getThresholds` | Tightening answers 200. Loosening answers 202, with `.held` and `.pendingChange` set. |
| `ensureProduct`, `createCustomer(token, {name, regionId, paymentTerm, username})`, `addCustomerPoc(token, custId, 'SALES'\|'SUCCESS'\|'COLLECTION', userId)`, `moveCustomerRegion` | Payments need a Collection POC, and invoices need a Sales POC. |
| `createInvoice(token, {customerId, amount \| items, dueDate, invoiceDate, paymentTerm, salesPocUserId})`, `patchInvoice`, `cancelInvoice`, `createPayment(token, {customerId, amount, invoiceIds, collectionPocUserId})`, `createPromise`, `createDispute` | These **held-aware writes** never throw. Each result carries `.held` (a 202 PENDING_APPROVAL), `.pendingChange`, and `.data` (the DTO on a 200). A held response has no top-level `id`. |
| `approvals.{list, get, forRecord, summary, approve, reject, withdraw}` | |
| `tok.role(role, level='CUSTOMER'\|'RECORD')`, `tok.user(id)`, `tok.customer()` | EmailTokens: the role-at-a-level model used for assignees and recipients. |
| `createTask`, `tasks.{list, mine, mineCount, setStatus}` | |
| `rule({...})`, `action.{task, email, promise, dispute}(overrides)`, `automation.{create, update, get, list, enable, disable, remove, run, preview, runs, runById, effects, health, placeholders}` | See §6.14 for the RuleRequest shape. |
| `waitForRun(token, runId, {timeoutMs})` | Polls until the run leaves QUEUED/RUNNING. The consumer polls every 2 s; the schedule tick is every **30 s**. |
| `emailsFor(token, entityType, entityId)` | The mail transport is `none`, so outbound emails end `NOT_SENT` (or `NOT_CONFIGURED`). **Assert the persisted Email row and its rendered subject and body**, not delivery. |
| `sql("select ...")` | Runs against `E2E_DB` (which must be `e2e_*`). Returns rows as arrays of strings. |
| `ageWorld(days)` | See §3.3. |
| `seedWorld(adminToken)` | See §3.2. |
| `results({area, round})` | Writes the results file (§5). |

### 3.2 The standard world (`seedWorld`)

```
regions  DEFAULT (seeded), NORTH, SOUTH, WEST          admin: APPROVE/ALL in all four
role     E2E_REGIONAL = every regional privilege + PRODUCT_VIEW, NOTIFICATION_VIEW, EXPORT_DATA,
         REGION_VIEW, AUTOMATION_VIEW/MANAGE (no USER_/ROLE_ admin, no AUTOMATION_ADMIN):
         the GRANT decides what each user can do where.
users    (password Passw0rd!)
  nora   MANAGE/ALL North, READ/ALL South, nothing in West
  sam    APPROVE/ALL South, MANAGE/ALL North
  bob    MANAGE/BOOK North  (Collection+Sales POC of customer n1 -> his book)
  wendy  MANAGE/ALL West only
  cust   customer login of s1 (South)   [username 'cust'; later calls get 's1-<tag>']
customers n1 n2 (North), s1 s2 (South), w1 w2 (West), d1 (DEFAULT); names "<Region> <KEY> <tag>"
  each: primary Collection POC (n1 bob, n2 nora, s* sam, w* wendy, d1 admin);
        invoice 1000 due +30d; invoice 2500 (x1 customers: dated -40d, due -10d = overdue; else due +20d);
        payment 500 allocated to the first invoice
thresholds South: INVOICE_CREATE 10000, PAYMENT_RECORD 5000, INVOICE_CANCEL 5000, PROMISE_CREATE 5000 (24h)
          set AFTER the seed records, so none of those records is held
```

It returns `{regions:{DEFAULT,NORTH,SOUTH,WEST}, users:{nora,sam,bob,wendy,cust}, tokens:{admin,nora,sam,bob,wendy,cust},
customers:{n1..d1}, invoices:{n1:[inv1000, inv2500], ...}, payments:{n1:[p], ...}, product, role, thresholds}`.
Regions, the role and the users are get-or-create, so seedWorld can be called again. Customers and
records are new on every call.

### 3.3 As-of reads (B3) need history

In a fresh database `historyStartsOn` is null and every record was created today. `asOf=<yesterday>`
is therefore valid but shows an empty world. A back-dated `invoiceDate` does **not** create history.
To see "as it was then", do this:

1. Seed.
2. `rt.ageWorld(10)`. This shifts **every** timestamp and date column in `e2e_<area>` back 10 days: records,
   history versions, audit, outbox, `history_meta` and due dates.
3. Make the change you want to observe now: a region move, an approval, an edit.
4. Read with `asOf: rt.day(-2)` to get the old state, and without `asOf` to get the new one.

Caveats:
- Scheduled rules' next-run times move back as well, so they may catch up on the next tick.
- Invoices look 10 days older, so days overdue grow.
- Re-read ids and versions after ageing. `@Version` columns are not touched.

## 4. Driving the Flutter app (CanvasKit)

The page is a canvas, so you work through Flutter's accessibility (semantics) tree.

- `const app = await rt.openApp({token} | {username, password} | {}, {width, height, path})` returns
  `{page, apiErrors, pageErrors, requests, close}`.
  - With neither a token nor a username you get the login screen. Use `rt.uiLogin(page, 'admin', 'admin123')`
    to sign in through it.
  - `requests` lists every API call the app made, with its query. Use it to assert that `regionIds` and
    `asOf` were sent.
- `rt.go(page, '/invoices')` navigates (hash routing). Routes: `/`, `/customers[/:id]`, `/invoices[/:id|/new]`,
  `/payments[/:id]`, `/promises[/:id]`, `/disputes[/:id]`, `/tasks[/:id]`, `/my-tasks`, `/approvals[/:id]`,
  `/approval-thresholds`, `/automation[/:id|/new]`, `/regions[/:id]`, `/users[/:id]`, `/roles[/:id]`,
  `/products[/:id]`, `/notifications`, `/inbox`.
- `rt.semantics(page)` returns every node as `{role, label, text, checked, selected, expanded, disabled, x, y, w, h}`.
  `rt.screenText(page)` returns every visible string, split into lines. `rt.find` and `rt.exists` take a
  string (exact match) or a RegExp. When `find` fails, it throws and lists what is on screen.
- `rt.tap(page, 'Save')` dispatches a click on the node. That works for buttons, tabs, chips, checkboxes and dialog buttons.
  `rt.press(page, matcher)` does a real mouse click at the node's centre. Use it for popup-menu items.
  `rt.clickAt(page, x, y)` clicks raw coordinates.
- **Text fields** show up as `<input>` nodes with role `textbox` and label = labelText. Use
  `rt.fillField(page, 'Username', 'admin')` to click, clear and type, or `rt.typeText` into whatever has focus.
- **Menus and dialogs.** Keyboard Escape does not reliably reach the canvas. `rt.dismiss(page)` taps the
  "Dismiss" / "Dismiss menu" barrier.
- **Region selector** (app bar; text `Regions: <label>`; hidden when the user holds ≤ 1 region, and for
  customer logins): `rt.regionSelector(page)`. `rt.chooseRegions(page, [/^WEST/, 'All my regions'])`
  **toggles** each item; each pick closes the menu. Menu items are `menuitemcheckbox` nodes labelled
  `CODE · Name` with `checked`.
- **As-of chip** (staff only): `rt.chooseAsOf(page, '2026-09-25')` opens the date picker, switches it to
  text input, types MM/DD/YYYY and taps View. It returns the chip text ("As of 25 Sep 2026").
  `rt.chooseAsOf(page, null)` goes back to today. Historical mode shows a banner reading
  "Viewing … — read only".
- **Merged nodes.** A whole list row, stat tile or chart card is one node with a newline-joined label. To hit
  a link inside it, screenshot, read the position, then use `rt.clickAt`.
- **The collapsed sidebar icons have no semantics label.** Navigate with `rt.go` instead.
- `rt.shot(page, dir, name)` saves `<dir>/<name>.png` and returns the path. Read the PNG to see the screen.

## 5. Results

Write **one file per area per round** to `$E2E/runs/r<N>/<area>.json`:

```json
{"area":"b2-maker-checker","round":1,"env":{"api":"http://localhost:18103","web":"http://localhost:18203/"},
 "cases":[{"id":"B2-07","feature":"Held payment","kind":"API","prd":"B2",
   "title":"A payment above South's PAYMENT_RECORD limit is held, not applied",
   "status":"PASS","severity":"",
   "steps":"seedWorld; sam POST /api/payments {customerId:s2, amount:6000}",
   "expected":"202 PENDING_APPROVAL, Location /api/approvals/{id}; customer balance unchanged",
   "actual":"202, pendingChange.id=3, balance unchanged",
   "evidence":"GET /api/payments?filter=customerId:eq:12 -> 1 row (the seed payment)",
   "screenshot":"","codeRef":"backend/src/main/java/com/geneinvoice/approval/MakerChecker.java:120"}]}
```

- `kind` is `API` or `UI`. `prd` is `A1`–`A6` or `B1`–`B3`.
- `status` is one of these:
  - `PASS`
  - `FAIL`
  - `BLOCKED` (a defect or environment problem stopped the case)
  - `NOT_TESTED`
- `severity` is set only on a FAIL:
  - `high`: data loss, wrong money, a rule breaking or slowing a save, a region or approval bypass, a feature unusable;
  - `medium`: wrong behaviour with a workaround;
  - `low`: cosmetic, wording, minor UX.
- `actual` on a FAIL must say **what you observed**: the exact status, body excerpt and ids, so someone
  else can reproduce it.
- `codeRef` is the `file:line` you believe is responsible, when you can find it.
- `rt.results({area, round})` gives `{rec, save, shotsDir}`. `rec({... ok: bool})` maps `ok` to PASS/FAIL,
  defaults a FAIL's severity to `medium`, and replaces a case with the same id.

**A defect you cannot reproduce on a second attempt is `NOT_TESTED`, with a note, not `FAIL`.**
Re-run every FAIL once, from a clean state if you can, before recording it.

### Evidence and screenshots

- Save screenshots to `$E2E/runs/r<N>/shots/<area>/<caseId>-<n>.png` (`R.shotsDir`), and put the path in
  `screenshot`. Every UI FAIL needs one.
- `evidence` holds the request and response excerpts that prove the verdict, or a pointer such as
  `runtime/<area>/backend.log` plus a grep pattern for server-side errors.
- For async automation, record the run id, its status and counters, and its effects (`automation.effects`).

## 6. Known environment conditions (not defects)

- Mail transport is `none`, so outbound emails are saved with status `NOT_SENT` or `NOT_CONFIGURED`.
  Rules show a warning that email delivery is not configured.
- A fresh database has no history before today (§3.3).
- Page size must be 10, 20 or 50. A 400 "size must be one of [10, 20, 50]" is by design.
- `POST /api/users` without `regionGrants` creates a user with no region access, by design (§4.6).
- Defaults recorded in `docs/implementation/hardening/TRIAGE.md` and the round triage files (userDecisions) are
  intended behaviour.
