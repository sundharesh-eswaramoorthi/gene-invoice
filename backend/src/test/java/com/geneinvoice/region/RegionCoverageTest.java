package com.geneinvoice.region;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.geneinvoice.CountingStatements;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.history.HistorySchemas;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gates that make region coverage a property of the build and of starting up rather than a
 * thing somebody audited once.
 *
 * <p>B1's whole claim is that an endpoint written in 2027 is region-bounded without its author
 * having to remember: the compiler will not build a TableSchema that does not name its entity,
 * RegionAxes throws at class initialisation for an entity nobody classified, RegionCoverageCheck
 * refuses to start for an unclassified entity or an unpartitioned privilege, and the predicate
 * lives inside the executor so omitting it is not expressible. Every one of those is a claim about
 * code that does not exist yet, so each is pinned here as a property over the code that does (B1).
 *
 * <p>Two of these walk the SOURCE TREE rather than the running application, deliberately: "only
 * two files build a CriteriaQuery" and "only four call sites leave a caller's grants behind" are
 * statements about what is WRITTEN, and a third hand-rolled reader or a fifth hatch is exactly the
 * change that must not pass unnoticed. They run from the module directory, which is Maven's
 * working directory for a test (B1).
 */
class RegionCoverageTest extends IntegrationTestBase {

    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired TableQueryExecutor queryExecutor;
    @Autowired RegionCoverageCheck bootCheck;

    private static final Path MAIN = Path.of("src", "main", "java");

    /**
     * The tables whose rows belong to a branch, written down HERE and not read from RegionAxes, so
     * that a mis-edit of the axis table fails this test rather than being confirmed by it. Six
     * today: the five business tables the customer anchors, plus the people table, whose rows are
     * reached through their own grants (B1).
     *
     * <p>Seven from A6: tasks hang off a bare customer_id and are VIA_CUSTOMER_ID, so a task list
     * must carry the same restriction a dispute list does (A6, B1).
     *
     * <p>Eight from A1: a step records what the engine did to one record, and it hangs off the
     * same bare customer_id, so the run history is narrowed to the reader's branches by the axis
     * and not by anything AutomationRuleService remembers to add. Its sibling automation_rules is
     * on the OTHER list, and deliberately: a rule belongs to no branch — where it may REACH is the
     * set in automation_rule_regions, applied as a predicate of its own (A1, A5, B1).
     */
    private static final Set<String> REGIONAL_TABLES =
            Set.of("customers", "invoices", "payments", "payment_promises", "disputes", "users",
                    "tasks", "automation_steps",
                    // FOURTEEN FROM B3, and this is the addition that unit exists to force. A
                    // mirror row is a VERSION of a regional record, so a read of one leaks exactly
                    // what a read of the live table would. The axis resolves it as of the date
                    // being answered — RegionScope.clause ANDs RegionPredicates.asOf over the
                    // placement ledger on top of the live clause — but that is a claim about code,
                    // and this is where it becomes a property of the SQL. The five link and line
                    // mirrors are deliberately absent: they are classified NONE for the same
                    // reason their live tables are, and they are never listed on their own
                    // (B1, B3).
                    "customer_history", "invoice_history", "payment_history", "promise_history",
                    "dispute_history", "task_history");

    /** The tables a published list reads that deliberately have no branch at all (B1). */
    private static final Set<String> UNREGIONED_TABLES =
            Set.of("products", "roles", "notifications", "email_recipients", "regions",
                    "automation_rules");

    /** The one restriction shape every axis produces for a caller who works in named branches. */
    private static final String RESTRICTION = "region_id in (";

    /**
     * The complete widening/narrowing call-site map. A new hatch is a new line here, which is the
     * review this enum exists to force: leaving a caller's own grants behind is the one thing in
     * B1 that cannot be justified by the query itself (B1).
     */
    private static final Map<String, Set<String>> HATCH_CALL_SITES = Map.of(
            "region/RegionSchemaUpgrade.java", Set.of("SCHEMA_UPGRADE"),
            "email/mailservice/MailServiceEventHandler.java", Set.of("MAIL_WEBHOOK"),
            "email/EmailDispatcher.java", Set.of("EMAIL_DISPATCH"),
            "promise/PromiseSweepScheduler.java", Set.of("PROMISE_SWEEP"),
            // The automation consumer, and BOTH of its hatches are NARROWING ones: it has no
            // principal, so it bounds every read and every write by the rule's own regions and
            // never by asSystem. An empty reach denies rather than widens (A5, B1).
            "automation/AutomationDispatcher.java", Set.of("AUTOMATION_FANOUT", "AUTOMATION_ACT"),
            "automation/AutomationActions.java", Set.of("AUTOMATION_ACT"),
            // The history sweep reads and repairs the whole company's records on a scheduler
            // thread with no principal, where "nobody" reads as NO regions rather than as every
            // region. Its statements are raw JDBC and consult no region predicate today, so the
            // hatch adds nothing to any answer; it is declared because anything added to that
            // sweep that DID read through a repository would otherwise silently repair nothing
            // (B1, B3).
            "history/HistoryReconciler.java", Set.of("HISTORY_RECONCILE"));

    /**
     * Declared, reviewed and not yet used. DATA_SEED belongs to the seeder nobody has wrapped, and
     * a unit that gives one a call site adds it to HATCH_CALL_SITES in the same commit (B1).
     * A-CONSUMER did exactly that with AUTOMATION_FANOUT and AUTOMATION_ACT, and B3-UPGRADES with
     * HISTORY_RECONCILE, which is why none of the three is here any more (A5, B3).
     */
    private static final Set<String> RESERVED_REASONS = Set.of("DATA_SEED");

    // ---- the boot check -----------------------------------------------------------------------

    @Test
    void everyMappedEntityDeclaresARegionAxis() {
        List<Class<?>> mapped = new ArrayList<>();
        for (EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            mapped.add(entity.getJavaType());
        }

        assertThat(mapped).isNotEmpty();
        for (Class<?> entity : mapped) {
            assertThatCode(() -> RegionCoverageCheck.declaresAnAxis(entity))
                    .describedAs("%s is mapped but has no region axis", entity.getName())
                    .doesNotThrowAnyException();
        }

        // The other direction, which the boot check cannot see: a classification left behind for
        // an entity that has been deleted or was never mapped is a line a reviewer would read as
        // covering something, and it covers nothing (B1).
        assertThat(RegionAxes.classified()).containsExactlyInAnyOrderElementsOf(mapped);
    }

    @Test
    void everyUnregionedEntityGivesAReason() {
        List<Class<?>> unregioned = new ArrayList<>();
        for (EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            if (RegionAxes.of(entity.getJavaType()) == RegionAxis.NONE) unregioned.add(entity.getJavaType());
        }

        assertThat(unregioned).isNotEmpty();
        for (Class<?> entity : unregioned) {
            // A sentence a reviewer reads and can disagree with, not the word "same": NONE on its
            // own is a shrug, and a shrug is how an unregioned list ships by accident (B1).
            assertThat(RegionAxes.reason(entity))
                    .describedAs("%s is unregioned; the reason must be a sentence", entity.getName())
                    .isNotBlank()
                    .hasSizeGreaterThan(20);
        }
        // And an entity WITH an axis says nothing, so reason() is never read as an excuse.
        assertThat(RegionAxes.reason(Invoice.class)).isEmpty();

        assertThatThrownBy(() -> RegionCoverageCheck.requireReason(Invoice.class, RegionAxis.NONE, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(Invoice.class.getName())
                .hasMessageContaining("unregioned with no reason");
    }

    @Test
    void anEntityNobodyClassifiedStopsTheApplicationAndNamesItself() {
        // String stands in for the @Entity a later feature adds and forgets: the boot check walks
        // the whole metamodel, so a new table with no list of its own is still caught (B1).
        assertThatThrownBy(() -> RegionCoverageCheck.declaresAnAxis(String.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("java.lang.String")
                .hasMessageContaining("RegionAxes");
    }

    @Test
    void theBootCheckPassesAgainstTheRunningApplicationAndIsWorthRunningTwice() {
        // The context started, so it passed once. Running it again proves it is a check and not a
        // migration: nothing it does depends on having run first (B1).
        assertThatCode(() -> bootCheck.afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void aHalfFinishedMigrationRefusesToServeTrafficUnlessTheOperatorSaysOtherwise() {
        assertThatThrownBy(() -> RegionCoverageCheck.regionColumnIsTight(true, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("customers.region_id is still nullable")
                .hasMessageContaining("REGION_BOOT_CHECK=warn");

        // warn is the only lever, and it is a lever and not a silence: the ERROR line is what an
        // operator's alert keys on, so it is asserted rather than assumed. The recorder also keeps
        // the line out of the build output, where a genuine ERROR from a passing test is exactly
        // the noise that teaches people to ignore ERROR lines (B1).
        List<String> logged = recorded(() -> RegionCoverageCheck.regionColumnIsTight(true, false));
        assertThat(logged).hasSize(1);
        assertThat(logged.get(0))
                .startsWith("ERROR ")
                .contains("customers.region_id is still nullable");

        assertThat(recorded(() -> RegionCoverageCheck.regionColumnIsTight(false, true))).isEmpty();
    }

    // ---- the classification, the schemas and the partition ------------------------------------

    @Test
    void everyTableSchemaNamesAnEntityWithADeclaredAxis() {
        List<String> entities = TableSchemas.entities();

        assertThat(entities).isNotEmpty();
        for (String entity : entities) {
            TableSchema schema = TableSchemas.byEntity(entity);
            assertThatCode(schema::axis)
                    .describedAs("table schema %s names an unclassified entity", entity)
                    .doesNotThrowAnyException();
            // The pair must also be a real mapped entity, or the executor's root/schema assertion
            // could never fire and the axis would be read off a class Hibernate knows nothing
            // about (B1).
            assertThat(RegionAxes.classified()).contains(schema.entityType());
        }
    }

    @Test
    void everyPrivilegeIsEitherRegionGatedOrCompanyWide() throws Exception {
        for (String privilege : Privileges.ALL) {
            assertThatCode(() -> RegionRights.needed(privilege))
                    .describedAs("%s is in neither half of the partition", privilege)
                    .doesNotThrowAnyException();
        }

        // Both directions, which needed() alone cannot say: a privilege classified here but
        // deleted from the catalogue is a line that covers nothing, and a name in both halves
        // would be read as company-wide by one reader and as region-gated by the next (B1).
        Set<String> gated = levelTable().keySet();
        Set<String> companyWide = companyWide();
        assertThat(gated).doesNotContainAnyElementsOf(companyWide);
        assertThat(new TreeSet<>(Stream.concat(gated.stream(), companyWide.stream()).toList()))
                .isEqualTo(new TreeSet<>(Privileges.ALL));

        assertThatThrownBy(() -> RegionRights.needed("INVENTED_IN_2027"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INVENTED_IN_2027");
    }

    /**
     * The load-bearing claim of the whole design — "a global privilege says WHAT, the region right
     * says WHERE, and all of the @PreAuthorize annotations stay correct unchanged" — turned from
     * structural reasoning into a property. R3 and R4 both recorded that nothing walks them; this
     * walks them, and it is what catches a privilege that is added to an annotation in 2027 and
     * never classified, which would otherwise be exercisable in every region (B1).
     */
    @Test
    void everyPrivilegeNamedByAnEndpointIsARealPrivilegeAndIsClassified() throws Exception {
        Map<String, List<String>> named = new TreeMap<>();
        int guarded = 0;
        List<Class<?>> controllers = restControllers();

        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                PreAuthorize onMethod = method.getAnnotation(PreAuthorize.class);
                PreAuthorize onClass = controller.getAnnotation(PreAuthorize.class);
                String expression = onMethod != null ? onMethod.value()
                        : onClass != null ? onClass.value() : null;
                if (expression == null) continue;
                guarded++;
                for (String privilege : authorities(expression)) {
                    named.computeIfAbsent(privilege, p -> new ArrayList<>())
                            .add(controller.getSimpleName() + "." + method.getName());
                }
            }
        }

        // A scan that silently found nothing would pass every assertion below, so the size of what
        // it walked is asserted first. Today: 115 annotated endpoints across 21 controllers naming
        // 29 distinct privileges — the floors are deliberately below those, because this test is
        // about the annotations being CLASSIFIED and not about their count (B1).
        assertThat(controllers).hasSizeGreaterThanOrEqualTo(20);
        assertThat(guarded).isGreaterThanOrEqualTo(100);
        assertThat(named.keySet()).hasSizeGreaterThanOrEqualTo(25);

        for (Map.Entry<String, List<String>> entry : named.entrySet()) {
            String privilege = entry.getKey();
            assertThat(Privileges.ALL)
                    .describedAs("%s is named by %s but is not a privilege the seeder creates",
                            privilege, entry.getValue())
                    .contains(privilege);
            assertThatCode(() -> RegionRights.needed(privilege))
                    .describedAs("%s guards %s but is in neither half of the region partition",
                            privilege, entry.getValue())
                    .doesNotThrowAnyException();
        }
    }

    // ---- the source-tree gates ------------------------------------------------------------------

    @Test
    void onlyTheQueryExecutorAndTheDashboardBuildCriteriaOverARegionalEntity() throws IOException {
        Set<String> readers = mainSourcesContaining("getCriteriaBuilder()");

        // The dashboard is the one reader that does not go through the funnel, and it pays for
        // that with two explicit region clauses of its own. A THIRD hand-rolled Criteria reader
        // would have to be given the same treatment, so it fails the build until somebody adds it
        // here deliberately (B1).
        //
        // HistoryDrift is the third, added deliberately and with its region treatment written
        // down rather than omitted: it reads one mirror table and returns a BOOLEAN about the
        // TABLE — "has anything in here been repaired rather than watched" — selecting no record
        // and returning no record's data to anybody. There is no row for a region rule to narrow,
        // so a region predicate would change nothing but would make the answer depend on who was
        // asking, and an as-of response would then be exact for one reader and inexact for the
        // next over the same page. Anything added to this file that returns RECORDS must be given
        // the dashboard's treatment instead (B1, B3).
        //
        // AsOfSchemaCheck is the fourth and is a different case again: it BUILDS a criteria path
        // against a mirror root at boot to prove the path resolves, and never executes a query at
        // all. It reads no row, in any region, ever (B1, B3).
        assertThat(readers).containsExactlyInAnyOrder(
                "common/query/TableQueryExecutor.java",
                "dashboard/DashboardService.java",
                "history/HistoryDrift.java",
                "history/AsOfSchemaCheck.java");
    }

    @Test
    void onlyTheDeclaredSystemReasonsWidenTheRegionSet() throws IOException {
        Map<String, Set<String>> found = new LinkedHashMap<>();
        for (String file : mainSourcesContaining("asSystem(")) {
            found.computeIfAbsent(file, f -> new LinkedHashSet<>()).addAll(reasonsNamedIn(file));
        }
        for (String file : mainSourcesContaining("asRegions(")) {
            found.computeIfAbsent(file, f -> new LinkedHashSet<>()).addAll(reasonsNamedIn(file));
        }
        // The hatch's own home declares both and is not a call site.
        found.remove("region/RegionScope.java");

        assertThat(found).isEqualTo(HATCH_CALL_SITES);

        Set<String> used = found.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
        Set<String> declared = Stream.of(RegionScope.SystemReason.values())
                .map(Enum::name).collect(Collectors.toSet());
        // A subset, said out loud: two of the eight constants are still declared for features
        // that have not landed, and a constant with no call site is a promise rather than a leak.
        // The assertion that matters is that nothing widens under a reason nobody declared (B1).
        assertThat(used).isSubsetOf(declared);
        assertThat(used).doesNotContainAnyElementsOf(RESERVED_REASONS);
        assertThat(new TreeSet<>(Stream.concat(used.stream(), RESERVED_REASONS.stream()).toList()))
                .isEqualTo(new TreeSet<>(declared));
    }

    // ---- the tripwire and the costs -------------------------------------------------------------

    /**
     * The funnel, statement by statement. Every published table is driven through the executor as
     * somebody who works in exactly one branch, and the SQL that reached the database is read
     * back: a regional table must carry the restriction and an unregioned one must not. Both
     * directions matter — forgetting the region clause leaks every branch, and forgetting the NONE
     * arm 404s every product (B1).
     */
    @Test
    void noStatementAgainstARegionalTableRunsWithoutARegionRestriction() throws Exception {
        User oneBranch = userRepository.findByUsername("cashier").orElseThrow();
        customer("Home Ltd");
        actAs(oneBranch);

        Set<String> regionalSeen = new TreeSet<>();
        Set<String> unregionedSeen = new TreeSet<>();
        for (String entity : TableSchemas.entities()) {
            TableSchema schema = TableSchemas.byEntity(entity);
            for (String sql : CountingStatements.capture(() -> drive(schema))) {
                if (!sql.startsWith("select")) continue;
                String table = rootTableOf(sql);
                if (REGIONAL_TABLES.contains(table)) {
                    regionalSeen.add(table);
                    assertThat(sql)
                            .describedAs("a read of %s ran with no region restriction: %s", table, sql)
                            .contains(RESTRICTION);
                } else if (UNREGIONED_TABLES.contains(table)) {
                    unregionedSeen.add(table);
                    assertThat(sql)
                            .describedAs("%s is unregioned but was scoped by somebody's rule: %s", table, sql)
                            .doesNotContain(RESTRICTION);
                }
            }
        }

        // THE SAME FUNNEL OVER THE SIX MIRRORS, one as-of read each, driven exactly as the live
        // half above is driven — through the executor, with the interval clause the source
        // switches put at the head of every as-of scope list. Under an open context the axis
        // resolves through customer_region_history instead of through the account's current
        // branch, which is a different predicate reaching a different table, and until this loop
        // existed no test asserted that it is there at all (B1, B3).
        LocalDate asked = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        try (AsOfContext.Handle handle = AsOfContext.open(asked)) {
            List<PredicateFactory> inForce = List.of(AsOf.at(AsOfContext.instant()));
            for (TableSchema schema : HistorySchemas.all()) {
                for (String sql : CountingStatements.capture(() -> drive(schema, inForce))) {
                    if (!sql.startsWith("select")) continue;
                    String table = rootTableOf(sql);
                    if (!REGIONAL_TABLES.contains(table)) continue;
                    regionalSeen.add(table);
                    assertThat(sql)
                            .describedAs("an as-of read of %s ran with no region restriction: %s",
                                    table, sql)
                            .contains(RESTRICTION);
                }
            }
        }

        // Not vacuous: every table the rule speaks about was actually read.
        assertThat(regionalSeen).isEqualTo(new TreeSet<>(REGIONAL_TABLES));
        assertThat(unregionedSeen).isEqualTo(new TreeSet<>(UNREGIONED_TABLES));
    }

    @Test
    void aSchemaUsedOnTheWrongRootIsRejected() {
        actAs(userRepository.findByUsername("admin").orElseThrow());

        // The axis is read off the schema's entity, so a schema paired with somebody else's root
        // would scope itself by the wrong rule — silently, and in whichever direction the wrong
        // classification happened to point (B1).
        assertThatThrownBy(() -> queryExecutor.count(Invoice.class, TableSchemas.CUSTOMERS,
                TableQuery.parseUnpaged(TableSchemas.CUSTOMERS, null, List.of()), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("customers")
                .hasMessageContaining("Invoice");
    }

    @Test
    void aListCostsNoExtraReadOfTheGrantTable() throws Exception {
        User oneBranch = userRepository.findByUsername("cashier").orElseThrow();
        customer("Home Ltd");
        // The principal is built OUTSIDE the counted body, exactly as a real request builds it in
        // the authentication filter: what is being counted is the list itself.
        RequestPostProcessor who = as(oneBranch);

        long reads = CountingStatements.reads("user_region_grants", () ->
                mockMvc.perform(get("/api/invoices").with(who)).andExpect(status().isOk()));

        // The grants ride on the principal and the predicate is built from ids already in memory,
        // so a list never goes back to the grant table. If this ever becomes non-zero, every list
        // in the application has acquired a per-request read of a table that is only ever written
        // by an administrator (B1).
        assertThat(reads).isZero();
    }

    @Test
    void aUserPageCostsAtMostTwoReadsOfTheGrantTable() throws Exception {
        User admin = userRepository.findByUsername("admin").orElseThrow();
        for (int i = 0; i < 10; i++) {
            user("person" + i, "VIEWER");
        }
        RequestPostProcessor who = as(admin);

        long reads = CountingStatements.reads("user_region_grants", () ->
                mockMvc.perform(get("/api/users").with(who)).andExpect(status().isOk()));

        // user_region_grants is an EAGER collection on User because every request reads it through
        // the principal; without @BatchSize(50) the staff directory would be one select per person
        // on every page. Ten people here, and the bound does not move (B1).
        assertThat(reads).isLessThanOrEqualTo(2);
    }

    // ---- fixtures -------------------------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void drive(TableSchema schema) {
        drive(schema, List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void drive(TableSchema schema, List<PredicateFactory> scope) {
        Class<?> type = schema.entityType();
        TableQuery query = TableQuery.parseUnpaged(schema, null, List.of());
        queryExecutor.count((Class) type, schema, query, scope);
        queryExecutor.ids((Class) type, schema, query, scope, TableQueryExecutor.BULK_ID_LIMIT);
        queryExecutor.run((Class) type, schema, query, scope, List.of());
    }

    /** What the check logged while the body ran, as "LEVEL message", and nowhere else. */
    private static List<String> recorded(Runnable body) {
        Logger logger = (Logger) LoggerFactory.getLogger(RegionCoverageCheck.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        boolean additive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            body.run();
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(additive);
        }
        return appender.list.stream()
                .map(event -> event.getLevel() + " " + event.getFormattedMessage())
                .toList();
    }

    /** The first table a statement reads from, which is the one its where clause is about. */
    private static String rootTableOf(String sql) {
        Matcher matcher = Pattern.compile("\\bfrom\\s+([a-z_]+)").matcher(sql);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** Every {@code hasAuthority('X')} literal in a Spring Security expression, in order. */
    private static List<String> authorities(String expression) {
        Matcher matcher = Pattern.compile("hasAuthority\\('([^']+)'\\)").matcher(expression);
        List<String> found = new ArrayList<>();
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }

    private static List<Class<?>> restControllers() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> found = new ArrayList<>();
        // The class files rather than the Spring context: a controller behind an @ConditionalOn…
        // that this profile does not switch on is still a controller somebody has to get right,
        // and the mail-service webhook is exactly one of those (B1).
        for (Resource resource : resolver.getResources("classpath*:com/geneinvoice/**/*.class")) {
            var metadata = readers.getMetadataReader(resource).getAnnotationMetadata();
            if (metadata.hasAnnotation(RestController.class.getName())) {
                found.add(Class.forName(metadata.getClassName()));
            }
        }
        return found;
    }

    /** Paths under src/main/java, module-relative and '/'-separated, whose text holds the needle. */
    private static Set<String> mainSourcesContaining(String needle) throws IOException {
        assertThat(Files.isDirectory(MAIN))
                .describedAs("run from the backend module directory; src/main/java is not here")
                .isTrue();
        Path root = MAIN.resolve("com").resolve("geneinvoice");
        try (Stream<Path> files = Files.walk(root)) {
            Set<String> found = new TreeSet<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains(needle)) {
                    found.add(root.relativize(file).toString().replace('\\', '/'));
                }
            }
            return found;
        }
    }

    private static Set<String> reasonsNamedIn(String file) throws IOException {
        String source = Files.readString(MAIN.resolve("com").resolve("geneinvoice").resolve(file));
        Matcher matcher = Pattern.compile("SystemReason\\.([A-Z_]+)").matcher(source);
        Set<String> found = new LinkedHashSet<>();
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, RegionRight> levelTable() throws Exception {
        Field field = RegionRights.class.getDeclaredField("LEVEL");
        field.setAccessible(true);
        return (Map<String, RegionRight>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> companyWide() throws Exception {
        Field field = RegionRights.class.getDeclaredField("COMPANY_WIDE");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }
}
