# Role × capability matrix — **observed**, run 2026-09-21 (Area E)

Every cell below was produced by a real HTTP call against `http://localhost:8083` with a freshly
created user of that role (data prefix `e`). Raw evidence:
`docs/regression/2026-09-21/scripts/area-e/out/{probe,security,extra,uploader,pocscope}.json`.

Fixtures: `custA` (payment terms `NET_45`, self-service login = the CUSTOMER row, a SUCCESS seat for
the CUSTOMER_SUCCESS_POC user and a COLLECTION seat for the COLLECTION_POC user), `invA` on `custA`
with the SALES_POC user as its Sales POC, `payA` on `custA`. `custB` / `invB` / `payB` are the
"foreign" records: no seat, no ownership for any of the three probe POCs.

---

## 1. Privileges each seeded role actually holds (`GET /api/roles`)

| Role | `DOCUMENT_VIEW` | `DOCUMENT_MANAGE` | `EXPORT_DATA` | `SCOPE_OVERRIDE` | record manage privileges held |
|---|---|---|---|---|---|
| ADMIN | ✓ | ✓ | ✓ | ✓ | CUSTOMER_MANAGE, INVOICE_MANAGE, PAYMENT_MANAGE |
| CASHIER | ✓ | ✓ | ✓ | ✓ | CUSTOMER_MANAGE, INVOICE_MANAGE, PAYMENT_MANAGE |
| VIEWER | ✓ | — | — | ✓ | none |
| CUSTOMER | ✓ | ✓ | — | — | none (uses the record's *view* privilege — §4.5) |
| SALES_POC | ✓ | ✓ | ✓ | — | INVOICE_MANAGE only |
| CUSTOMER_SUCCESS_POC | ✓ | ✓ | ✓ | ✓ | CUSTOMER_MANAGE only |
| COLLECTION_POC | ✓ | ✓ | ✓ | ✓ | PAYMENT_MANAGE only |

Matches `DataSeeder.java:65-157` exactly. `DOCUMENT_VIEW` and `DOCUMENT_MANAGE` each exist as exactly
one `privileges` row, and no role's privilege list contains a duplicate.

---

## 2. Feature A / B capabilities

`custT` = a throwaway customer; `custA` has `NET_45`, so a due date default of
`invoiceDate + 45 days` is the expected answer.

| Role | Set a customer's payment terms | Create an invoice | Due date defaults correctly | Override a due date | Read ageing | Ageing coverage | Filter invoices by overdue | Export invoices CSV |
|---|---|---|---|---|---|---|---|---|
| ADMIN | ✓ 200 | ✓ 200 | ✓ `+45d`, term `NET_45` | ✓ 200 → term `CUSTOM` | ✓ 200 | `ALL` | ✓ 200, unrestricted | ✓ 200 |
| CASHIER | ✓ 200 | ✓ 200 | ✓ `+45d`, term `NET_45` | ✓ 200 → term `CUSTOM` | ✓ 200 | `ALL` | ✓ 200, unrestricted | ✓ 200 |
| VIEWER | ✗ 403 | ✗ 403 | n/a | ✗ 403 | ✓ 200 | `ALL` | ✓ 200, unrestricted | **✗ 403** (no `EXPORT_DATA`) |
| CUSTOMER | ✗ 403 | ✗ 403 | n/a | ✗ 403 | ✓ 200 | `OWN` | ✓ 200, own rows only (1 row, against 6 for ADMIN in the same pass) | ✗ 403 |
| SALES_POC | ✗ 403 | ✓ 200 | ✓ `+45d`, term `NET_45` | ✓ 200 → term `CUSTOM` | ✓ 200 | `BOOK` | ✓ 200, own book only (1 row where ADMIN saw 24), locked chip `salesPocUserId:eq:<me>` | ✓ 200 |
| CUSTOMER_SUCCESS_POC | ✓ 200 | ✗ 403 | n/a | ✗ 403 | ✓ 200 | `ALL` | ✓ 200, unrestricted | ✓ 200 |
| COLLECTION_POC | ✗ 403 | ✗ 403 | n/a | ✗ 403 | ✓ 200 | `ALL` | ✓ 200, unrestricted | ✓ 200 |
| *(role with `EXPORT_DATA` only)* | — | — | — | — | **✗ 403** | — | **✗ 403** | **✗ 403** |

- Payment terms follow `CUSTOMER_MANAGE`; invoice creation and due-date override follow
  `INVOICE_MANAGE` (impl doc §1 answer 2).
- Ageing and the overdue filter follow `INVOICE_VIEW`.
- Export needs `EXPORT_DATA` **and** `INVOICE_VIEW` — withholding either gives 403.
- The exported header is
  `Invoice #,Customer,Date,Due date,Total,Paid,Balance,Status,Overdue[,Sales POC]` for every role
  that may export; `Sales POC` is present only when the caller may see POC identity.
- Ageing buckets are `Not yet due / 1–30 days / 31–60 days / 61–90 days / Over 90 days` for every
  role. For SALES_POC (`BOOK`) and CUSTOMER (`OWN`) the buckets sum to exactly the open balance of
  the invoices that role can list (1185.00 over 12 invoices in both cases).

---

## 3. Feature C — documents, by role × parent record

`U` upload, `L` list, `C` count, `D` download, `E` edit (PATCH), `X` delete.
Edit/delete were run against the role's **own** upload where it had one, otherwise against an
admin-uploaded document on the same record.

### 3a. On `custA` (CUSTOMER) — a record all seven roles can reach

| Role | U | L | C | D | E | X |
|---|---|---|---|---|---|---|
| ADMIN | ✓ 201 `INTERNAL` | ✓ 200 (3) | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 204 |
| CASHIER | ✓ 201 `INTERNAL` | ✓ 200 (3) | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 204 |
| VIEWER | ✗ 403 | ✓ 200 (2) | ✓ 200 | ✓ 200 | ✗ 403 | ✗ 403 |
| CUSTOMER | ✓ 201 forced `SHARED` | ✓ 200 (2) — `SHARED` only | ✓ 200 (2) | `SHARED` ✓ 200 / `INTERNAL` ✗ 404 | ✗ 403 (even on its own) | ✗ 403 (even on its own) |
| SALES_POC | ✗ 403 (no `CUSTOMER_MANAGE`) | ✓ 200 (3) | ✓ 200 | ✓ 200 | ✗ 403 | ✗ 403 |
| CUSTOMER_SUCCESS_POC | ✓ 201 `INTERNAL` | ✓ 200 (4) | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 204 |
| COLLECTION_POC | ✗ 403 (no `CUSTOMER_MANAGE`) | ✓ 200 (3) | ✓ 200 | ✓ 200 | ✗ 403 | ✗ 403 |

### 3b. On `invA` (INVOICE)

| Role | U | L | C | D | E | X |
|---|---|---|---|---|---|---|
| ADMIN | ✓ 201 | ✓ 200 (3) | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 204 |
| CASHIER | ✓ 201 | ✓ 200 (3) | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 204 |
| VIEWER | ✗ 403 | ✓ 200 (2) | ✓ 200 | ✓ 200 | ✗ 403 | ✗ 403 |
| CUSTOMER | ✓ 201 forced `SHARED` | ✓ 200 (2) — `SHARED` only | ✓ 200 (2) | `SHARED` ✓ 200 / `INTERNAL` ✗ 404 | ✗ 403 | ✗ 403 |
| SALES_POC | ✓ 201 `INTERNAL` | ✓ 200 (4) | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 204 |
| CUSTOMER_SUCCESS_POC | ✗ 403 (no `INVOICE_MANAGE`) | ✓ 200 (3) | ✓ 200 | ✓ 200 | ✗ 403 | ✗ 403 |
| COLLECTION_POC | ✗ 403 (no `INVOICE_MANAGE`) | ✓ 200 (3) | ✓ 200 | ✓ 200 | ✗ 403 | ✗ 403 |

### 3c. On `payA` (PAYMENT)

| Role | U | L | C | D | E | X |
|---|---|---|---|---|---|---|
| ADMIN | ✓ 201 | ✓ 200 (2) | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 204 |
| CASHIER | ✓ 201 | ✓ 200 (2) | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 204 |
| VIEWER | ✗ 403 | ✓ 200 (1) | ✓ 200 | ✓ 200 | ✗ 403 | ✗ 403 |
| CUSTOMER | ✓ 201 forced `SHARED` | ✓ 200 (1) — `SHARED` only | ✓ 200 (1) | ✗ 404 on `INTERNAL` | ✗ 403 | ✗ 403 |
| SALES_POC | **✗ 404** | **✗ 404** | **✗ 404** | **✗ 404** | ✗ 403 | ✗ 404 |
| CUSTOMER_SUCCESS_POC | ✗ 403 (no `PAYMENT_MANAGE`) | ✓ 200 (2) | ✓ 200 | ✓ 200 | ✗ 403 | ✗ 403 |
| COLLECTION_POC | ✓ 201 `INTERNAL` | ✓ 200 (3) | ✓ 200 | ✓ 200 | ✓ 200 | ✓ 204 |

SALES_POC's **payments book is empty** (`ScopeResolver.build` → `nothing()` for a caller who is not
COLLECTION-assignable and has no `SCOPE_OVERRIDE`), so every payment-document call answers 404 —
exactly as `GET /api/payments/{id}` itself does.

### 3d. `canEdit` / `canDelete` flags in the `DocumentDto` (AC-C22)

For every role above, the flags on the listed rows matched what the API then allowed — including the
uploader case: after a role lost `INVOICE_MANAGE`, `canDelete` stayed `true` on the row it had
uploaded itself and `false` on all the others, and DELETE then answered 204 and 403 to match.

---

## 4. Scope — foreign records (`custB` / `invB` / `payB`, no seat, no ownership)

| Role | Record itself | Document list | Document download | Document upload |
|---|---|---|---|---|
| CUSTOMER (self-service) | 403 | 403 | 403 | 403 |
| SALES_POC | 404 | 404 | 404 | 404 |
| CUSTOMER_SUCCESS_POC | **200** | **200** | **200** | 201 on CUSTOMER, 403 on INVOICE/PAYMENT |
| COLLECTION_POC | **200** | **200** | **200** | 201 on PAYMENT, 403 on CUSTOMER/INVOICE |

The document answer is identical to the record's own answer in every case (AC-C11). The last two
rows are **not** a document defect: `CUSTOMER_SUCCESS_POC` and `COLLECTION_POC` are seeded with
`SCOPE_OVERRIDE` (`DataSeeder.java:138`, `DataSeeder.java:153`), which by design lifts the book
restriction — the same behaviour they had before this feature. They are recorded here because the
implementation doc's matrix (line 266) says all three POC roles operate "inside their POC book",
which is true only of `SALES_POC`.

---

## 5. Divergences from the implementation doc's stated matrix (§4.5, lines 261-267)

| Doc line | Doc says | Observed |
|---|---|---|
| 263 `ADMIN ✓ ✓` | view + manage | ✓ matches |
| 264 `CASHIER ✓ ✓` | view + manage, "keeps every existing capability" | ✓ matches — CASHIER's live privilege set is a strict superset of its pre-feature set |
| 265 `VIEWER ✓ —` | view only | ✓ matches |
| 266 `SALES / CUSTOMER_SUCCESS / COLLECTION POC ✓ ✓ — inside their POC book` | manage on documents, book-limited | ✗ **two divergences.** (a) "Manage ✓" holds only for the record kinds the role can manage: SALES_POC manages invoice documents only (customer 403, payment 404); CUSTOMER_SUCCESS_POC customer documents only (invoice 403, payment 403); COLLECTION_POC payment documents only (customer 403, invoice 403). (b) "inside their POC book" is true only of SALES_POC — CUSTOMER_SUCCESS_POC and COLLECTION_POC hold `SCOPE_OVERRIDE` and read/manage documents on records they hold no seat on. |
| 267 `CUSTOMER ✓ ✓ … PATCH and DELETE are 403` | own records, `SHARED` only, uploads forced `SHARED`, PATCH/DELETE 403 | ✓ matches exactly |

The doc also carries **no** role × capability matrix for Feature A / Feature B, although PRD §8
requires one "for both features". The per-capability answers are scattered through §1 (answer 2),
§2.3 and §3 instead.

---

## 6. Conjunction checks (AC-C10) — purpose-built roles

| Role composition | list / count | download | upload | PATCH | DELETE |
|---|---|---|---|---|---|
| `DOCUMENT_VIEW` + `DOCUMENT_MANAGE`, **no** record privilege | 403 | 403 | 403 | 403 | 403 |
| `INVOICE_VIEW`, **no** `DOCUMENT_VIEW` | 403 | 403 | 403 | 403 | 403 |
| `DOCUMENT_VIEW` + `DOCUMENT_MANAGE` + `INVOICE_VIEW`, **no** `INVOICE_MANAGE` | 200 | 200 | 403 | 403 | 403 (someone else's) / 204 (its own upload) |
| `EXPORT_DATA` only | — | — | — | — | export 403, ageing 403, overdue filter 403 |

Both halves of every conjunction are genuinely required.
