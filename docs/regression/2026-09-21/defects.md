# Defects — regression run 2026-09-21

Targeted run on invoice due dates, ageing by days past due, and document attachments
([PRD](../../requirements/invoice-due-dates-and-documents.md)), plus blast radius.

| ID | Severity | Title | Status |
|---|---|---|---|
| D-01 | high | Deploying current code onto an existing database leaves the `emails` schema unmigrated — email is 500 | **FIXED** (scope revised — see below) |
| D-02 | medium | A null byte in an uploaded filename returns 500 instead of a validation error, inconsistently by position | **FIXED** |
| D-03 | medium | The implementation doc's document role matrix misstates both what the POC roles can manage and how far they can reach | **FIXED** |
| D-04 | low | The implementation doc carries no role x capability matrix for Features A and B, which PRD section 8 requires | **FIXED** |
| D-05 | low | A user without DOCUMENT_MANAGE is given no explanation for the missing Upload button | **FIXED** (PRD amended; UI unchanged) |
| D-06 | — | A refused upload reads "File Files of this kind cannot be attached" | **NOT A DEFECT** — stale test bundle; re-checked PASS on the rebuild |

---

## D-01 — Deploying current code onto an existing database leaves the `emails` schema unmigrated, and every email endpoint returns 500

- **Severity:** high · **Kind:** API / migration · **Area:** email (found while deploying for this run)
- **Found:** before test execution, while bringing the user's 8081/8082 deployment up to current code
- **Not caused by** the due-date or document work. It is a pre-existing gap in the email feature's
  migration path, exposed the first time current code was deployed onto a database created by an
  older version. Logged here because it blocks email testing and would break a real upgrade.

**Reproduce**

```text
Take a database created by an older build (here: `geneinvoice`, the 15-16 Sep demo data, last
served by a backend running since 17 Sep). Deploy the current jar against it with ddl-auto: update.
Then, as admin:
  GET /api/emails?entityType=INVOICE&entityId=1
  GET /api/inbox
```

**Expected**

Both return 200. Either `ddl-auto: update` brings the `emails` table up to the current entity, or a
schema-upgrade component migrates it the way `InvoiceSchemaUpgrade` migrates `invoices`.

**Actual**

Both return **500** (`{"status":500,"error":"Internal Server Error","message":"Unexpected error"}`).
The underlying exception is:

```text
org.postgresql.util.PSQLException: ERROR: column e1_0.attempts does not exist
```

The `emails` table is still on the pre-refactor schema. It has 30 columns — `customer_id`,
`invoice_id`, `sent_at`, `from_label`, `delivery_status` — and **none** of the columns the current
`Email` entity maps: `entity_type`, `entity_id`, `entity_label`, `occurred_at`, `status`, `attempts`.
102 rows sit in it.

Startup logs **17 `CommandAcceptanceException`s**, failing to create three indexes because their
columns are absent:

```text
create index idx_email_dispatch   on emails (status, next_attempt_at)        -> column "status" does not exist
create index idx_email_entity     on emails (entity_type, entity_id, ...)    -> column "entity_type" does not exist
create index idx_email_recipient_inbox on email_recipients (...)             -> column "field" does not exist
```

The app starts anyway and reports itself healthy — the failures are logged at WARN and nothing
surfaces them.

**Root cause**

```text
Two things combine.

1. backend/src/main/java/com/geneinvoice/email/EmailSchemaUpgrade.java:42 is the only migration the
   email feature ships, and it only widens an enum check constraint:
       widen(connection, "emails", "status", EmailStatus.class)
   It adds no columns. On this database it is a no-op, because `status` does not exist to widen.

2. spring.jpa.hibernate.ddl-auto: update (application.yml, all profiles) cannot add a NOT NULL
   column to a table that already has rows. The current Email entity declares entity_type,
   entity_id, entity_label and status as nullable = false, so Hibernate's ALTER statements fail
   against a populated `emails` table and are logged as warnings rather than stopping startup.

The email entity was refactored from per-record foreign keys (customer_id, invoice_id) to the
polymorphic entity_type/entity_id shape without a data migration to carry existing rows across.

Contrast InvoiceSchemaUpgrade, which does this correctly for the same class of change: add the
column nullable, backfill every row, then enforce NOT NULL. On the same deployment it migrated
1,015 invoices cleanly ("Backfilled 1015 invoice due dates using Net 30", "invoices.due_date is
now not null").
```

**Suggested fix**

```text
Give the email feature the treatment InvoiceSchemaUpgrade already demonstrates:

1. Add the new columns nullable.
2. Backfill: entity_type/entity_id from the old customer_id and invoice_id columns (a row with
   invoice_id -> INVOICE, else customer_id -> CUSTOMER); occurred_at from sent_at; status from
   delivery_status through an explicit value mapping; attempts to 0.
3. Enforce NOT NULL, then create the three indexes.
4. Drop the superseded columns only once the backfill is verified.

Separately, consider making DDL failure loud. A feature silently losing three indexes and every
query against a table, while the app reports itself started, is the part that let this sit
unnoticed since the email refactor. At minimum log these at ERROR; better, have each
*SchemaUpgrade verify its table matches the entity and fail startup with a named message, the way
LocalDocumentStorage fails when DOCUMENT_ROOT is unusable.

This is also the strongest argument yet for Flyway: three of these hand-written *SchemaUpgrade
classes now exist (customer, email, invoice, poc), one of them incomplete, and nothing checks that
a given database has had them all applied.
```

**Evidence**

```text
/tmp/gene-invoice-8082.log (17 CommandAcceptanceException at startup; "column e1_0.attempts does
not exist" on the /api/emails request)
docker exec gene-invoice-db psql -U geneinvoice -d geneinvoice -c "\d emails"  (30 pre-refactor columns)
Database backed up before the deploy: ~/gene-invoice-db-backups/2026-09-21/geneinvoice-pre-duedate.sql.gz
```

**Scope**

```text
Affects an upgrade of an existing deployment only. A fresh database is unaffected: the same jar on
the empty geneinvoice_rt database (8083) creates the current schema correctly, and the automated
suites (679 backend tests) run against create-drop schemas, which is why this never appeared in CI.
The `documents` table created cleanly on both, being new with no existing rows.
```

---

## D-02 — A null byte in an uploaded filename returns 500 instead of a validation error, and the answer depends on where the byte sits

- **Severity:** medium · **Kind:** API · **Area:** documents (C)
- **Test cases:** C-38, C-43 · **AC:** AC-C8
- **Reproduced on three separate runs.**

**Reproduce**

```text
POST /api/documents (multipart) to any record, with a filename containing a raw null byte:
  "shell\0.pdf"       -> 500
  "\0boot.pdf"        -> 201, stored as filename "boot.pdf"
  "\0"                -> 201, stored as filename "file"
  "report\0.pdf"      -> 500   (before the extension)
```

**Expected**

The app's standard validation error (400 `Validation Failed` with a `fieldErrors` entry), the same
shape every other rejected upload returns — an over-size file, a disallowed content type and a
traversal attempt all answer that way. `DocumentRules.cleanFilename` already strips control
characters, so the byte should simply be removed and the upload accepted, or refused cleanly.

**Actual**

**HTTP 500 `{"message":"Unexpected error"}`** when the null byte sits in the middle of the name or
immediately before the extension. When it leads the name, or is the whole name, the upload succeeds
(201) with a sanitised filename. So the same class of input produces 201 or 500 depending only on
byte position.

Backend log:

```text
org.springframework.web.multipart.MultipartException: Failed to parse multipart servlet request
  caused by org.apache.tomcat.util.http.fileupload.InvalidFileNameException
```

**Root cause**

```text
Tomcat's multipart parser rejects the filename before Spring ever binds the request, throwing
InvalidFileNameException wrapped in MultipartException. That happens upstream of the controller, so
DocumentRules.cleanFilename — which would have stripped the byte — is never reached.

backend/src/main/java/com/geneinvoice/document/DocumentUploadAdvice.java handles only
MaxUploadSizeExceededException, so nothing maps MultipartException, and GlobalExceptionHandler's
catch-all answers 500.

The position dependence is Tomcat's, not the app's: the parser only raises InvalidFileNameException
for certain placements, and the rest reach cleanFilename and are sanitised normally.
```

**Suggested fix**

```text
Add MultipartException (and InvalidFileNameException specifically) to DocumentUploadAdvice, mapping
it to the same 400 validation shape the other refusals use, with a field error naming the file.
Then assert both placements in a test, so the answer stops depending on byte position.
```

**Security impact — none found**

```text
The tester probed this specifically. Nothing escapes the storage root, no path is written, the
response body leaks no stack trace or internal detail, and the server keeps serving normally
immediately afterwards (C-42: list 200 and a normal upload 201 straight after every attack).
This is a wrong-status / unhandled-exception defect, not a bypass.
```

**Evidence**

```text
docs/regression/2026-09-21/scripts/area-c/ (run.js, helpers.js — builds multipart bodies by hand so
a filename can carry raw bytes); data/area-c.json cases C-38, C-42, C-43
```

---

## D-03 — The implementation doc's document role matrix misstates both what the POC roles can manage and how far they can reach

- **Severity:** medium · **Kind:** documentation · **Area:** permissions (E)
- **Test cases:** E-33, E-34 · **AC:** AC-C10, AC-C11
- **The code is right; the document is wrong.** No authorization bypass was found anywhere in area E.
  Both errors sit on the same line of the matrix, so one edit fixes them.

**Where**

```text
docs/implementation/invoice-due-dates-and-documents.md, line 266:
  | SALES / CUSTOMER_SUCCESS / COLLECTION POC | ✓ | ✓ |
described as access "inside their POC book (ScopeResolver)".
```

**Error 1 — Manage is not uniform across record kinds (E-33)**

```text
The doc reads as though each POC role can manage documents on every record kind. In reality each
can only manage documents on the record kind whose manage privilege it holds:

  SALES_POC             invoices only   (customer 403, payment 404)
  CUSTOMER_SUCCESS_POC  customers only  (invoice 403,  payment 403)
  COLLECTION_POC        payments only   (customer 403, invoice 403)

This is AC-C10 working exactly as specified — DOCUMENT_MANAGE *plus* the parent record's manage
privilege. The matrix just does not say so.
```

**Error 2 — Only one of the three POC roles is book-limited (E-34)**

```text
The doc says POC document access is bounded by the POC book. Only SALES_POC is.
CUSTOMER_SUCCESS_POC and COLLECTION_POC carry SCOPE_OVERRIDE (DataSeeder.java:138 and :153), so
they reached records they hold no seat on: 200 on the record, 200 on its document list, and 200
downloading an INTERNAL document on custB / invB / payB, plus 201 uploads on the record kind they
can manage.

This is pre-existing, intended role design, consistent with the same roles reporting ageing
Coverage ALL rather than BOOK (area B, B-37). It is NOT a regression and NOT a bypass. But the
implementation doc currently states the opposite of the real security posture, which is the kind
of error that gets a role handed to someone on the strength of the doc.
```

**Suggested fix**

```text
Replace the single POC row with one row per POC role, giving each its manageable record kinds, and
state plainly which roles carry SCOPE_OVERRIDE and therefore are not book-limited. The observed,
verified matrix is in docs/regression/2026-09-21/data/role-matrix-observed.md — it can be lifted
straight in.

Worth a separate decision, outside this defect: whether CUSTOMER_SUCCESS_POC and COLLECTION_POC
should be able to read INTERNAL documents on records outside their book at all. The behaviour is
intended today; whether it should be is a product question the doc error was hiding.
```

**Evidence**

```text
data/area-e.json cases E-33, E-34; data/role-matrix-observed.md;
scripts/area-e/05-pocscope.js and scripts/area-e/out/*.json
```

---

## D-04 — The implementation doc carries no role x capability matrix for Features A and B

- **Severity:** low · **Kind:** documentation · **Area:** permissions (E)
- **Test case:** E-35

**Expected**

PRD section 8 (Cross-cutting requirements) says the implementing session "must produce a role x
capability matrix covering ADMIN, CASHIER, VIEWER, CUSTOMER and the three POC roles" **for both
features** — due dates and documents alike.

**Actual**

The only matrix in the implementation doc (lines 261-267) covers document view/manage. Nothing
states who may set a customer's payment terms, override an invoice due date, filter by overdue, or
what ageing coverage each role receives.

**Suggested fix**

```text
Add the Feature A and B rows. They were built empirically during this run and verified against the
live app: docs/regression/2026-09-21/data/role-matrix-observed.md covers all 7 seeded roles against
every new capability across all three features.
```

---

## D-05 — A user without `DOCUMENT_MANAGE` is given no explanation for the missing Upload button

- **Severity:** low · **Kind:** UI · **Area:** documents UI (D)
- **Test case:** D-18 · **AC:** AC-C20
- **Screenshot:** `report/shots/D-18-1.png`

**Expected**

AC-C20: upload "disables itself with an explanation for users without `DOCUMENT_MANAGE`".

**Actual**

The Upload button is simply **absent** and nothing on the tab says why. A user who expects to attach
a file sees a list with no way to add to it and no reason given.

AC-C22 ("no visible button that will predictably 403") **is** met — this is the tension between the
two criteria. The implementation doc resolves it in favour of C22, recording only that "buttons that
would 403 are not shown", without noting that C20's explanation was dropped.

**Suggested fix**

```text
Either honour AC-C20 with a short line where the button would be ("You do not have permission to
add documents"), or amend the implementation doc to record the deliberate choice of C22 over C20 so
the two criteria stop contradicting each other. The first is a few lines in
frontend/lib/features/documents/documents_tab.dart:172; the second is a doc edit.
```

---

## D-06 — A refused upload reads "File Files of this kind cannot be attached" — the field label is glued onto a message that is already a sentence

- **Severity:** low · **Kind:** UI · **Area:** documents UI (D)
- **Test case:** D-35 · **AC:** AC-C21
- **Status:** **NOT A DEFECT.** The run tested a stale bundle. Re-checked against a rebuild and it passes.

**Actual (as observed during the run)**

```text
"File Files of this kind cannot be attached (PDF, PNG, JPEG, Word or Excel only)"
```

**Why it appeared**

```text
The web bundle served on :8084 was built at 00:10. frontend/lib/core/api/api_client.dart was edited
at 00:12 — two minutes later, while the run was in progress — and its _fieldMessage function fixes
exactly this string. Its comment names the defect verbatim:

  "The app's own refusals are whole sentences already ... and gluing the field on gave
   'File Files of this kind cannot be attached (...)'"

_fieldMessage now shows a message starting with a capital letter exactly as the server wrote it,
and only prefixes the field label onto a lower-case fragment (Bean Validation's "must be at most
500 characters").

This is a reporting artefact of a working tree that moved under the run, not a defect in the code
as it stands. See the run's HANDOVER note on mid-run source changes.
```

**Re-check (after rebuilding :8084 from current source)**

```text
The rebuild was confirmed independently before re-testing: the served main.dart.js is now
3,832,125 bytes (was 3,828,422) and contains the fragment-detection logic that the old bundle
lacked.

The refused upload now reads exactly:
    "Files of this kind cannot be attached (PDF, PNG, JPEG, Word or Excel only)"
— the server's own sentence, with no "File" label in front, matching the form's client-side
wording. Case D-35 re-scored PASS. The stale screenshot was removed.
```

---

# Fixes — 21 Sep 2026

All five defects are addressed. D-01's scope was revised first, because its stated root cause turned out to rest
on a false premise.

## D-01 — revised, then fixed

**What the original entry assumed:** that a released version of the app had shipped the pre-refactor `emails`
schema, so an upgrade needed a data migration carrying those rows across.

**What is actually true:** the email feature has never been committed. `git ls-files` finds **0 of its 29 files**
tracked, so `Email.java` does not exist at HEAD. The `emails` table in the user's demo database was created by an
**earlier, in-progress iteration** of the feature that has since been rewritten — not by any release.

The data settles it. `email_recipients.kind` holds `ROLE` (48) and `DIRECT` (154), while the current
`RecipientField` is `TO`/`CC`. Those are not renamed columns; they are different concepts — the old design
recorded *how* a recipient was named, the new one records *which header field* they sit in. The old rows carry no
TO/CC information at all, so a migration could only invent it. Three further tables (`email_entity_links`,
`email_recipient_reads`, `email_delivery_attempts`) match no current entity, so that database has been through
several divergent development schemas.

Writing the suggested backfill would therefore have meant fitting permanent code to one abandoned local schema
and fabricating data. **It was not written.**

**What was fixed instead — the defect underneath.** The app logged 17 failed DDL statements at WARN, announced
itself started, and then answered 500 on every request against the table it had failed to migrate. Silent schema
drift is the real defect, and it is the reason this went unnoticed.

```text
backend/src/main/resources/application.yml
  spring.jpa.properties.hibernate.hbm2ddl.halt_on_error: ${SCHEMA_HALT_ON_ERROR:true}
```

A schema `ddl-auto: update` cannot apply now stops startup, naming the table and column, while somebody is
watching and before any user meets it. `SCHEMA_HALT_ON_ERROR=false` starts anyway, for a database whose drift is
known and being dealt with.

**Test:** `backend/src/test/java/com/geneinvoice/config/SchemaDriftTest.java` — boots the real application against
a database holding a `documents` table an older version might have left behind (no entity columns, one row) and
asserts startup fails naming that table; a second case asserts the escape hatch still starts.

**Consequence for the user's 8082 deployment, by their decision:** its data was left untouched, so it will now
**refuse to start** until its stale email tables are dealt with, or it is started with `SCHEMA_HALT_ON_ERROR=false`.
It is still running on the process started before this change.

## D-02 — fixed

```text
backend/src/main/java/com/geneinvoice/document/DocumentUploadAdvice.java
  + @ExceptionHandler(MultipartException.class) -> 400 with fieldErrors.file
backend/src/main/java/com/geneinvoice/document/DocumentRules.java
  + BAD_FILENAME = "The file's name cannot be read. Rename it and try again"
```

`MaxUploadSizeExceededException` is itself a `MultipartException`; it keeps its own handler, because Spring picks
the closest match and that one names which limit was passed.

**Test:** `backend/src/test/java/com/geneinvoice/document/DocumentUploadFilenameTest.java`. These cases cannot be
written against `MockMvc` — a `MockMultipartFile` hands the filename straight to the controller, while the defect
lives in Tomcat's parser. The test boots a real server and writes the multipart body by hand, because a byte a
normal HTTP client would not send has to be put on the wire deliberately.

It was checked against the unfixed code: with the new handler commented out the test fails on `expected 500 not
to be 500`, and passes with it. The assertion is that a name a user chose is never answered with a fault — which
placements Tomcat refuses is its business and may change between versions.

## D-03 and D-04 — fixed

`docs/implementation/invoice-due-dates-and-documents.md`:

- The single POC row in §4.5 became one row per role, each naming the record kind it can manage, plus a note that
  only `SALES_POC` is book-limited and that the other two carry `SCOPE_OVERRIDE` — the same grant that makes their
  ageing coverage `ALL`. It also records that whether those roles *should* reach outside their book is an open
  product question rather than a defect.
- A new §3.1 carries the Feature A and B matrix PRD §8 required, for all seven roles.

Both come from `data/role-matrix-observed.md`, measured against the running app rather than read off the code.

## D-05 — fixed by amending the specification, not the UI

The user's decision: keep the UI as built and resolve the contradiction in the documents.

PRD **AC-C20** no longer asks for a disabled control with an explanation; it now says the control is absent for a
user without `DOCUMENT_MANAGE` and defers to AC-C22, with a note recording the amendment. The implementation doc's
§4.6 records the same, and notes that if the explanation is ever wanted it belongs as a line of text where the
button would be — a line of text cannot be pressed and 403.
