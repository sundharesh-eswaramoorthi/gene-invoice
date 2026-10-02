# Engineering map: authentication, users, roles and privileges

Scope: `backend/…/{auth,privilege,role,user,config}/**` plus every place that checks rights (all controllers, `poc/ScopeResolver`, service `get`/`requireInBook` paths), and the Flutter side (`features/auth`, `features/users`, `shared/models/{privileges,auth_models,user}.dart`, `core/router.dart`, `shared/widgets/app_shell.dart`, `core/api/api_client.dart`). This was read-only research and no file was edited. All paths below are relative to ``, and Java paths are under `backend/src/main/java/com/geneinvoice/`.

---

## 0. Summary

- **Model.** A `User` has exactly one `Role` (`@ManyToOne(EAGER, optional=false)`). A `Role` has a set of `Privilege`s (`@ManyToMany(EAGER)`). There is no region, no per-record grant and no second role.
- **Authorities.** The Spring authorities are `"ROLE_<roleName>"` plus the name of each privilege. They are rebuilt from the database on every request, because `JwtAuthFilter` calls `loadUserByUsername` each time. The JWT carries only `sub`, `role` and `cga`, never the privileges.
- **Four enforcement layers.**
  1. `@PreAuthorize("hasAuthority('" + Privileges.X + "')")` on controller methods. This is the only annotation style used.
  2. Manual checks: `currentUser.has(...)`, `currentUser.isCustomer()`, `currentUser.canAssignPoc(userRepository)`, throwing `AccessDeniedException("Not allowed")`.
  3. Row scope: `ScopeResolver.forX()` returns `List<PredicateFactory>`. It is applied to list, count, tiles, bulk ids, export and `get` (through `queryExecutor.inScope`). A record outside scope answers `NotFoundException` (404), per AUTH-08.
  4. Field visibility: `scopeResolver.canSeePoc()`, and `TableSchema.visibleTo(isCustomer)` dropping `pocRestricted` columns.
- **Two axes, not one.** "Customer login" means `user.customerId != null`. It is orthogonal to privileges and checked everywhere.
- **Seeded logins.** `admin/admin123` (ADMIN, all 31 privileges) and `cashier/cashier123` (CASHIER). There are no seeded POC, VIEWER or customer logins.
- **B1 (per-region rights) is a structural change.** Every "has privilege X" becomes "has X in region R". The single choke points for this are `AppUserDetailsService.buildAuthorities`, `CurrentUser`, `ScopeResolver` and `UserRepository.hasPrivilege/findAssignable/countActiveHolders`. Several write paths do no scope check at all today; the create paths are listed in §10.

---

## 1. Authentication pipeline (backend)

### 1.1 SecurityConfig: `config/SecurityConfig.java`
- `@Configuration @EnableMethodSecurity @RequiredArgsConstructor` (lines 25–28). Method security is on, so `@PreAuthorize` works on any bean. By convention it is only used on controllers.
- `BCryptPasswordEncoder` (32–35).
- CORS allows all origin patterns and exposes `Authorization` (42–53).
- Filter chain (55–70):
  - CSRF off, `SessionCreationPolicy.STATELESS`.
  - The unauthenticated entry point returns `HttpStatusEntryPoint(UNAUTHORIZED)`, so the result is a 401 with no body.
  - `permitAll` covers: `/api/auth/**`, `/actuator/health`, `/h2-console/**` (line 63), and `POST /api/mail-service/events` (line 64). The mail-service endpoint is authenticated by an HMAC header instead; see `email/mailservice/MailServiceEventsController.java:45-50`.
  - `.anyRequest().authenticated()` (65).
  - `jwtAuthFilter` sits before `UsernamePasswordAuthenticationFilter` (68).
- `/api/auth/**` being `permitAll` also covers `/api/auth/me` and `/api/auth/change-password`. Those guard themselves by throwing `BadCredentialsException`: `AuthController.java:61-63` and `CurrentUser.require()`.

### 1.2 JWT: `auth/JwtService.java`
- The secret comes from `app.jwt.secret`; `app.jwt.expiration-ms` is 86400000 (24 h) per `backend/src/main/resources/application.yml`. The secret is base64-decoded if that yields at least 32 bytes, otherwise the raw bytes are used (23–38).
- `generateToken(username, claims)` sets `sub`, `iat` and `exp`, signed with HS (42–51).
- `claimsFor(User)` returns `{"role": roleName, "cga": credentialsChangedAt epoch ms}` (53–60). **No privileges and no regions are in the token.**
- `isCurrent(claims, credentialsChangedAt)`: a token minted before the last password change is rejected (62–66, AUTH-04).

### 1.3 Filter: `auth/JwtAuthFilter.java`
- On `Bearer` it parses the token and calls `userDetailsService.loadUserByUsername(sub)`. That is **a database load on every request** (36).
- It skips authentication if the user is `!isEnabled()` (i.e. `active=false`) (37–40), or if the token is older than `credentialsChangedAt` (44–48).
- It sets a `UsernamePasswordAuthenticationToken(userDetails, null, authorities)` (49–52).
- Any exception clears the context and the request continues unauthenticated, which ends in a 401 (54–56).
- Consequence: a privilege or role change takes effect on the user's **next request**. Tokens do not need to be revoked.

### 1.4 Principal: `auth/AppUserDetails.java`, `auth/AppUserDetailsService.java`
- `AppUserDetails(User user, Collection<GrantedAuthority>)`. It holds the entity itself. `isEnabled() = user.isActive()` (27); the other flags are always true.
- `loadUserByUsername` uses `findByUsernameIgnoreCase` (27, CP-06).
- The authority builder is `public static`, and tests reuse it (`IntegrationTestBase.java:134,141`):
  ```java
  // AppUserDetailsService.java:32-38
  public static List<GrantedAuthority> buildAuthorities(User user) {
      if (user.getRole() == null) return List.of();
      Stream<SimpleGrantedAuthority> roleAuth = Stream.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().getName()));
      Stream<SimpleGrantedAuthority> privs = user.getRole().getPrivileges().stream()
              .map(p -> new SimpleGrantedAuthority(p.getName()));
      return Stream.concat(roleAuth, privs).collect(Collectors.toList());
  }
  ```

### 1.5 `auth/CurrentUser.java`
This is the injectable helper every service and controller uses.

| Method | Lines | Behaviour |
|---|---|---|
| `User require()` | 12–18 | Throws `BadCredentialsException("Not authenticated")` (401) if no `AppUserDetails` principal. **It throws on scheduler and consumer threads.** |
| `Long customerIdOrNull()` | 20–22 | `require().getCustomerId()`, so it also throws without a principal |
| `Long idOrNull()` | 24–29 | Null-safe; use it in background code |
| `boolean isCustomer()` | 31–33 | `customerId != null` |
| `boolean has(String privilege)` | 35–39 | Checks **SecurityContext authorities** (no database call) |
| `boolean canAssignPoc(UserRepository)` | 41–45 | Staff and `userRepository.hasPrivilege(id, POC_ASSIGN)`. This is a **database query**, not the authorities |

There are two sources of truth: `has()` reads the authorities, while `ScopeResolver` and `canAssignPoc` run `UserRepository.hasPrivilege` queries (`user/UserRepository.java:54-60`). Both reflect the role at request time. With regions, both must become region-aware.

### 1.6 `auth/AuthController.java` (`/api/auth`)
- **`POST /login`** (35–56): authenticates through the `AuthenticationManager`. It returns `LoginResponse(token, expiresInMs, UserInfo(id, username, fullName, role, privileges, customerId))`. `BadCredentialsException` is rethrown as "Invalid username or password", which maps to 401.
- **`GET /me`** (58–68): returns the same `UserInfo`, with privileges taken from `u.getRole().getPrivileges()`.
- **`POST /change-password`** (70–93):
  - Runs `Passwords.require` (min length 6, `common/Passwords.java:5-13`).
  - The current password must match, and the new one must differ.
  - Sets `credentialsChangedAt=now` and returns `{"status":"ok","token":<new token>}`.
- DTOs are records in `auth/dto/LoginRequest.java` (`@NotBlank username, password`) and `auth/dto/LoginResponse.java` (nested `UserInfo` record, 10–17).

### 1.7 Exceptions: `common/GlobalExceptionHandler.java`
| Exception | Status | Lines |
|---|---|---|
| `NotFoundException` | 404 | 34–38 |
| `BadRequestException` | 400 | 40–43 |
| `BadCredentialsException` / `AuthenticationException` | 401 | 45–55 |
| `AccessDeniedException` | 403, message **always** "You do not have permission for this action" (the original message is dropped) | 57–61 |
| `InvalidFieldsException(Map)` | 400 with `fieldErrors` | 73–90 |
| `DataIntegrityViolationException` | 409 | 133–137 |
| `ConcurrencyFailureException` / optimistic lock | 409 | 139–145 |

The error body is the record `ApiError(timestamp, status, error, message, path, fieldErrors)` (`common/ApiError.java`). Custom exceptions are plain `RuntimeException` subclasses with a message constructor (`common/BadRequestException.java`, `common/NotFoundException.java`).

---

## 2. Data model

### 2.1 Entities (copy this style)
```java
// privilege/Privilege.java:6-24
@Entity @Table(name = "privileges")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Privilege {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(unique = true, nullable = false, length = 100) private String name;
    @Column(length = 255) private String description;
}
```
- **`role/Role.java` (10–37).** Table `roles`. `name` is unique, not null, length 80. Privileges are mapped as:
  ```java
  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(name = "role_privileges", joinColumns = @JoinColumn(name = "role_id"),
             inverseJoinColumns = @JoinColumn(name = "privilege_id"))
  @Builder.Default private Set<Privilege> privileges = new HashSet<>();
  ```
- **`user/User.java` (9–55).** Table `users`.
  - `username`: unique, not null, 80. `email`: unique, 120, nullable. `password`: not null. `fullName`: 120. `active` is a `@Builder.Default` of true.
  - `@ManyToOne(fetch = EAGER, optional = false) @JoinColumn(name="role_id") Role role` (38–40).
  - `@Column(name="customer_id") Long customerId`. This is a plain Long, not a relation; when set, the user is a customer login.
  - `credentialsChangedAt`.
  - `createdAt` is `updatable=false` and set by `@PrePersist onCreate()` (48–54).

### 2.2 Repositories
- `privilege/PrivilegeRepository.java`: `findByName`.
- `role/RoleRepository.java`: `findByName`, `existsByNameIgnoreCase`, `existsByNameIgnoreCaseAndIdNot`.
- `user/UserRepository.java`:
  - Username and email lookups, case-insensitive (13–25).
  - `countByRoleId` (26).
  - `findInternalIdsByRoleId` (28–29).
  - `clearBlankEmails` (31–33).
  - `findByCustomerId` (34).
  - `findByRoleName(String)` (35). Used by `notifyAdmins`; it is **keyed on a role name**.
  - `findAssignable(privilege, q, Pageable)` (37–52): active, internal (staff) users whose role holds the privilege. This backs the POC picker.
  - `hasPrivilege(userId, privilege)` (54–60): joins role and privileges. It does **not** check `active` or `customerId`.
  - `countActiveHolders(privilege, excludeUserId, excludeRoleId)` (62–74): backs the "last administrator" guards.

---

## 3. Privilege catalogue

The source of truth is `privilege/Privileges.java` (constants at 8–52, `ALL` at 54–71). The frontend mirror is `frontend/lib/shared/models/privileges.dart` (4–47), which has no `ALL` list. There are 31 privileges.

| Privilege | Enforced at (non-exhaustive, all real) |
|---|---|
| USER_VIEW | `UserController` list/get/export (97,110,284); `GmailConnectionController.ofUser` (42); table-schema "users"; audit type USER |
| USER_MANAGE | `UserController` create/update/delete/bulk (117,149,195,246); `GmailConnectionController.disconnectUser` (48); last-admin invariants (`UserController.java:229-234`, `RoleController.java:161-165`) |
| ROLE_VIEW | `RoleController` list/get/export (63,76,102); `PrivilegeController.list` (25) |
| ROLE_MANAGE | `RoleController` create/update/delete (109,123,169) |
| CUSTOMER_VIEW / _MANAGE | `CustomerController` (47–86,125,160); `DocumentTargets` (26–34); `AuditController` (41–47) |
| PRODUCT_VIEW / _MANAGE | `ProductController` (67–159) |
| INVOICE_VIEW / _MANAGE | `InvoiceController` (47–177); `DashboardController` billed/outstanding/top (22,28,34) |
| PAYMENT_VIEW / _MANAGE | `PaymentController` (52–131); `DashboardController` collected/top-paying (40,46) |
| DISPUTE_CREATE | `DisputeController.create` (61); `DisputeService.open` additionally requires a customer login (`DisputeService.java:55-58`) |
| DISPUTE_VIEW | `DisputeController` list/get/export (36,55,81) |
| DISPUTE_MANAGE | `DisputeController` approve/deny (67,74) |
| NOTIFICATION_VIEW | `NotificationController` (all) |
| AUDIT_VIEW | `AuditController.history` (68), **and** the entity's VIEW privilege (98–104) |
| POC_VIEW | `PocController` (26,36,51); `CustomerController.pocs` (89); `ScopeResolver.canSeePoc` for field visibility |
| POC_ASSIGN | `CustomerController` add/remove/primary POC (97,107,115); `CurrentUser.canAssignPoc` in updates and bulk reassign |
| POC_ASSIGNABLE_SALES/SUCCESS/COLLECTION | `poc/PocType.java:9-11`; `ScopeResolver.isAssignableAs` (book scoping); `PocService.requireAssignable` (54–72); `findAssignable` |
| SCOPE_OVERRIDE | `ScopeResolver.canSeeEverything` (39–43): not limited to one's POC book; `/api/pocs/my-scope` "clearable" |
| PROMISE_VIEW / _MANAGE / _OVERRIDE | `PaymentPromiseController` (43–146); `/recompute` uses PROMISE_OVERRIDE (56–64, AUTH-05) |
| EMAIL_VIEW / EMAIL_SEND | `EmailController`, `InboxController`, `GmailConnectionController`; `EmailViews.viewer` (`email/EmailViews.java:44-47`); `GmailDisconnects.mayConnect` (`email/connection/GmailDisconnects.java:62-65`); `RoleController.sendsEmail` (143–145) |
| DOCUMENT_VIEW / _MANAGE | `DocumentController`; `DocumentTargets.canManage` needs DOCUMENT_MANAGE **and** the entity's MANAGE privilege (62–66) |
| EXPORT_DATA | Every `/export` endpoint, combined as `EXPORT_DATA and X_VIEW` |

### 3.1 Seeded role → privilege matrix (`config/DataSeeder.java`)
| Role | How seeded | Privileges |
|---|---|---|
| ADMIN | `upsertRole` (54) | **All** (`Privileges.ALL`), reset on every boot |
| CASHIER | `upsertRole` (56–68) | CUSTOMER_VIEW/MANAGE, PRODUCT_VIEW, INVOICE_VIEW/MANAGE, PAYMENT_VIEW/MANAGE, NOTIFICATION_VIEW, POC_VIEW, POC_ASSIGN, PROMISE_VIEW, SCOPE_OVERRIDE, EMAIL_VIEW/SEND, DOCUMENT_VIEW/MANAGE, EXPORT_DATA. **No** AUDIT_VIEW, DISPUTE_*, PROMISE_MANAGE or POC_ASSIGNABLE_* |
| VIEWER | `upsertRole` (69–77) | CUSTOMER_VIEW, PRODUCT_VIEW, INVOICE_VIEW, PAYMENT_VIEW, NOTIFICATION_VIEW, POC_VIEW, PROMISE_VIEW, SCOPE_OVERRIDE, EMAIL_VIEW, DOCUMENT_VIEW |
| CUSTOMER | `upsertRole` (78–91) | CUSTOMER_VIEW, INVOICE_VIEW, PAYMENT_VIEW, DISPUTE_CREATE, DISPUTE_VIEW, NOTIFICATION_VIEW, AUDIT_VIEW, PROMISE_VIEW, EMAIL_VIEW, EMAIL_SEND, DOCUMENT_VIEW, plus DOCUMENT_MANAGE only if already held or created on this boot (`keptOrNew`, 90) |
| SALES_POC | `createRoleIfAbsent` (93–107) | CUSTOMER_VIEW, PRODUCT_VIEW, INVOICE_VIEW/MANAGE, PAYMENT_VIEW, DISPUTE_VIEW, NOTIFICATION_VIEW, AUDIT_VIEW, POC_VIEW, POC_ASSIGN, POC_ASSIGNABLE_SALES, PROMISE_VIEW, EMAIL_VIEW/SEND, DOCUMENT_VIEW/MANAGE, EXPORT_DATA. **No SCOPE_OVERRIDE**, so this role is book-scoped |
| CUSTOMER_SUCCESS_POC | `createRoleIfAbsent` (108–123) | Like SALES_POC but with CUSTOMER_MANAGE, POC_ASSIGNABLE_SUCCESS and SCOPE_OVERRIDE, and without INVOICE_MANAGE |
| COLLECTION_POC | `createRoleIfAbsent` (124–138) | CUSTOMER_VIEW, INVOICE_VIEW, PAYMENT_VIEW/MANAGE, DISPUTE_VIEW, NOTIFICATION_VIEW, AUDIT_VIEW, POC_VIEW, POC_ASSIGN, POC_ASSIGNABLE_COLLECTION, PROMISE_VIEW/MANAGE/OVERRIDE, SCOPE_OVERRIDE, EMAIL_VIEW/SEND, DOCUMENT_VIEW/MANAGE, EXPORT_DATA |

Role-name constants are `DataSeeder.ROLE_SALES_POC / ROLE_SUCCESS_POC / ROLE_COLLECTION_POC` (29–31).

---

## 4. DataSeeder mechanics (`config/DataSeeder.java`)

`@Component`, `CommandLineRunner`, `@Transactional run()` (24–40). It runs **after** the context has started, so after all `*SchemaUpgrade` `InitializingBean`s.

1. `clearBlankEmails()` (41–42).
2. Every name in `Privileges.ALL` is created if missing (description = name), and newly created names are tracked in `createdNow` (44–52).
3. Role seeding has three strategies with different overwrite semantics:
   - **`upsertRole(name, desc, privs)`** (167–173) **replaces** the privilege set on every boot. ADMIN, CASHIER, VIEWER and CUSTOMER are therefore reset each restart, and edits made through the Roles UI to those four roles are lost.
   - **`createRoleIfAbsent(name, desc, createdNow, privs)`** (175–192) creates the role if missing. Otherwise it only adds privileges that were **created on this boot** and are in its list, so admin edits survive.
   - **`keptOrNew`** (194–203) keeps a privilege only if it is already held or new on this boot.
4. **`pickByNames`** (205–211) silently ignores unknown names (`ifPresent`). A constant missing from `Privileges.ALL` vanishes without any error.
5. Seeded users are created only if the username is absent:
   - `admin` / `admin123`: ADMIN, email `admin@geneinvoice.local`, full name "System Administrator" (140–151).
   - `cashier` / `cashier123`: CASHIER, email `cashier@geneinvoice.local` (153–164).

**Manual testing notes.**
- No POC users are seeded. On a fresh database the only user assignable as Sales or Collection POC is `admin`, because ADMIN holds every privilege.
- Invoice creation requires `salesPocUserId` (`InvoiceService.create` → `pocService.requireAssignable`, `invoice/InvoiceService.java:64`), and payment recording requires a collection POC (`payment/PaymentService.java:61`). As `cashier` you must pick `admin` as the POC.
- Customer logins are created through `POST /api/customers` with `username` and `password` in the body (`customer/CustomerService.java:205-248`, role looked up by the literal `"CUSTOMER"` at 223–224).
- Staff users of other roles are created through `POST /api/users` (the CUSTOMER role is refused there: `UserController.java:134-136`).
- Tests (`backend/src/test/java/com/geneinvoice/IntegrationTestBase.java`):
  - `user(username, roleName)` creates a user with password `"password"` (96–105).
  - `customerUser` (107–117).
  - `actAs(User)` sets the SecurityContext (132–137).
  - `as(User)` is a MockMvc post-processor (139–144).
  - `resetTransactionalData` deletes every user except `admin` and `cashier` (80–82).
  - The test profile is H2 with `ddl-auto: create-drop` (`backend/src/test/resources/application-test.yml`).

---

## 5. How a check is expressed

### 5.1 Controller gate (the convention to copy)
```java
@GetMapping
@PreAuthorize("hasAuthority('" + Privileges.INVOICE_VIEW + "')")
...
@PostMapping("/export")
@PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('" + Privileges.INVOICE_VIEW + "')")
```
- The privilege is always given as a `Privileges.X` constant concatenated into SpEL. There is **no** `hasRole` anywhere, and AUTH-05 explicitly moved away from role names (`promise/PaymentPromiseController.java:57-60`).
- Endpoints with no `@PreAuthorize` that rely on `authenticated()` plus manual checks:
  - `TableSchemaController` (`common/query/TableSchemaController.java:42-71`), which filters entities via its `VIEW_PRIVILEGE` map (21–31) and `currentUser.has`.
  - `GmailConnectionController.disconnect`, which uses `isAuthenticated()` (35).
  - `AuthController.me` and `change-password`.

### 5.2 Manual checks inside controllers and services
- **Non-customer caller:**
  ```java
  private void requireNonCustomerCaller() {
      if (currentUser.isCustomer()) throw new AccessDeniedException("Not allowed");
  }
  ```
  Used at `poc/PocController.java:62-66` and `customer/CustomerController.java:211-215`.
- **Privilege by entity type** through a static map plus `currentUser.has`:
  - `audit/AuditController.java:41-47,98-104`
  - `document/DocumentTargets.java:26-34,48-72`
  - `email/EmailTargets.java:207-212`
- **POC reassign guard:** `currentUser.canAssignPoc(userRepository)`. The exception type is **inconsistent**:
  - `AccessDeniedException` (403, D-46): `customer/CustomerController.java:133-137`.
  - `BadRequestException("You may not change …")` (400): `invoice/InvoiceController.java:117-119`, `payment/PaymentController.java:123-125`, `promise/PaymentPromiseController.java:134-136`, `invoice/InvoiceService.java:134-136`, `payment/PaymentService.java:121-123`, `promise/PaymentPromiseService.java:124-126`.
- **Customer-only operation:** `DisputeService.open` rejects staff with `AccessDeniedException("Only customers can open disputes")` (`dispute/DisputeService.java:53-58`).
- **Invariants on users and roles:**
  - You cannot deactivate yourself or change your own role (`UserController.java:158-161`).
  - You cannot delete yourself (198–200).
  - You cannot delete a customer login directly (204–206).
  - The last active USER_MANAGE holder must remain (`UserController.java:229-234`, `RoleController.java:161-165`).
  - You cannot strip USER_MANAGE or ROLE_MANAGE from your own role (`RoleController.java:147-160`).
  - A role that is assigned to users cannot be deleted (`RoleController.java:168-178`).
  - Bulk actions refuse your own row (`UserController.java:259-261`).

### 5.3 Row scope: `poc/ScopeResolver.java`
The single source of row visibility for customers, invoices, payments, promises and disputes.

```java
public record Scope(List<PredicateFactory> predicates, List<String> lockedFilters) { ... }   // 27-31
```

- **Customer login:** the predicate is `customer.id == own` (113–118; 71–75 for customers; 90–95 for disputes).
- **Staff with SCOPE_OVERRIDE** (`canSeeEverything`, 39–43): `Scope.empty()`, meaning everything is visible.
- **Staff without override:**
  - Invoices: `salesPoc.id == me`, locked filter `salesPocUserId:eq:<me>`.
  - Payments and promises: `collectionPoc.id == me`, locked filter `collectionPocUserId:eq:<me>`.
  - Customers: a POC seat for me **or** an invoice where I am the Sales POC, locked filter `myBook:eq:<me>` (66–87).
  - If the caller is not assignable as that POC type, `nothing()` returns `cb.disjunction()` (131–133).
- **Disputes for staff:** `Scope.empty()` (96). Any staff with DISPUTE_VIEW sees **all** disputes, with no POC-book limit.
- `canSeePoc()` (33–37): staff with POC_VIEW. It controls whether POC fields appear in DTOs and exports (e.g. `InvoiceDtos.InvoiceDto.from(inv, scopeResolver.canSeePoc())`, `invoice/InvoiceController.java:70`).
- Every call to `canSeeEverything`, `isAssignableAs` or `canSeePoc` issues a `userRepository.hasPrivilege` **database query**.

How the scope is consumed (the pattern new scoped entities must copy):
```java
// customer/CustomerService.java:66-72
ScopeResolver.Scope scope = scopeResolver.forCustomers();
var page = queryExecutor.run(Customer.class, TableSchemas.CUSTOMERS, query, scope.predicates(), List.of());
return PageResponse.of(toDtos(page.content()), query, page.total(), scope.lockedFilters());
// single-record read: customer/CustomerService.java:129-145 ; invoice/InvoiceService.java:260-286
if (!queryExecutor.inScope(Invoice.class, TableSchemas.INVOICES, id, scopeResolver.forInvoices().predicates()))
    throw new NotFoundException("Invoice not found");
```
- `TableQueryExecutor.inScope` returns true immediately if the scope is empty (`common/query/TableQueryExecutor.java:71-75`).
- `run`, `count`, `ids` and `aggregate` all AND the scope predicates with the user's filters (107–120).
- Per AUTH-08, a record outside scope or belonging to another customer returns **404**, not 403 (`invoice/InvoiceService.java:265-270`, `payment/PaymentService.java:276-283`, `promise/PaymentPromiseService.java:509-518`).
- `DisputeService.get` still returns **403** for another customer's dispute (`dispute/DisputeService.java:147-150`).

Lists that are **not** scoped (`List.of()`): users (`UserController.java:104`), roles (`RoleController.java:70`), products (`ProductController.java:74`). Notifications and inbox are scoped by owner: `ownedByCaller()` in `notification/NotificationController.java:103-106` and `email/InboxController.java:103-108`.

### 5.4 Field visibility
- `TableSchema.visibleTo(boolean customerScoped)` drops `pocRestricted()` columns for customer logins (`common/query/TableSchema.java:46-50`).
- Every entity controller builds `schema()` as `TableSchemas.X.visibleTo(currentUser.isCustomer())` (e.g. `invoice/InvoiceController.java:42-44`).

### 5.5 Audit convention
```java
// audit/AuditService.java:18-32
auditService.record(String entityType, Long entityId, String action, Object before, Object after,
                    Long userId, Long disputeId, String reason);
```
- Before and after are DTO or snapshot records serialised to JSON, and `reason` is capped at 500 characters.
- User writes are audited: `USER_UPDATED`, `USER_DEACTIVATED`, `USER_DELETED`, `USER_ACTIVATED` (`UserController.java:188,213,223,272`). **User create is not audited** (`UserController.java:116-146`).
- **Role create, update and delete are not audited at all** (`RoleController.java:108-178`). The UI states that "Roles keep no audit history" (`frontend/lib/features/users/role_detail_screen.dart:13-14`).
- In background code, pass `currentUser.idOrNull()`, as `PaymentService.record` and `InvoiceService.create` do (`invoice/InvoiceService.java:88`).

---

## 6. Endpoint → gate matrix

Format: `METHOD path`, then the annotation privilege, then any extra runtime checks.

**Auth** (`/api/auth`, permitAll): `POST /login`; `GET /me` (principal required); `POST /change-password` (principal required).

**Users** (`user/UserController.java`):
- `GET /api/users` (USER_VIEW)
- `GET /{id}` (USER_VIEW)
- `POST` (USER_MANAGE; refuses the CUSTOMER role; duplicate and password checks)
- `PUT /{id}` (USER_MANAGE; self-guard, last-admin guard, Gmail disconnect)
- `DELETE /{id}` (USER_MANAGE; soft-deactivates if referenced as a POC, 208–218)
- `POST /bulk` with ACTIVATE or DEACTIVATE (USER_MANAGE)
- `POST /export` (EXPORT_DATA + USER_VIEW)

**Gmail per user:** `GET /api/users/{id}/gmail` (USER_VIEW); `DELETE /api/users/{id}/gmail` (USER_MANAGE); `GET/PUT /api/me/gmail` (EMAIL_SEND, staff only per `GmailConnectionService.java:113-117`); `DELETE /api/me/gmail` (authenticated).

**Roles** (`role/RoleController.java`):
- `GET /api/roles` and `GET /{id}` (ROLE_VIEW)
- `POST` (ROLE_MANAGE; name uniqueness is case-insensitive)
- `PUT /{id}` (ROLE_MANAGE; `requireAdministrationSurvives`; disconnects Gmail if EMAIL_SEND was removed, 132–139)
- `DELETE /{id}` (ROLE_MANAGE; refused while users hold the role)
- `POST /export` (EXPORT_DATA + ROLE_VIEW)

**Privileges:** `GET /api/privileges` (ROLE_VIEW) returns `[{id,name,description}]` (`privilege/PrivilegeController.java:18-28`).

**POC:** `GET /api/pocs/assignable?type&q&limit`, `/types` and `/my-scope` (POC_VIEW + non-customer).

**Customers:** list, summary, get (CUSTOMER_VIEW + scope); create, update, delete (CUSTOMER_MANAGE; update and delete run `requireInBook`, **create does not**); `GET /{id}/pocs` (POC_VIEW + non-customer); `POST/DELETE /{id}/pocs…` and `/primary` (POC_ASSIGN + non-customer); `POST /bulk` ADD_POC (CUSTOMER_MANAGE + `canAssignPoc`); `/export`.

**Invoices:** list, summary, get, `/assignable-check` (INVOICE_VIEW); `/due-date-preview` (INVOICE_MANAGE + `requireCustomerInBook`, `InvoiceService.java:207-210`); create (INVOICE_MANAGE, **no customer book check**); `PATCH /{id}` and `/cancel` (INVOICE_MANAGE + `requireInBook`); `/bulk` CANCEL or REASSIGN_SALES_POC; `/export`.

**Payments:** list, summary, get, `/credits/{customerId}` (PAYMENT_VIEW + customer scope, 93–108); create (PAYMENT_MANAGE, **no book check on the customer**); `PATCH /{id}` (PAYMENT_MANAGE + `requireInBook`); `/bulk` REASSIGN_COLLECTION_POC; `/export`.

**Promises:** list, summary, get (PROMISE_VIEW); create, update, cancel, bulk (PROMISE_MANAGE; **create has no book check**, `PaymentPromiseService.java:71-74`); override, clear-override, `/recompute` (PROMISE_OVERRIDE); `/export`.

**Disputes:** list, get, export (DISPUTE_VIEW; staff see all); create (DISPUTE_CREATE + customer login); approve, deny (DISPUTE_MANAGE).

**Documents:** upload, patch, delete (DOCUMENT_MANAGE + `DocumentTargets.requireManageable`); list, count, download (DOCUMENT_VIEW + `requireVisible`).

**Emails / Inbox:** see §3. **Notifications:** NOTIFICATION_VIEW, owner-scoped. **Dashboard:** INVOICE_VIEW or PAYMENT_VIEW, plus ScopeResolver. **Audit:** AUDIT_VIEW + entity VIEW + a read of the record through the scoped `get`. **Products:** PRODUCT_VIEW / PRODUCT_MANAGE. **Table-schemas:** authenticated + `mayRead`.

---

## 7. Frontend

### 7.1 Models
- **`frontend/lib/shared/models/auth_models.dart`**, class `CurrentUser` (3–38): `id, username, fullName, role, Set<String> privileges, int? customerId`.
  - `isCustomer => customerId != null` (31).
  - **`isAdmin => role == 'ADMIN'`** (32). This is keyed on the role name.
  - `has(p)` (34), `hasAny(ps)` (35).
  - `canRaiseDispute` (37).
  - `LoginResult` (40–52).
- **`frontend/lib/shared/models/user.dart`:**
  - `AppUser` (1–35) does **not** parse `privileges` from `UserDto`.
  - `AppRole` has `List<String> privileges` (37–58).
- **`frontend/lib/shared/models/privileges.dart`:** the string constants (1–48). They must be kept in sync by hand with `Privileges.java`.

### 7.2 Session: `features/auth/auth_controller.dart`, `core/api/api_client.dart`, `core/storage/secure_storage.dart`
- `AuthController` (`StateNotifier<AuthState>`):
  - `bootstrap()` reads the token and calls `GET /api/auth/me`; on failure it clears the token (29–43).
  - `login()` calls `POST /api/auth/login` and saves the token (45–61).
  - `logout()` clears the token and the page-size store (63–68).
- `currentUserProvider` (75–76) is the object every screen reads.
- **The user's privileges are cached for the whole session.** They are only refreshed at app start or login, so a role change is not reflected in the UI until reload. The backend enforces the change immediately.
- Dio attaches `Authorization: Bearer <token>` (`api_client.dart:26-33`). A 401 on any non-`/api/auth/*` path, or on `/api/auth/me`, triggers logout (34–52). **A 403 does not**; it surfaces through `apiErrorMessage` (54–68) as "You do not have permission for this action".
- Token storage: `flutter_secure_storage`, or `SharedPreferences` on web (`secure_storage.dart`).
- The change-password dialog stores the new token returned by the server (`features/auth/change_password_dialog.dart:55-59`).

### 7.3 Route guards: `core/router.dart`
- Global redirect: not logged in → `/login`; logged in and at `/login` → `/` (64–70).
- Per-route guard:
  ```dart
  GoRouterRedirect needs(List<String> privileges) => (context, state) {
    final user = ref.read(authControllerProvider).user;
    if (user == null) return null;
    return privileges.isEmpty || user.hasAny(privileges) ? null : '/';
  };                                               // 44-48
  ```
- Applied as `redirect: needs(navPrivilegesFor('/customers'))` on the list routes `/inbox`, `/customers`, `/products`, `/invoices`, `/payments`, `/promises`, `/disputes`, `/users` and `/roles`. `/notifications` uses `needs(const [Privileges.notificationView])` (209).
- **Unguarded routes:** `/` (76), `/me/gmail` (84), **`/invoices/new`** (126), and every `/:id` detail route (93–251). Detail routes rely on the backend's 404/403, which render as `RecordUnavailable`.
- The router does not enforce `hideForCustomer` (only the nav does).

### 7.4 Nav gating: `shared/widgets/app_shell.dart`
- `NavEntry(label, icon, path, requiredAnyOf, {hideForCustomer, unreadBadge})` (12–22).
- `navEntries` (24–45) lists Dashboard, Inbox, Customers, Invoices, Payments, Promises, Disputes, Products, Users and Roles. **Add new screens (Tasks, Rules, Approvals, Regions) here and in the router.**
- `navPrivilegesFor(path)` (50–55) is shared with the router (UI-10).
- Visibility filter (76–80): customers lose `hideForCustomer` entries; an entry with an empty list is always shown; otherwise `hasAny`.
- The notifications bell needs NOTIFICATION_VIEW (90–91). The account menu shows `user.role` (113–115, 152).

### 7.5 Per-screen gating inventory
Every site that must become record-region-aware under B1:

| File:line | Gate |
|---|---|
| `features/customers/customers_screen.dart:49-51` | customerManage, exportData, promiseManage |
| `features/customers/customer_detail_screen.dart:195-201` | customerManage, auditView, promiseView, dispute* |
| `features/invoices/invoices_screen.dart:36-38` | invoiceManage, exportData, promiseManage |
| `features/invoices/invoice_detail_screen.dart:166-172` | invoiceManage, auditView, promiseView, dispute* |
| `features/payments/payments_screen.dart:36-37` | paymentManage, exportData |
| `features/payments/payment_detail_screen.dart:120-126` | paymentManage, auditView, promiseView, dispute* |
| `features/promises/promises_screen.dart:28-30` | promiseManage, promiseOverride, exportData |
| `features/promises/promise_detail_screen.dart:30-32` | promiseManage, promiseOverride, auditView |
| `features/promises/promises_tab.dart:58-59` | promiseManage, promiseOverride |
| `features/disputes/disputes_screen.dart:22` | exportData |
| **`features/disputes/dispute_detail_screen.dart:123`** | **`user.isAdmin`** (role name!) instead of DISPUTE_MANAGE |
| `features/products/products_screen.dart:61-62`, `features/products/product_detail_screen.dart:26-27` | productManage, exportData, auditView |
| `features/documents/document_actions.dart:13-22` | documentView/Manage + record view/manage privilege |
| `features/email/email_actions.dart:18-21` | emailSend / emailView |
| `features/email/email_providers.dart:57-60` | canConnectGmail = staff && emailSend |
| `features/poc/poc_providers.dart:103-121` | canSeePoc (staff && pocView), canAssignPoc (staff && pocAssign), `GET /api/pocs/my-scope` |
| `features/dashboard/dashboard_screen.dart:39-46,197-225` | invoice/payment/promise/customer/poc/dispute view |
| `features/users/users_screen.dart:66-68` | userManage, exportData |
| `features/users/user_detail_screen.dart:26-30` | userManage, auditView && userView, customerView |
| `features/users/roles_screen.dart:55-57`, `features/users/role_detail_screen.dart:23` | roleManage, exportData |

Locked filters from `PageResponse.lockedFilters` show as lock chips with the tooltip **"Your role limits this list to your own records"** (`core/table/data_table_scaffold.dart:774-781`). They are parsed with `TableFilter.parse` (`core/table/table_models.dart:156-159`). The dashboard treats a non-empty `lockedFilters` as "book coverage" (`features/dashboard/dashboard_providers.dart:232`).

### 7.6 Users and roles screens
- **`features/users/users_screen.dart`**
  - Providers: `usersProvider` (`/api/users?size=50`), `rolesProvider` (`/api/roles?size=50`), `userDetailProvider` (18–40).
  - `UsersScreen` is a `DataTableScaffold<AppUser>` with path `/api/users` and ACTIVATE/DEACTIVATE bulk actions (60–138).
  - `UserFormDialog` (140–298):
    - `POST {username,email,fullName,password,roleId,active}` (186–193).
    - `PUT {email,fullName,password?,roleId?,active}` (197–203).
    - A single role dropdown (255–271).
  - This is where a **region-grants editor** (region → role) would go.
- **`features/users/roles_screen.dart`**
  - `allPrivilegesProvider` (`GET /api/privileges`, sorted) (18–23).
  - `RoleFormDialog` is a flat checkbox list of every privilege (207–227), with body `{name, description, privileges:[...]}` (153–157). It has no grouping and no global/regional distinction.
- `features/users/role_detail_screen.dart` and `features/users/user_detail_screen.dart` are read-only detail views; they show `Role` and `Active` in the grid (`user_detail_screen.dart:78-98`).

---

## 8. "Role-at-a-level" (used by the A3 email "To" field)

This is **not** the security `Role`. It is `email/RoleRef(EmailRole role, RoleLevel level)` (`email/RoleRef.java:5-60`).
- `EmailRole` is SALES_POC, CUSTOMER_SUCCESS_POC or COLLECTION_POC, each mapped to a `PocType` (`email/EmailRole.java`).
- `RoleLevel` is CUSTOMER (customer POC seats) or RECORD (the record's own field, such as `invoice.salesPoc`) (`email/RoleLevel.java`).
- Tokens look like `ROLE:<LEVEL>:<ROLE>`.

Holders are resolved in `email/EmailTargets.java:308-326`:
- RECORD uses the record's own field.
- CUSTOMER uses `pocService.activeHoldersByType(customerId)`, ordered `PocTypeAsc, PrimaryDesc, IdAsc` (`poc/CustomerPocRepository.java:15`, `poc/PocService.java:106-115`), and filters to active users.
- "First holder" (`Target.sender`, `EmailTargets.java:157-160`) is therefore the primary if active, otherwise the oldest active seat. That is the "a role held by several people resolves to the primary" rule A4 needs. Reuse `PocService.primaryFor` (88–94) or `activeHolders(...).stream().findFirst()` (96–99).

**Naming collision:** the security roles seeded as `SALES_POC`, `CUSTOMER_SUCCESS_POC` and `COLLECTION_POC` (`DataSeeder.java:29-31`) share names with the `EmailRole` values but are unrelated objects. Automation assignee pickers must use `RoleRef` (POC seats), not `roles`.

---

## 9. Extension points for the PRD

### 9.1 B1: per-region rights ("read in one region, manage in another, nothing in a third")

The current shape cannot express this, because `User.role` is a single global `@ManyToOne` (`User.java:38-40`). A minimal-disruption design that reuses the privilege catalogue and the role editor:

1. **New `region` package**, following the `Privilege` entity style (§2.1):
   - `Region(id, code, name, active, …thresholds)`.
   - `UserRegionGrant(id, user_id, region_id, role_id)` with a unique `(user_id, region_id)`.
   - "Read in R1, manage in R2, nothing in R3" is then VIEWER@R1, CASHIER@R2 and no row for R3.
2. **Split the catalogue into global and regional privileges.** For example: global = USER_*, ROLE_*, PRODUCT_*, NOTIFICATION_VIEW, EXPORT_DATA, plus new REGION_* and RULE_*; regional = CUSTOMER_*, INVOICE_*, PAYMENT_*, PROMISE_*, DISPUTE_*, POC_*, SCOPE_OVERRIDE, DOCUMENT_*, EMAIL_*, AUDIT_VIEW, TASK_*, and the new approval right.
   - Keep `User.role` as the global role, which keeps `countActiveHolders` and the last-admin guards valid as long as USER_MANAGE stays global.
   - Add a `Set<String> REGIONAL` next to `Privileges.ALL`.
3. **Authorities.** Extend `AppUserDetails` with `Map<Long regionId, Set<String>>` built in `AppUserDetailsService.loadUserByUsername` (24–30).
   - The flat authorities should be the **union** (global ∪ all regional), so every existing `@PreAuthorize("hasAuthority(...)")` keeps meaning "holds it somewhere".
   - Load the grants eagerly or with one join query. `OpenEntityManagerInViewConfig` is **disabled** for `/api/emails/**`, `/api/inbox/**`, `/api/me/gmail`, `/api/users/*/gmail` and `/api/mail-service/**` (`config/OpenEntityManagerInViewConfig.java:12-13`), so a LAZY collection on the principal `User` throws `LazyInitializationException` on those paths.
4. **`CurrentUser` additions:** `Set<Long> regionsWith(String privilege)`, `boolean hasIn(String privilege, Long regionId)`, and `void requireIn(String privilege, Long regionId)`.
   - `requireIn` should throw `AccessDeniedException("Not allowed")` for writes. For reads, use `NotFoundException` to preserve AUTH-08.
   - Make `canAssignPoc` region-parameterised.
5. **`ScopeResolver` is the single row-scope choke point.** Add to every `Scope` a region predicate: `region ∈ regionsWith(<entity VIEW privilege>) ∩ requestedRegions`. The requested regions are the PRD's "which regions am I asking about" parameter.
   - Rework the POC book per region: `(region ∈ overrideRegions) OR (region ∈ pocRegions(type) AND bookPredicate)`.
   - `canSeeEverything`, `isAssignableAs` and `canSeePoc` need a `regionId` argument.
   - `forDisputes` (89–97) must stop returning `Scope.empty()` for staff. `Dispute.customerId` is a raw `Long` (`dispute/Dispute.java:25-26`), so it needs either its own region column or a subquery on customers.
   - Everything that already goes through `ScopeResolver` picks this up automatically: `page`, `idsMatching`, `allMatching`, `tiles`, `get`/`inScope`, exports, bulk and the dashboard (`dashboard/DashboardService.java:68,85,115,189-197`).
6. **Region checks on writes.** `@PreAuthorize` is not enough once authorities are a union. Add `requireIn(X_MANAGE, record.region)` in each service write:
   - update / cancel / void / delete, inside `requireInBook` or next to it (`CustomerService.java:254,301`, `InvoiceService.java:108,358`, `PaymentService.java:115`, `PaymentPromiseService.update/cancel/override` via `get`).
   - **The create paths, which today have no customer scope check at all**: `InvoiceService.create` (59–64), `PaymentService.record` (55–61), `PaymentPromiseService.create` (71–74), `CustomerService.create` (205; the new customer's region comes from the request).
   - `DocumentTargets.requireManageable` (54–60).
   - `PocService.add`, `remove` and `setPrimary`.
   - Bulk handlers (they call the same service methods).
   - Email send (`EmailTargets.java:207-212`).
   - Dispute approve and deny.
7. **POC assignability per region.** `UserRepository.findAssignable` (37–52), `hasPrivilege` (54–60) and `PocService.requireAssignable` (54–72) must take `regionId`. The picker (`GET /api/pocs/assignable`) needs a `regionId` (or `customerId`) parameter so it only offers users assignable in that record's region.
8. **Gmail and EMAIL_SEND.** `GmailDisconnects.mayConnect` (62–65) and `RoleController.sendsEmail` (143–145) only look at `user.getRole()`. If EMAIL_SEND becomes regional, they must consider the union of grants, or users will be disconnected or blocked wrongly.
9. **`notifyAdmins`** uses `findByRoleName("ADMIN")` (`notification/NotificationService.java:32-38`). It should become "holders of DISPUTE_MANAGE (or the approval right) in the record's region".
10. **API shape.** Extend `LoginResponse.UserInfo` (`auth/dto/LoginResponse.java:10-17`) and `/me` with `regions: [{id, code, name, privileges:[…]}]`. Keep `privileges` as the union for back-compat with nav gating. Add to `UserDto` (`UserController.java:65-77`). Add endpoints such as `GET/PUT /api/users/{id}/region-grants` (USER_MANAGE) and `/api/regions` (REGION_VIEW / REGION_MANAGE).
11. **Customer logins.** Their effective region is their customer's region; the existing `customerId` short-circuits in `ScopeResolver` already pin them to one customer.
12. **Frontend.**
    - Add `Map<int, Set<String>> regionPrivileges`, `hasIn(p, regionId)` and `regionsWith(p)` to `CurrentUser` (`auth_models.dart`).
    - Nav and route gating stays on the union.
    - Every `canEdit/canManage` site in §7.5 must switch to `hasIn(manage, record.regionId)`. **Better:** have the backend return per-record `permissions`/`canEdit` flags in the DTOs, so the region logic is not duplicated in Dart.
    - Add a region selector (global "asking about" regions) that feeds a query parameter to every list, summary and dashboard provider.
    - Add a region-grants table to `UserFormDialog`.

### 9.2 B2: the approval right (maker-checker)
- Add a constant such as `CHANGE_APPROVE` (a regional privilege) in all four places: `Privileges.java` (constant **and** `ALL`), `frontend/lib/shared/models/privileges.dart`, and the `DataSeeder` role lists.
  - ADMIN gets it automatically through `upsertRole`.
  - A POC role only gets it on the boot where it is created, via `createRoleIfAbsent`.
  - CASHIER and VIEWER get it only if it is added to their lists.
- **Rule at approval time:** `approver.id != maker.id` **and** `currentUser.hasIn(CHANGE_APPROVE, record.regionId)`. Evaluate against the record's region *now*, and decide what happens if the record moved region while the change was pending.
- The closest existing precedent is disputes:
  - A `PENDING` → `APPROVED`/`DENIED` state machine.
  - `proposedChangeJson`, applied on approve (`dispute/DisputeService.java:91-117,219-288`).
  - `mustBePending` under a `findByIdForUpdate` row lock (167–174).
  - Audit with `disputeId`.
  - It has **no** maker ≠ checker check, because the maker is always a customer.
- Per-region thresholds: store them on `Region` or in a `RegionThreshold(region_id, entity/field, amount)` table. Changing a threshold should need a global or regional admin privilege.
- "Pending visible to everyone who can see the record": expose pending changes through the same `ScopeResolver`-scoped `get` of the target, never through an unscoped list.
- Frontend: add an Approvals nav entry gated by `needs([Privileges.changeApprove])`. The approve button should be gated by `hasIn(changeApprove, record.regionId) && pending.makerId != me.id`. Do **not** copy the `isAdmin` pattern from `dispute_detail_screen.dart:123`.

### 9.3 B3: as-of reads
- Rights are evaluated against the caller's **current** grants; only the record's region and pending approvals are read as-of. Decide and document this.
- Nothing in the auth area is temporal. `UserRegionGrant` has no history, and role and privilege edits are not audited (§5.5). If "who could see what as of date D" is ever needed, grants need effective-from/to columns or an audit trail. At minimum, audit grant changes through `auditService.record("USER", …, "USER_REGION_GRANT_CHANGED", …)`.

### 9.4 Part A: security in the automation engine
- **New privileges to add** (same four places): e.g. `TASK_VIEW`, `TASK_MANAGE` (regional); `RULE_VIEW`, `RULE_MANAGE`, `RULE_RUN` (global, or regional if rules are region-scoped). Add nav entries and routes guarded by `needs(navPrivilegesFor('/tasks'))`.
- **Consumer and scheduler threads have no SecurityContext:**
  - `currentUser.require()`, `customerIdOrNull()`, `isCustomer()` and every `ScopeResolver` method throw `BadCredentialsException` there.
  - The existing background job (`promise/PromiseSweepScheduler.java:17-30` → `PaymentPromiseService.sweepOverdue`, 492–503) avoids them.
  - Reusing `PaymentPromiseService.create` (it calls `currentUser.require()` at 94/99), `CustomerService.create/update` or any scoped `get(...)` from a consumer will fail.
  - `DisputeService.open` hard-requires a customer login (53–58), so an automation cannot reuse it to create a dispute.
  - Options: (a) add internal, unscoped service methods that take an explicit `actorUserId` (the `InvoiceService.getInternal` precedent, 275–279), or (b) run the consumer under a synthetic principal built with `new AppUserDetails(user, AppUserDetailsService.buildAuthorities(user))`, as `IntegrationTestBase.actAs` does. Option (b) inherits that user's scope and region limits.
  - In both cases, carry `triggeredByUserId` or `ruleId` on the event, and pass `currentUser.idOrNull()` or `null` to `auditService.record`.
- **"Run from UI":** the controller gates with RULE_RUN, publishes an event, and returns immediately. The consumer must not assume the caller's SecurityContext.
- **Assignees and recipients:** resolve `RoleRef` holders through `PocService` (§8). With B1, also filter holders by whether they hold TASK_VIEW (or the relevant privilege) in the record's region, or tasks will be assigned to people who cannot see them.

---

## 10. Risks and gotchas

1. **Union authorities widen access to writes.** Once authorities are the union across regions, `@PreAuthorize` alone lets a user with INVOICE_MANAGE in R1 edit any invoice they can *see* in R2. Every write must add a region-manage check.
2. **Create paths have no scope check today:** `InvoiceService.create`, `PaymentService.record`, `PaymentPromiseService.create`, `CustomerService.create` (§9.1 item 6). They are the likeliest cross-region leak.
3. **Dispute visibility is unscoped for staff** (`ScopeResolver.forDisputes` returns empty, line 96, and `DisputeService.list` 136–141 returns all). Any regional scoping must be added explicitly.
4. **Unscoped lists:** users, roles and products (`List.of()` scope). Decide whether users should be filtered by region grants.
5. **`lockedFilters` doubles as "book coverage".** `DashboardService.paidAgainstBookOnly` and `coverage` (`dashboard/DashboardService.java:203-217`) and the frontend `upcomingPromisesProvider` (`dashboard_providers.dart:232`) treat any locked filter as "POC book". Putting the region filter into `lockedFilters` flips every user to BOOK coverage and shows the misleading chip text "Your role limits this list to your own records" (`data_table_scaffold.dart:776`).
6. **Role-name-keyed logic** that ignores privileges:
   - Backend: `NotificationService.notifyAdmins` → `findByRoleName("ADMIN")` (34); `UserController.create` refuses `"CUSTOMER"` (134); `CustomerService.create` requires a role literally named `CUSTOMER` (223–224).
   - Frontend: `CurrentUser.isAdmin` (`auth_models.dart:32`), used to resolve disputes (`dispute_detail_screen.dart:123`).
7. **DataSeeder overwrites** ADMIN, CASHIER, VIEWER and CUSTOMER privilege sets on every boot (`upsertRole`, 167–173). **`pickByNames` silently drops unknown names** (205–211). A new privilege must be added to `Privileges.ALL`, or it never exists and no role gets it, with no error.
8. **Frontend privilege constants are a hand-maintained copy** with no `ALL` list, and no mechanism keeps them in sync.
9. **Schema changes:**
   - `hibernate.hbm2ddl.halt_on_error: true` (`application.yml`, under `spring.jpa.properties`) plus `ddl-auto: update` means adding a **NOT NULL** `region_id` to an existing populated table (users, customers, invoices, …) **halts startup**.
   - Declare the column nullable in the entity, and backfill in a `*SchemaUpgrade` `InitializingBean`. The pattern takes `(DataSource, EntityManagerFactory schemaUpToDate)` in the constructor, runs plain JDBC, commits if not autocommit, and logs a warning on failure: `poc/PocSchemaUpgrade.java:14-70`, `common/RowVersionUpgrade.java`.
   - Schema upgrades run **before** `DataSeeder` (a `CommandLineRunner`), so a default region that the backfill references must be inserted by the upgrade itself.
   - Tests use H2 `create-drop`, so they will **not** catch this.
10. **Per-request cost.** `JwtAuthFilter` loads the user on every request (36). Region grants add at least one query; `ScopeResolver` already issues several `hasPrivilege` queries per call. Compute a region-privilege map once in the principal rather than querying repeatedly.
11. **Detached principal.** `AppUserDetails.user` is the entity loaded in the filter, and it is detached on OEMIV-excluded paths (§9.1 item 3). Do not add LAZY associations to `User` that `CurrentUser` callers will touch.
12. **Inconsistent "not allowed" status** for POC reassignment: 400 in `InvoiceController`, `PaymentController`, `PaymentPromiseController` and three services, 403 in `CustomerController` (§5.2). New code should use `AccessDeniedException` (403, D-46) for rights and `NotFoundException` (404) for out-of-scope records (AUTH-08).
13. **The 403 message is always replaced** by the generic "You do not have permission for this action" (`GlobalExceptionHandler.java:57-61`). Custom denial reasons, such as "you cannot approve your own change", need `BadRequestException` or a dedicated handler.
14. **Stale frontend session.** `CurrentUser` privileges are only fetched at login or bootstrap. After an admin edits a user's grants, the UI keeps stale gating until reload, though the backend is correct on the next request.
15. **Some routes lack guards** (`/invoices/new`, detail routes, `/me/gmail`), and the router ignores `hideForCustomer`. New screens should use `needs(...)` and not rely on the nav alone.
16. **User and role creation are unaudited** (UserController create, RoleController create/update/delete). B3 "as of a date" questions about rights cannot be answered from the audit log today.
17. **`hasPrivilege` ignores `active` and `customerId`** (`UserRepository.java:54-60`). Callers add those conditions themselves (`ScopeResolver.java:33-49`, `PocService.java:61-70`). Any region-aware replacement must keep them.
18. **Email "roles" are POC seats, not security roles** (§8). The shared names (`SALES_POC`, …) make it easy to wire automation assignees to the wrong thing.