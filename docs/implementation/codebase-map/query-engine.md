# Engineering map: the list, filter and table query engine, for PRD A2 (conditions), B1 (regions) and B3 (as-of)

Backend paths below are relative to `backend/src/main/java/com/geneinvoice/` and frontend paths to `frontend/lib/`. Nothing was edited.

---

## 0. Summary of the key findings

- **The filter grammar is a flat list of `field:operator:values` strings.** Every string is ANDed. There is no OR and no grouping anywhere, on either the wire or the server (`common/query/TableQueryExecutor.java:130-132`). The only OR that exists is inside a single column, via the `in` / `notIn` operators.
- **Every list, count, id lookup and aggregate goes through one choke point:** `TableQueryExecutor.predicates(...)` (`TableQueryExecutor.java:107-120`). It ANDs two things:
  - a `List<PredicateFactory> scope` (visibility), and
  - the validated `FilterSpec`s.
  
  `DashboardService` is the exception. It builds its own criteria, but applies the same `ScopeResolver` scopes through a copy of that loop (`dashboard/DashboardService.java:301-317`).
- **Visibility has one source: `poc/ScopeResolver.java`.** It returns `Scope(predicates, lockedFilters)`. `lockedFilters` doubles as the "book mode" signal read by the dashboard and the frontend, so a region scope must not be pushed into it (see §9).
- **There is no in-memory filter evaluator.** Two ways to evaluate a condition exist or can be built:
  - Evaluate against the database by id, reusing the `inScope` pattern (`TableQueryExecutor.java:71-75`).
  - Build an in-memory twin. `email/EmailViews.java:51-65` is a precedent: a `PredicateFactory` paired with an in-memory boolean for the same rule.
- **`TableQueryExecutor` is auth-free.** Everything around it is not: `ScopeResolver`, `TableSchema.visibleTo(currentUser.isCustomer())`, and every service's `page`/`get`/`tiles` call `CurrentUser.require()`. That throws `BadCredentialsException` when there is no `SecurityContext`, which is the situation inside a queue consumer or a scheduler.
- **There are no tests for this engine** (`backend/src/test` has none that touch `common/query`).

---

## 1. File inventory

### Backend: `common/query`

| File | Lines | Role |
|---|---|---|
| `ColumnType.java` | 31 | Column types and the operators each one allows |
| `ColumnDef.java` | 80 | Column record, path helpers, builder |
| `TableSchema.java` | 55 | Per-entity column index and validation |
| `TableSchemas.java` | 355 | The static schemas for every list, plus derived and subquery columns |
| `FilterOperator.java` | 51 | Operator enum (wire name, arity) |
| `FilterSpec.java` | 60 | One parsed `field:op:values` filter |
| `FilterParams.java` | 19 | Reads repeated `filter` query params |
| `FilterPredicates.java` | 111 | **Package-private.** `FilterSpec` → JPA `Predicate` |
| `ValueCoercion.java` | 67 | **Package-private.** Raw string → typed `Comparable` |
| `DateRange.java` | 32 | Presets for the `relative` operator |
| `PredicateFactory.java`, `PredicateResolver.java`, `PathResolver.java` | 11 each | Functional interfaces |
| `TableQuery.java` | 64 | Parsed page, size, sort and filters |
| `TableQueryExecutor.java` | 145 | `run`, `count`, `inScope`, `ids`, `aggregate` |
| `Aggregates.java` | 32 | `countWhen`, `sumWhen`, `asLong`, `asMoney` |
| `PageResponse.java` | 29 | List response envelope |
| `TableSchemaController.java` | 83 | `GET /api/table-schemas...` |

### Backend: `common/bulk`

| File | Lines | Role |
|---|---|---|
| `BulkDtos.java` | 56 | Request and result records |
| `BulkExecutor.java` | 92 | One transaction per record |
| `Csv.java` | 38 | CSV output with formula-injection escaping |

### Frontend: `core/table`

| File | Lines | Role |
|---|---|---|
| `table_models.dart` | 296 | `TableFilter`, `TableQuery`, `PagedResult`, `ColumnDef`, `TableSchema`, labels |
| `table_providers.dart` | 119 | `TableRequest`; schema, page and summary providers; page-size store |
| `route_query.dart` | 64 | URL ↔ query |
| `filter_editor.dart` | 339 | `showFilterEditor`, `describeFilter` |
| `reference_picker.dart` | 166 | Search pickers for reference columns |
| `data_table_scaffold.dart` | 1081 | The list page widget: filter bar, locked chips, bulk, export, tiles |

---

## 2. Request flow (the convention every list copies)

### Controller shape

`invoice/InvoiceController.java:42-56`:

```java
private TableSchema schema() {
    return TableSchemas.INVOICES.visibleTo(currentUser.isCustomer());
}

@GetMapping
@PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
public PageResponse<InvoiceDtos.InvoiceSummary> list(
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size,
        @RequestParam(required = false) String sort,
        HttpServletRequest request,
        @RequestParam(required = false) Long customerId) {
    return service.page(TableQuery.parse(schema(), page, size, sort,
            withCustomer(FilterParams.from(request), customerId)));
}
```

- The summary endpoint uses `TableQuery.parseUnpaged(schema(), null, filters)` (`InvoiceController.java:58-65`).
- Context params are turned into ordinary filters, so they appear in `appliedFilters`:
  - `withCustomer` adds `customerId:eq:<id>` (`InvoiceController.java:169-174`, `payment/PaymentController.java:167-172`).
  - `withContext` handles promises and disputes (`promise/PaymentPromiseController.java:182-189`, `dispute/DisputeController.java:104-112`).

### Service shape

`invoice/InvoiceService.java:295-316`:

```java
@Transactional(readOnly = true)
public PageResponse<InvoiceDtos.InvoiceSummary> page(TableQuery query) {
    ScopeResolver.Scope scope = scopeResolver.forInvoices();
    boolean poc = scopeResolver.canSeePoc();
    var page = queryExecutor.run(Invoice.class, TableSchemas.INVOICES, query,
            scope.predicates(), List.of("customer", "salesPoc"));
    return PageResponse.of(
            page.content().stream().map(i -> InvoiceDtos.InvoiceSummary.from(i, poc)).toList(),
            query, page.total(), scope.lockedFilters());
}
public List<Long> idsMatching(TableQuery query, int limit) { return queryExecutor.ids(Invoice.class, TableSchemas.INVOICES, query, scopeResolver.forInvoices().predicates(), limit); }
public List<Invoice> allMatching(TableQuery query) { ...run(..., List.of("customer","salesPoc")).content(); }
```

Every list service has the same four-method set:

| Service | Methods and lines |
|---|---|
| `customer/CustomerService.java` | `page` 66-72, `idsMatching` 74-78, `allMatching` 80-84, `tiles` 86-101 |
| `payment/PaymentService.java` | `page` 300-309, `idsMatching` 311-315, `allMatching` 317-321, `tiles` 323-348 |
| `promise/PaymentPromiseService.java` | `page` 537-544, `idsMatching` 546-550, `allMatching` 552-556, `tiles` 558-586 |
| Disputes | No service method. The controller calls the executor directly (`DisputeController.java:35-52`) and uses `TableSchemas.DISPUTES` without `visibleTo`, because that schema has no POC-restricted columns. |

### Per-record visibility check ("in book")

Used for single-record get, update and delete. From `InvoiceService.java:281-286`:

```java
private void requireInBook(Long id) {
    if (!queryExecutor.inScope(Invoice.class, TableSchemas.INVOICES, id,
            scopeResolver.forInvoices().predicates())) {
        throw new NotFoundException("Invoice not found");
    }
}
```

The same pattern is at:
- `CustomerService.java:140-145`
- `PaymentService.java:286-291`
- `PaymentPromiseService.java:515-518`
- `PaymentController.credit` (`PaymentController.java:103-106`)

Out of scope answers **404, not 403** (AUTH-02 / AUTH-08 comments).

### Exceptions

`common/GlobalExceptionHandler.java`:

| Exception | Response |
|---|---|
| `BadRequestException(String)` (`common/BadRequestException.java`) | 400 (lines 40-43) |
| `NotFoundException` | 404 |
| `AccessDeniedException` | 403, generic message |
| `InvalidFieldsException(Map)` | 400 with field errors (lines 73-79) |
| `ConcurrencyFailureException` | 409 |

Every parse and validation error in the engine is a `BadRequestException`.

---

## 3. Wire formats

### 3.1 The filter string (backend `FilterSpec.parse`, `FilterSpec.java:10-44`)

```
<field>:<operator>:<values>
```

- `field` is the text before the first `:`, trimmed. It must be non-empty.
- `operator` is the text between the first and second `:`. It is matched case-insensitively against the wire name or the enum name (`FilterOperator.parse`, `FilterOperator.java:43-50`).
- `values` is everything after the second `:`, so it can contain further colons.
  - Arity 0 (`isEmpty`, `isNotEmpty`): values are ignored and become `[]`.
  - Multi-valued operators (arity ≠ 1: `in`, `notIn`, `between`, and the arity-0 ones): the rest is split on `,`, each part is trimmed and empty parts are dropped.
  - Single-valued operators: `List.of(rest)`, **not trimmed**, so `name:contains: x` searches for `" x"`.
- The value count is checked (lines 35-41): `between` needs exactly 2, `in`/`notIn` at least 1, single-valued ops exactly 1.
- A value containing `\0` is rejected (lines 46-51).
- `wire()` gives back `field:op:v1,v2` (line 57-59).
- **Values cannot contain commas** for multi-valued operators. There is no escaping.

Transport:
- **GET lists and summaries:** repeated `filter` query params, read by `FilterParams.from(request)` (`FilterParams.java:14-18`), which drops blank entries.
- **Bulk and export POST bodies:** `BulkDtos.BulkRequest.filters: List<String>` (`common/bulk/BulkDtos.java:13-23`).
- **Frontend routes:** repeated `f=` (`table_models.dart:86-91`). The API side uses `filter` (`table_models.dart:79-84`).

### 3.2 Operators (`FilterOperator.java:9-21`) and which types allow them (`ColumnType.java:6-16`)

| wire | arity | TEXT | ENUM | BOOLEAN | NUMBER | MONEY | DATE | REFERENCE |
|---|---|---|---|---|---|---|---|---|
| `eq` | 1 | ✓ | ✓ | ✓ | ✓ | ✓ | **✗** | ✓ |
| `neq` | 1 | ✓ | ✓ | | ✓ | ✓ | | ✓ |
| `contains` | 1 | ✓ | | | | | | |
| `in` | -1 | | ✓ | | | | | ✓ |
| `notIn` | -1 | | ✓ | | | | | |
| `gt`, `lt` | 1 | | | | ✓ | ✓ | | |
| `gte`, `lte` | 1 | | | | ✓ | ✓ | ✓ | |
| `between` | 2 | | | | ✓ | ✓ | ✓ | |
| `isEmpty`, `isNotEmpty` | 0 | ✓ | | | | | | ✓ |
| `relative` | 1 | | | | | | ✓ | |

`TableSchema.requireFilterable` enforces this table (`TableSchema.java:34-44`). It runs at parse time (`TableQuery.java:48-49`) **and again** at execution (`TableQueryExecutor.java:116`).

### 3.3 How each operator becomes SQL (`FilterPredicates.build`, `FilterPredicates.java:21-61`)

If the column has a `customFilter`, that is used and nothing below applies (line 24-26). Otherwise the path is `def.path().resolve(root,q,cb)` and its Java type drives value coercion.

| operator | SQL semantics |
|---|---|
| `isEmpty` | For strings: `x IS NULL OR trim(x) = ''`. For other types: `IS NULL` (32-34) |
| `isNotEmpty` | For strings: `x IS NOT NULL AND trim(x) <> ''` (35-37) |
| `contains` | `lower(x) LIKE '%' + escapeLike(value.toLowerCase(Locale.ROOT)) + '%' ESCAPE '\'` (38-41). Helpers are `Strings.escapeLike` and `LIKE_ESCAPE` (`common/Strings.java:17-21`) |
| `eq` | `x = coerce(v)` |
| `neq` | **`x IS NULL OR x <> v`**, so nulls match (43-44) |
| `in` | `x IN (...)` |
| `notIn` | **`x IS NULL OR NOT x IN (...)`** (46-47) |
| `gt`, `gte`, `lt` | Plain comparison |
| `lte` | `atMost` (86-94): if the column is an `Instant` and the value is date-only (`yyyy-MM-dd`), this becomes `< start of next day UTC`, i.e. the whole day is included |
| `between` | `>= lo AND atMost(hi)` (55-58) |
| `relative` | `DateRange.preset(name, LocalDate.now(ZoneOffset.UTC))` (**reads the clock at line 68**), then `>= from` and `<= to` for `LocalDate`, or `< to+1 day` for `Instant` (63-84) |

Value coercion (`ValueCoercion.coerce`, `ValueCoercion.java:17-44`), by target type:

| Target type | Rule |
|---|---|
| `String` | Trimmed |
| `BigDecimal` | At most 18 integer digits and at most 6 decimals (46-55) |
| `Long`, `Integer`, `Double` | Parsed |
| `Boolean` | Only `true` or `false` |
| `Instant` | A date-only value means start of that day in UTC; otherwise ISO-8601 (57-62) |
| `LocalDate` | `LocalDate.parse` |
| enum | `Enum.valueOf(v.toUpperCase())` |

**Coercion only runs when the predicate is built, i.e. at execution, never at parse.** A filter such as `status:eq:BOGUS` or `total:gt:abc` passes `TableQuery.parse` and only fails when it is executed.

`DateRange.PRESETS` (`DateRange.java:10-30`): `today`, `yesterday`, `last7Days`, `last30Days`, `thisMonth`, `lastMonth`, `thisYear`, `past` (up to yesterday), `future` (from tomorrow). Today is in neither `past` nor `future`.

### 3.4 Sort, page and size (`TableQuery.parse`, `TableQuery.java:15-54`)

- `sort=field[,asc|desc]`. The direction defaults to `asc`. Any other direction is a 400. The field must be sortable. If sort is blank, the schema's `defaultSort` is used.
- The executor always adds `id desc` as a tiebreaker (`TableQueryExecutor.java:134-144`).
- `size` must be one of `[10, 20, 50]` (lines 11-13 and 20-23). `page >= 0`.
- `parseUnpaged` sets `size = Integer.MAX_VALUE` (56-59). The executor then **silently caps rows at `BULK_ID_LIMIT = 5000`** (`TableQueryExecutor.java:23, 53-55`), while `total` stays uncapped.
- The record constructor `new TableQuery(page, size, sortField, asc, filters)` is public and can take any size. Every current construction is inside the package (`TableQuery.java:53, 58`).

### 3.5 Response envelope (`PageResponse.java:6-23`)

```json
{"content":[...],"page":0,"size":20,"totalElements":123,"totalPages":7,
 "sort":"invoiceDate,desc","appliedFilters":["status:in:UNPAID"],"lockedFilters":["salesPocUserId:eq:5"]}
```

`totalPages` is at least 1.

### 3.6 Schema endpoint (`TableSchemaController.java`)

| Endpoint | Returns |
|---|---|
| `GET /api/table-schemas` | Entity names the caller may read |
| `GET /api/table-schemas/{entity}` | `SchemaDto` |
| `GET /api/table-schemas/all` | All readable `SchemaDto`s |

```java
public record ColumnDto(String name, String label, String type, boolean sortable,
                        boolean filterable, List<String> operators, List<String> enumValues,
                        String referenceKind) {}
public record SchemaDto(String entity, String defaultSort, List<Integer> pageSizes,
                        int defaultPageSize, List<String> datePresets, List<ColumnDto> columns) {}
```

- Readability is decided by `VIEW_PRIVILEGE` (lines 21-31). **An entity missing from that map is 403 even if it is registered in `TableSchemas`.**
- The schema is `visibleTo(currentUser.isCustomer())`, so customer logins do not see POC-restricted columns (lines 49, 61-62).

---

## 4. The schemas that A2 conditions reuse (`TableSchemas.java`)

Column declaration convention (`ColumnDef.java:22-36, 48-79`):
- The default path is `attr(name)`. `sortable` and `filterable` default to true.
- `.path(PathResolver)` sets a computed or joined path.
- `.filter(PredicateResolver)` sets a fully custom predicate.
- `.reference(kind)` sets the frontend picker kind.
- `.pocRestricted()` hides the column from customer logins.

`nested(assoc, attr)` reuses an existing LEFT join on the same `From` (`ColumnDef.java:38-46`). `referenceId(assoc)` is `root.get(assoc).get("id")`.

Example of a derived column with a custom filter (`TableSchemas.java:60-63`):

```java
ColumnDef.of("overdue", "Overdue", ColumnType.BOOLEAN).notSortable()
        .path((root, q, cb) -> root.get("id"))
        .filter((spec, root, q, cb) -> overduePredicate(spec, root, cb))
        .build(),
```

### INVOICES (`TableSchemas.java:40-70`, default sort `invoiceDate,desc`)

| column | type | DB path | in-memory equivalent |
|---|---|---|---|
| `id` | NUMBER | `id` | `getId()` |
| `invoiceNumber` | TEXT | attr | `getInvoiceNumber()` |
| `customerId` | REFERENCE(customer), not sortable | `customer.id` | `getCustomer().getId()` (lazy) |
| `customerName` | TEXT | LEFT JOIN `customer.name` | `getCustomer().getName()` |
| `invoiceDate` | DATE (`Instant`) | attr | `getInvoiceDate()` |
| `dueDate` | DATE (`LocalDate`) | attr | `getDueDate()` |
| `total`, `paidAmount` | MONEY | attr | getters |
| `balance` | MONEY | `total - paidAmount` (52-55), no cancelled special case | `getBalance()` (`invoice/Invoice.java:90-92`), same formula |
| `status` | ENUM `InvoiceStatus` | attr | `getStatus()` |
| `overdue` | BOOLEAN, custom filter | `invoiceOverdue(root,cb,InvoiceDates.today())` (72-85). `eq:false` means `dueDate IS NULL OR NOT overdue` | `isOverdue(today)` (`Invoice.java:94-97`) |
| `notes` | TEXT, not sortable | attr | `getNotes()` |
| `salesPocUserId` | REFERENCE(pocUser), POC-restricted | `salesPoc.id` | `getSalesPoc()?.getId()` |
| `salesPocName` | TEXT, POC-restricted | LEFT JOIN `salesPoc.fullName` | `getSalesPoc()?.getFullName()` |
| `createdAt` | DATE (`Instant`) | attr | `getCreatedAt()` |

There is no `paymentTerm` column and no `updatedAt`. `Invoice` has no `updatedAt` field.

### PAYMENTS (`TableSchemas.java:94-112`, default sort `paidAt,desc`)

Columns: `id`, `customerId` (REFERENCE customer), `customerName` (nested), `amount` (MONEY), `creditApplied` (MONEY), `method` (TEXT), `notes`, `paidAt` (DATE `Instant`), `status` (ENUM `PaymentStatus`), `collectionPocUserId` (REFERENCE pocUser, POC-restricted, `collectionPoc.id`), `collectionPocName` (POC-restricted).

`Payment` has **no `createdAt` or `updatedAt`**. `paidAt` is always set to "now" on persist (`payment/Payment.java:59-64`), and `CreatePaymentRequest` has no `paidAt` (`payment/PaymentDtos.java:19-28`).

### CUSTOMERS (`TableSchemas.java:114-133`, default sort `name,asc`)

| column | type | notes |
|---|---|---|
| `id`, `name`, `phone`, `email` | NUMBER / TEXT | attr |
| `address` | TEXT, not sortable | attr |
| `creditBalance` | MONEY | attr |
| `outstanding` | MONEY | Correlated subquery: sum of `total - paidAmount` over non-cancelled invoices of this customer (`customerOutstanding`, 135-144). **Needs the database; there is no entity getter.** |
| `successPocUserId`, `collectionPocUserId` | REFERENCE(pocUser), POC-restricted, custom filter | `EXISTS`/`NOT EXISTS` over `CustomerPoc` seats (`pocSeatPredicate`, 146-180). Only `isEmpty`, `isNotEmpty`, `eq`, `neq`, `in` are supported; anything else is a 400 (177-178). The dummy path is `root.get("id")`. |
| `createdAt` | DATE | attr |

There is no `overdue`, `paymentTerm` or `updatedAt` column, although `CustomerDto` carries `overdueAmount` and `paymentTerm` (`customer/CustomerService.java:191-200`).

### PROMISES (`TableSchemas.java:190-221`)

Columns: `id`, `customerId`, `customerName`, `amount`, `fulfilledAmount`, `remainingAmount` (a CASE expression, 223-231; it mirrors `PaymentPromise.getRemainingAmount()` at `promise/PaymentPromise.java:110-114`), `promisedDate` (`LocalDate`), `status`, `statusOverridden` (BOOLEAN), `collectionPocUserId`, `collectionPocName`, `notes`.

`invoiceId` and `paymentId` are REFERENCE columns with custom filters that `EXISTS` over the many-to-many (`promiseLinkPredicate`, 233-266). That is the **pattern for to-many conditions**. `createdAt` is also a column.

### DISPUTES (`TableSchemas.java:292-302`)

Columns: `id`, `customerId` (REFERENCE, plain `Long` column; `dispute/Dispute.java:25-26`), `targetType`, `targetId`, `status`, `reason`, `createdAt`, `resolvedAt`.

### Other schemas

- `PRODUCTS`, `USERS`, `ROLES`, `NOTIFICATIONS`, `INBOX` (268-331).
- Private schemas: `RECORD_EMAILS` (`email/EmailService.java:63-65`) and `RECORD_DOCUMENTS` (`document/DocumentService.java:43-45`). These have no filterable columns and are used only for paging.

### Registry

`BY_ENTITY` (`TableSchemas.java:333-342`) is built from the explicit list at lines 337-338. `byEntity` lowercases its input and returns 400 for an unknown entity (344-350). **A new schema, such as `tasks`, `rules` or `regions`, must be added both here and to `TableSchemaController.VIEW_PRIVILEGE`.**

---

## 5. The executor (`TableQueryExecutor.java`)

```java
@Transactional(readOnly = true)
public <T> Page<T> run(Class<T> type, TableSchema schema, TableQuery query,
                       List<PredicateFactory> scope, List<String> fetchAssociations)   // 30-59
public <T> long count(Class<T> type, TableSchema schema, TableQuery query, List<PredicateFactory> scope) // 61-69
public <T> boolean inScope(Class<T> type, TableSchema schema, Long id, List<PredicateFactory> scope)    // 71-75
public <T> List<Long> ids(Class<T> type, TableSchema schema, TableQuery query,
                          List<PredicateFactory> scope, int limit)                                     // 77-87
public <T> Object[] aggregate(Class<T> type, TableSchema schema, TableQuery query,
                              List<PredicateFactory> scope, AggregateSelections selections)            // 89-100
@FunctionalInterface public interface AggregateSelections {
    List<Expression<?>> build(Root<?> root, CriteriaQuery<?> query, CriteriaBuilder cb); }             // 102-105
```

The choke point (107-120):

```java
private <T> List<Predicate> predicates(Root<T> root, CriteriaQuery<?> cq, CriteriaBuilder cb,
                                       TableSchema schema, TableQuery query,
                                       List<PredicateFactory> scope) {
    List<Predicate> all = new ArrayList<>();
    for (PredicateFactory f : scope) { Predicate p = f.build(root, cq, cb); if (p != null) all.add(p); }
    for (FilterSpec spec : query.filters()) {
        ColumnDef def = schema.requireFilterable(spec.field(), spec.operator());
        all.add(FilterPredicates.build(spec, def, root, cq, cb));
    }
    return all;
}
private Predicate all(CriteriaBuilder cb, List<Predicate> parts) {
    return parts.isEmpty() ? cb.conjunction() : cb.and(parts.toArray(new Predicate[0]));   // AND only
}
```

Behaviour to know:
- `run` always issues a second query for the count (line 58).
- A page far past the end returns an empty list instead of overflowing (44-50).
- `run` left-join-fetches the named associations (123-128).
- `inScope` returns **true immediately when `scope` is empty** (line 73). It does not check that the row exists; callers call `findById` first.
- `inScope` builds a query with `TableQuery.parseUnpaged(schema, null, List.of("id:eq:" + id))`. That validates the schema's default sort, which is harmless.
- `aggregate` returns `new Object[n]` of nulls when there are no rows (line 99). Normalise with `Aggregates.asLong`/`asMoney`.
- Methods are `readOnly` and use `REQUIRED` propagation, so when called inside a caller's read-write transaction they join it. Hibernate's AUTO flush flushes pending changes that touch the queried tables before the query runs.
- The class has no dependency on `CurrentUser`.

`PredicateFactory` (`PredicateFactory.java:9-11`):

```java
@FunctionalInterface public interface PredicateFactory {
    Predicate build(Root<?> root, CriteriaQuery<?> query, CriteriaBuilder cb); }
```

`PredicateResolver` (the custom column filter) is `(FilterSpec spec, Root<?> root, CriteriaQuery<?> query, CriteriaBuilder cb)`. **It receives no context object** such as an as-of date or regions.

---

## 6. AND/OR today

- **Across filters:** AND only (`TableQueryExecutor.java:130-132`). Scope predicates are ANDed with the filters.
- **Within one column:** `in`/`notIn` give OR, but only on ENUM, plus `in` on REFERENCE. TEXT, NUMBER, MONEY and DATE have no set operators.
- **No grouping** exists in `FilterSpec`, `TableQuery`, the URL format, `BulkRequest.filters`, or the frontend models.
- **Frontend dedupe:** `TableQuery.addFilter` replaces any existing filter with the same field **and** operator (`table_models.dart:68-72`). So `status:eq:A` plus `status:eq:B` cannot coexist on a list page, and group children must not be built through `addFilter`.
- The only existing OR composition in criteria code is hand-written: `ScopeResolver.forCustomers` (`poc/ScopeResolver.java:83-84`) and `EmailViews.customerTookPart` (`email/EmailViews.java:51-60`).

---

## 7. How visibility is injected (`poc/ScopeResolver.java`)

```java
public record Scope(List<PredicateFactory> predicates, List<String> lockedFilters) {
    static Scope empty() { return new Scope(List.of(), List.of()); } }            // 27-31
```

### Per-entity scopes

| method | Customer login | SCOPE_OVERRIDE | POC-assignable staff | Other staff |
|---|---|---|---|---|
| `forInvoices` (51-54) | `customer.id = own` | `empty()` | `salesPoc.id = me`, locked `salesPocUserId:eq:me` | `disjunction()` (sees nothing), locked filter shown |
| `forPayments` (56-59) | `customer.id = own` | `empty()` | `collectionPoc.id = me` | nothing |
| `forPromises` (61-64) | same | `empty()` | `collectionPoc.id = me` | nothing |
| `forCustomers` (66-87) | `id = own`, no lock | `empty()` | `EXISTS seat(me) OR EXISTS invoice(salesPoc=me)`, locked **`myBook:eq:me`** | nothing |
| `forDisputes` (89-97) | `customerId = own` | — | — | **all staff see everything** |

- The shared builder is `build(...)` (111-129). `nothing(...)` adds `cb.disjunction()` (131-133).
- `canSeeEverything()` (39-43) returns `Scope.empty()` **before** any other predicate, so it bypasses everything.
- `myBook` is **not a column** in `CUSTOMERS`. It is display-only. Sending it back as a filter is a 400 "Unknown column: myBook". The frontend special-cases it (`data_table_scaffold.dart:806-809`).
- `canSeePoc()`, `canSeeEverything()`, `isAssignableAs()` and every `forX()` call `currentUser.require()` (`auth/CurrentUser.java:12-18`).

### Call sites that apply a scope

These are the places where adding a predicate inside `ScopeResolver` would propagate automatically:

- **List, ids, allMatching and tiles:** the four list services (§2) and `DisputeController.list`/`export` (47-49, 83-85).
- **`requireInBook` checks:** `CustomerService.java:140-145`, `InvoiceService.java:281-293`, `PaymentService.java:286-291`, `PaymentPromiseService.java:515-518`, `PaymentController.java:103-106`.
- **Email bulk targeting:** `email/EmailTargets.java:342-364`. Its `permittedIds` (366-371) validates with the `visibleTo` schema but executes with the full schema.
- **Dashboard:** `DashboardService.java:68, 85, 115, 189-197` via `scoped()` (301-309) and `idsInScope()` (311-317).

### Paths that are NOT scoped (they must be handled separately for B1)

- `PaymentService.list(customerId)` (`PaymentService.java:293-298`) uses `findAll`.
- `DisputeService.list()` (135-141) and `DisputeService.get()` (143-152) only check the customer.
- `PaymentPromiseService.listForCustomer` and `listForInvoice` (527-535).
- `InvoiceService.getInternal` (275-279).
- `ProductController`, `UserController`, `RoleController`: `List.of()` scope.
- `NotificationController` and `InboxController`: scoped by owner only (`InboxController.java:103-108`).
- Record-scoped document and email lists (`DocumentService.java:205-215`, `EmailService.java:323-334`): scoped by record plus `targets.requireVisible`.
- Cross-entity figures computed outside the executor:
  - `CustomerService.toDtos` via `invoiceRepository.sumOutstandingByCustomer` and `sumOverdueByCustomer` (`CustomerService.java:158-166`).
  - `CustomerService.outstandingSubquery` (103-115).
  - `TableSchemas.customerOutstanding` (135-144).
  
  All of these sum **every** invoice of the customer, ignoring scope.

---

## 8. Aggregates and counts

- `Aggregates.countWhen(cb, p)` is `coalesce(sum(case when p then 1 else 0), 0)`. `sumWhen` is the same with a value, falling back to `BigDecimal.ZERO`. `asLong` and `asMoney` (scale 2, HALF_UP) normalise results across H2 and Postgres (`Aggregates.java:13-31`).
- **Summary tiles** go through `executor.aggregate(...)` with the list's filters and scope:
  - `CustomerService.tiles` (86-101). It uses subqueries for outstanding and seat-missing counts.
  - `InvoiceService.tiles` (318-350). **It reads the clock at 321** for overdue.
  - `PaymentService.tiles` (323-348).
  - `PaymentPromiseService.tiles` (558-586).
  - Tile endpoints are `GET <list>/summary` with the same `filter` params. The frontend removes `page`, `size` and `sort` (`table_providers.dart:63-72`).
- **Dashboard** (`dashboard/DashboardService.java`) runs its own criteria with scope predicates only. It does not apply list filters. `today` is passed from `DashboardController.today()` (`DashboardController.java:52-54`, which calls `InvoiceDates.today()`). Coverage is decided from `lockedFilters`:

  ```java
  private boolean paidAgainstBookOnly() { return !scopeResolver.forInvoices().lockedFilters().isEmpty(); }   // 203-205
  private DashboardDtos.Coverage coverage(ScopeResolver.Scope scope) {
      if (currentUser.isCustomer()) return DashboardDtos.Coverage.OWN;
      return scope.lockedFilters().isEmpty() ? DashboardDtos.Coverage.ALL : DashboardDtos.Coverage.BOOK; } // 213-216
  ```

---

## 9. Bulk and export (`common/bulk`)

Every list controller resolves ids the same way (e.g. `InvoiceController.java:159-167`):

```java
private List<Long> resolveIds(BulkDtos.BulkRequest req) {
    TableQuery query = TableQuery.parseUnpaged(schema(), req.sort(), req.filters());
    List<Long> permitted = service.idsMatching(query, TableQueryExecutor.BULK_ID_LIMIT);
    if (req.allMatching()) return permitted;
    if (req.ids() == null || req.ids().isEmpty()) throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
    return req.ids().stream().filter(permitted::contains).toList();
}
```

`BulkExecutor.run(req, permitted, truncated, op)` (`BulkExecutor.java:53-85`):
- Runs each record in its own `REQUIRES_NEW` transaction (22-25, 68).
- Ids outside the permitted set are **skipped** with `NOT_REACHABLE`.
- `IneligibleException` → skipped. `BulkExecutor.eligibility(op)` maps `BadRequestException` to this.
- `ConcurrencyFailureException` → skipped with `CHANGED_WHILE_RUNNING`.
- Any other `RuntimeException` → failed.

`BulkResult(action, requested, succeeded, failed, skipped, truncated, limit)` (`BulkDtos.java:47-55`) has **no "pending" outcome**, which matters for B2. The frontend reads only these fields (`data_table_scaffold.dart:552-607`).

`Csv.of` prefixes values that start with `=+-@` to block formula injection (`Csv.java:27-37`).

---

## 10. Frontend (`frontend/lib/core/table`)

### Models (`table_models.dart`)

- `TableFilter(field, operator, values, {label})`. `wire` is `field:op:v1,v2`. `parse` mirrors the backend split rules; `_multiValueOperators = {'in','notIn','between','isEmpty','isNotEmpty'}` (lines 16-33) and must stay in sync with backend arity.
- **`label` is not part of the wire format or of equality** (9-12, 35-43). A stored or restored reference filter shows the bare id.
- `TableQuery.toApiParams()` uses `filter`; `toRouteParams()` uses `f` (79-91).
- `PagedResult.fromJson` parses `lockedFilters` back into `TableFilter` (145-160).
- `ColumnDef` and `TableSchema` mirror the server DTOs (191-266). `operatorLabel` and `datePresetLabel` are at 268-296.

### Providers (`table_providers.dart`)

- `TableRequest(entity, path, query, extra)`, with `apiParams = {...query.toApiParams(), ...extra}` (11-44). `forSummary` keeps `extra`.
- `tableSchemaProvider(entity)` calls `GET /api/table-schemas/$entity` (46-54).
- `tablePageProvider` (56-61) and `tableSummaryProvider` (63-72).

### Filter editor (`filter_editor.dart`)

- **`showFilterEditor({context, schema, existing}) → Future<TableFilter?>` is public (7-16)** and can build one condition leaf directly from a server schema.
- The value editor switches on type (194-301):
  - ENUM: chips for `in`/`notIn`, dropdown otherwise.
  - BOOLEAN: Yes/No.
  - REFERENCE: `ReferencePicker`, **one id only, even for `in`**.
  - DATE: date picker or preset dropdown.
  - numeric or text fields otherwise.
- `describeFilter(f, schema)` renders a chip label (330-339).

### Reference picker (`reference_picker.dart:28-87`)

Kinds are `pocUser` (via `/api/pocs/assignable` for each PocType), `invoice`, `payment` and `customer`. **An unknown kind falls through to customer search** (75-76), and `filter_editor.dart:253` defaults `null` to `'customer'`. A new `region` kind needs its own case.

### Scaffold (`data_table_scaffold.dart`)

- The filter bar (728-810) shows locked chips with the tooltip "Your role limits this list to your own records". `_lockedLabel` is `myBook` → "My book only", anything else → "My records only".
- **Bulk and export POST bodies do not include `extraParams`** (473-480, 499-505). They send only `ids` / `selectAllMatchingFilter`, `sort` and `filters`. No screen uses `extraParams` today.

### Route query (`route_query.dart`)

`RouteQuery.read`, `push(query, {keep})` and `location(path, params)` (19-64). `keep` is unused. Only `page`, `size`, `sort` and `f` survive a push.

### Deep links that hard-code column names and wire strings

These are a contract that renaming a column breaks:
- `features/dashboard/dashboard_charts.dart:568-586`
- `features/dashboard/dashboard_screen.dart:353, 398`
- `features/dashboard/dashboard_providers.dart:211-217`. Line 232 also infers "book" from a non-empty `lockedFilters`.
- `invoices_screen.dart` quick filter `TableFilter('overdue','eq',['true'])`
- `record_payment_dialog.dart:336`, `promise_form_dialog.dart:390`, `promise_providers.dart:52`, `products_screen.dart:22`, `email_entity.dart:56`

---

## 11. A2: evaluating a condition

### 11.1 Proposed condition shape (reuses the leaf grammar unchanged)

```json
{"op":"AND","children":[
  {"filter":"status:in:UNPAID,PARTIALLY_PAID"},
  {"op":"OR","children":[{"filter":"overdue:eq:true"},{"filter":"balance:gt:1000"}]}
]}
```

- Leaves use the exact list-page wire strings, so `FilterSpec.parse` / `TableFilter.parse` / `describeFilter` / `showFilterEditor` work as they are.
- An AND-only tree converts to a list URL (`RouteQuery.location('/invoices', {'f': [...]})`) for a "show matching records" link.

**Backend seam.** Add `Condition` in `com.geneinvoice.common.query`, because `FilterPredicates` and `ValueCoercion` are package-private (`FilterPredicates.java:18`, `ValueCoercion.java:11`):
- A sealed `Condition` with two cases: `Leaf(FilterSpec)` and `Group(Junction AND|OR, List<Condition>)`.
- `validate(TableSchema)` calls `schema.requireFilterable(spec.field(), spec.operator())` on every leaf.
- `PredicateFactory toPredicate(TableSchema)` recurses into `FilterPredicates.build(spec, def, root, q, cb)`, then `cb.and(...)` or `cb.or(...)`.
- Guard empty groups: `cb.or()` with no arguments is FALSE, `cb.and()` with no arguments is TRUE.
- **The compiled `PredicateFactory` is simply appended to the `scope` list**, so `run`, `count`, `ids`, `aggregate` and `inScope` all work without any change to the executor.

**Validate at save time.** Coercion is lazy (§3.3), so run a dry query such as:

```java
queryExecutor.count(type, schema, TableQuery.parseUnpaged(schema, null, List.of()),
        List.of(cond.toPredicate(schema), (r, q, cb) -> cb.disjunction()))
```

Building the predicates forces coercion and throws `BadRequestException` on a bad value. The stored column names become a permanent contract; a later rename or removal makes `requireFilterable` throw "Unknown column" at evaluation time.

### 11.2 (a) As a database predicate over a set (scheduled rule, or "run now")

```java
List<PredicateFactory> scope = new ArrayList<>(regionScope);   // NOT scopeResolver.forX() — no auth in a consumer
scope.add(condition.toPredicate(TableSchemas.INVOICES));
List<Long> ids = queryExecutor.ids(Invoice.class, TableSchemas.INVOICES,
        new TableQuery(0, 500, "id", true, List.of(FilterSpec.parse("id:gt:" + lastId))), scope, 500);
```

- Use keyset paging on `id` (`id` is NUMBER, so `gt` is allowed, and it is sortable). Do not use `parseUnpaged` + `run`, which caps at 5000 rows while reporting the full total.
- For a "N records match" preview, use `queryExecutor.count(...)`.

### 11.3 (b) Against a single entity at trigger time

**Option 1 (recommended): evaluate against the database by id, in the consumer.** This is the `inScope` pattern:

```java
queryExecutor.inScope(Invoice.class, TableSchemas.INVOICES, id, List.of(cond.toPredicate(TableSchemas.INVOICES)))
```

- The semantics match the list exactly, including derived and subquery columns: `overdue`, `outstanding`, POC seats, promise links, `remainingAmount`.
- The cost is one count query per rule per event.
- The consumer sees committed state, so for an "updated" trigger it evaluates the state at consumption time, not at event time.
- An empty condition list returns true (`TableQueryExecutor.java:73`).

**Option 2: in-memory twin**, for evaluating an event-time snapshot or an as-of snapshot. There is no evaluator today. The precedent is `EmailViews.customerTookPart`: a `PredicateFactory` (51-60) paired with a boolean function (62-65). The work involved:
- Add an optional value accessor to `ColumnDef.Builder`, e.g. `.value(Function<Object,Object>)`, beside `.path(...)`.
- Write a `FilterMatcher` in `common.query` that mirrors `FilterPredicates.build` exactly:
  - `isEmpty` on strings trims.
  - `neq`/`notIn` match null.
  - `contains` compares `toLowerCase(Locale.ROOT)` substrings.
  - Money uses `BigDecimal.compareTo`, **never `equals`**, because `equals` is scale-sensitive (100.00 vs 100).
  - Enums compare by name (uppercase).
  - A date-only value on an `Instant` column means `[startOfDay UTC, next day)` for `lte`/`between`.
  - `relative` uses `DateRange.preset(name, today)`.
  - Coerce values through `ValueCoercion.coerce(javaType, raw, column)`.
- Columns that cannot be computed from the entity alone need repository lookups:
  - `customers.outstanding` (`invoiceRepository.sumOutstandingByCustomer`)
  - `customers.successPocUserId` and `collectionPocUserId` (`CustomerPoc`)
  - `promises.invoiceId` and `paymentId` (lazy many-to-many)
- Lazy associations (`Invoice.customer`, `Invoice.salesPoc`, `Payment.customer`, `Payment.collectionPoc`) need an open transaction. Outside web requests there is no open-session-in-view (`application.yml` sets `open-in-view: false`; it is registered only for web).

### 11.4 Trigger coverage gotcha (affects which conditions ever fire)

Invoice `paidAmount`/`status` and customer `creditBalance` change as **side effects**, not through `InvoiceService.update`:
- `PaymentService.applyTo` (171-203) and `reverseAllocations` (220-242)
- `InvoiceService.cancelWithRefund` and `replaceItems` (377-418, no audit)
- `DisputeService.applyInvoiceChange` and `applyPaymentChange` (240-288)

A condition like `status:eq:FULLY_PAID` or `balance:lte:0` is mostly reached through these paths. Publishing the trigger only from the controller-level service methods would miss them.

---

## 12. B1: region scope, injected in one place

1. **Column.** On INVOICES, PAYMENTS, CUSTOMERS, PROMISES and DISPUTES add:

   ```java
   ColumnDef.of("regionId","Region",ColumnType.REFERENCE).reference("region").notSortable().path(ColumnDef.referenceId("region"))
   ```

   Optionally add `regionName` via `nested("region","name")`. "Which regions am I asking about" then becomes an ordinary filter (`regionId:in:1,2`) that already works in URLs, bulk, export, tiles and conditions. Frontend: add a `region` case to `referenceOptionsProvider`, and multi-select support for `in` on references in `filter_editor.dart:240-258`.
2. **Permitted regions.** Add them as a predicate inside every `ScopeResolver.forX()`, including `forDisputes()` (all staff today) and **before** the `canSeeEverything()` early return (lines 76-78 and 119-121). Also apply it in the customer-login branch. Because the executor ANDs scope with filters, asking for a region you cannot read yields an empty result rather than an error.
3. **Read versus manage.** `ScopeResolver` has one scope per entity, and `requireInBook` is shared by get and update/cancel/delete. Region rights differ per operation, so add an access parameter, e.g. `forInvoices(Access.READ|MANAGE)`, and use MANAGE in the write paths:
   - `InvoiceService.update` / `cancel`
   - `CustomerService.update` / `delete`
   - `PaymentService.update`
   - promise `get()`, which every write reuses
   - the bulk `resolveIds`

   `@PreAuthorize` authorities are global and cannot express per-region rights.
4. **Keep `lockedFilters` meaning "book".** Add a separate `Scope` field (e.g. `regionIds`) and a `PageResponse` field (the record is only constructed internally, `PageResponse.java:19, 26`). Otherwise:
   - `DashboardService.paidAgainstBookOnly()` and `coverage()` flip every user to BOOK.
   - The frontend "My records only" chip and `upcomingPromises.book` misreport.
5. **Dashboard.** It takes no filters, so a `regions` parameter must be added to `DashboardController` and threaded into `scoped()` and `idsInScope()`.
6. **Figures summed across entities** ignore scope (§7). Decide whether a customer's `outstanding`/`overdue` counts only invoices in the regions being asked about. If so, change `TableSchemas.customerOutstanding` (135-144), `CustomerService.outstandingSubquery` (103-115) and `InvoiceRepository.sumOutstandingByCustomer`/`sumOverdueByCustomer` (lines 41, 52).
7. **Unscoped paths** listed in §7 need explicit checks.
8. **Frontend.** Region selection passed via `extraParams`/query params is **not** sent with bulk or export (`data_table_scaffold.dart:473-480, 499-505`). Put region as a filter, or add it to those bodies and to `BulkRequest`.

---

## 13. B3: as-of, injected in one place

### Clock reads that must take "as-of today"

- `FilterPredicates.java:68` (`relative`)
- `TableSchemas.java:81` (`overdue`)
- `InvoiceService.java:321` (tiles)
- `CustomerService.java:164` (`toDtos` overdue)
- `InvoiceController.java:139` (export)
- `InvoiceDtos.java:86, 117`
- `DashboardController.java:52-54`
- `PaymentPromiseService.java:272, 493`

`PredicateResolver` has no context parameter. Either widen it (only the three custom filters in `TableSchemas` implement it; no other implementations exist) or use a request/thread-scoped `QueryContext { LocalDate today; Instant asOf; Set<Long> regionIds }`.

### Single seam

- Add `asOf` to the `TableQuery` record (only built at `TableQuery.java:53, 58`), or read it from a `QueryContext`.
- Consume it in `TableQueryExecutor.predicates()` (107-120) to add a validity predicate.
- `DashboardService` and the repository aggregates in `CustomerService.toDtos` bypass the executor and need the same treatment.

### Historical state does not exist today

Entities hold current values only.

`audit_log` (`audit/AuditService.java:18-31`, `audit/AuditLog.java`) is **not** a reliable history:
- Snapshots are heterogeneous: `InvoicePaymentAudit` for `PAYMENT_APPLIED`/`PAYMENT_REVERSED`, strings for `PROMISE_STATUS_CHANGED` and `CUSTOMER_PAYMENT_TERM_CHANGED`, `DueDateSnapshot`.
- Several writes are unaudited: `cancelWithRefund`, `replaceItems`, `voidPayment`/`updateAmount` themselves, and customer credit changes in `applyTo`/`reverseAllocations`.

Versioned history tables are needed.

### Useful property: schemas are attribute-name based

`attr`, `nested` and `referenceId` all resolve through `root.get("<name>")`. A history entity with identical attribute names (`total`, `paidAmount`, `status`, `dueDate`, `customer`, `salesPoc`, ...) plus `validFrom`/`validTo` can therefore be queried with the **unchanged** `TableSchemas.INVOICES`:

```java
queryExecutor.run(InvoiceVersion.class, TableSchemas.INVOICES, q, scope + validAt(asOf), ...)
```

Caveats:
- Subquery columns and scopes hard-code live classes: `customerOutstanding` → `Invoice.class`, `pocSeatPredicate` → `CustomerPoc.class`, `promiseLinkPredicate` → `PaymentPromise.class`, and `ScopeResolver.customerHasPocSeat`/`customerHasInvoiceOwnedBy`. Each needs an as-of variant.
- Check on H2 how Hibernate treats an attribute named `id` that is not the `@Id`, since `inScope` uses `id:eq`.

### Region membership and approvals as-of

- **Region at a date:** the region scope predicate becomes an `EXISTS` over a `(entity_type, entity_id, region_id, valid_from, valid_to)` table when `asOf` is set.
- **"Which approvals were outstanding":** add a BOOLEAN custom-filter column, e.g. `pendingApproval`, as an `EXISTS` over pending-change rows with `created_at <= asOf AND (resolved_at IS NULL OR resolved_at > asOf)`. Copy `pocSeatPredicate` (`TableSchemas.java:146-180`) for the operator switch.
- **Timestamps are missing** on some entities. Payment has neither `createdAt` nor `updatedAt`; Customer and Invoice have no `updatedAt`. "Existed at date X" is only derivable where `createdAt` exists.

---

## 14. B2: pending changes in lists

- Add a `hasPendingChange` BOOLEAN column (EXISTS subquery, `.notSortable()`, dummy `path(root.get("id"))`) and a DTO flag, so a pending change is visible and filterable without having taken effect.
- Bulk actions that cross a threshold become pending rather than succeeding. `BulkResult` needs a `pending` list (`BulkDtos.java:47-55`), and `_showBulkResult` must render it (`data_table_scaffold.dart:552-607`).

---

## 15. Conventions to copy

- **New column with a to-many or cross-table condition:**

  ```java
  .path((root,q,cb)->root.get("id")).notSortable().filter((spec,root,q,cb)-> ...EXISTS subquery...)
  ```

  Handle `isEmpty`/`isNotEmpty`/`eq`/`neq`/`in` explicitly; any other operator is a `BadRequestException` (`TableSchemas.java:146-180, 233-266`). Never use `nested()` over a to-many association: it would inflate counts and aggregates.
- **Scope predicates** are lambdas of type `PredicateFactory`. For correlated subqueries use the passed `q.subquery(...)` (`ScopeResolver.java:135-151`).
- **List controller, list service, `requireInBook`, `resolveIds` + `BulkExecutor.run`:** §2 and §9.
- **Tiles** go through `queryExecutor.aggregate(..., (root,q,cb)->List.of(cb.count(root.get("id")), Aggregates.countWhen(cb, ...), ...))` and are read back with `Aggregates.asLong`/`asMoney` (`InvoiceService.java:318-350`).
- **A new list entity** must be registered in `TableSchemas.buildIndex` (337-338) and `TableSchemaController.VIEW_PRIVILEGE` (21-31), and on the frontend via a route `RouteQuery.read(...)` in `core/router.dart` plus a `DataTableScaffold<T>(entity:, path:, ...)`.
- **Schedulers** wrap their work in try/catch and log (`promise/PromiseSweepScheduler.java:17-30`). They run with no `SecurityContext`, so they must not call `ScopeResolver`, `visibleTo(currentUser...)` or the services' `page`/`get`/`tiles`/`toDto`. `PromiseService.toDto` calls `scopeResolver.canSeePoc()`.

---

## 16. Risks and gotchas

1. **AND only.** Any OR must be compiled into a single `Predicate`. Don't try to express it through `TableQuery.filters`.
2. **Value errors appear at execution, not parse.** Stored conditions need a dry-run validation at save time; enum values or presets removed later will fail at run time.
3. **`FilterPredicates` and `ValueCoercion` are package-private.** The condition compiler and any in-memory matcher belong in `common.query`.
4. **`neq`/`notIn` include NULL, and string `isEmpty` includes whitespace.** An in-memory twin must match this.
5. **DATE columns have no `eq`**; use `between d,d`. `relative` excludes today from both `past` and `future`. All day boundaries are UTC.
6. **Single-valued values are not trimmed** at parse (only in coercion). Multi-valued values cannot contain commas.
7. **`parseUnpaged` + `run` silently truncate at 5000 rows** while `totalElements` stays uncapped.
8. **`inScope` with an empty scope returns true** without checking that the row exists.
9. **Consumers and schedulers have no authentication.** `CurrentUser.require()` throws, and so does every `ScopeResolver` and service read method. Use `TableQueryExecutor` directly with explicit scopes and the full schemas.
10. **Custom-filter columns have dummy paths.** Sorting or aggregating on `overdue`, the POC seat columns, `invoiceId` or `paymentId` is meaningless, and an in-memory evaluator needs bespoke code for each.
11. **`lockedFilters` is overloaded as the "book mode" signal** in the dashboard and the frontend. Do not add region or as-of entries to it.
12. **Frontend `extraParams` are dropped from bulk and export.** `TableFilter.label` is not persisted. The reference picker falls back to customers for unknown kinds. `addFilter` dedupes on field plus operator.
13. **Column names are a public contract:** URLs, dashboard deep links, and stored rules.
14. **`SCOPE_OVERRIDE` returns `Scope.empty()`** and bypasses every predicate added after that line in `ScopeResolver`.
15. **Several read paths bypass the executor** (§7) and would leak across regions or as-of boundaries.
16. **No tests cover `common/query`.** Add tests under `backend/src/test/java/com/geneinvoice/` using `IntegrationTestBase` (H2, `actAs(user)`, `as(user)` for MockMvc), including one test per operator/type pair for the in-memory twin versus the database result.