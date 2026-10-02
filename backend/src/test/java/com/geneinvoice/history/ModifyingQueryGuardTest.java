package com.geneinvoice.history;

import com.geneinvoice.invoice.InvoiceItem;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * History is captured from Hibernate's entity and collection events, which bulk JPQL and native SQL never raise
 * (§5.7.6). So at runtime nothing writes a versioned table that way unless the same transaction names the rows
 * to {@link HistoryTracker#touch}: the few places that may are listed here, next to the scan, and nothing else
 * may use the tools for it.
 */
class ModifyingQueryGuardTest {

    static final Path MAIN = Path.of("src/main/java");
    static final Path ROOT = MAIN.resolve("com/geneinvoice");

    /**
     * Where SQL that bypasses the entity events is allowed: startup upgraders (they run before the baseline),
     * the schema helpers, history itself, and repositories of tables without history. A runtime path that
     * changes versioned rows with SQL joins this list only together with its {@code HistoryTracker.touch}.
     */
    static final List<Predicate<Path>> ALLOWED = List.of(
            file -> file.getFileName().toString().endsWith("Upgrade.java"),   // every *SchemaUpgrade, RowVersionUpgrade
            // A startup upgrader too: moves an earlier build's automation rows aside, before Hibernate's DDL.
            file -> file.equals(ROOT.resolve("automation/LegacyAutomationRows.java")),
            file -> file.startsWith(ROOT.resolve("common/schema")),
            file -> file.startsWith(ROOT.resolve("history")),
            file -> file.equals(ROOT.resolve("email/connection/GmailConnectionRepository.java")),
            // The region move: CustomerRegionMoveService names every row it moves to HistoryTracker.touch.
            file -> file.equals(ROOT.resolve("region/CustomerRegionMoveRepository.java")),
            // The tasks' part of the move: TaskMoveParticipant names every task it moves to HistoryTracker.touch.
            file -> file.equals(ROOT.resolve("task/TaskMoveParticipant.java")));

    static final List<Pattern> BYPASSES_EVENTS = List.of(
            Pattern.compile("\\bcreateNativeQuery\\s*\\("),
            Pattern.compile("\\bexecuteUpdate\\s*\\("),
            Pattern.compile("JdbcTemplate\\b"),   // NamedParameterJdbcTemplate too
            Pattern.compile("\\bJdbcClient\\b"),
            Pattern.compile("\\b(?:createStatement|prepareStatement|prepareCall)\\s*\\("),
            Pattern.compile("\\bnativeQuery\\s*=\\s*true\\b"));

    /** A JPQL bulk update or delete in a {@code @Query}, whatever repository declares it; group 1 is its entity. */
    static final Pattern BULK_QUERY = Pattern.compile(
            "@Query\\s*\\(\\s*(?:value\\s*=\\s*)?\"(?:\"\")?\\s*(?i:update|delete\\s+from|delete)\\s+(\\w+)");

    static final Pattern MODIFYING = Pattern.compile("@Modifying\\b");
    static final Pattern REPOSITORY_OF =
            Pattern.compile("interface\\s+\\w+\\s+extends\\s+(?:Jpa|Crud|PagingAndSorting|ListCrud)Repository\\s*<\\s*(\\w+)\\s*,");

    /** Entities whose rows live in a version: the versioned types, and invoice lines (inside their invoice's). */
    static Set<String> versionedEntities() {
        Set<String> names = HistoryModel.types().stream().map(t -> t.liveType().getSimpleName())
                .collect(Collectors.toSet());
        names.add(InvoiceItem.class.getSimpleName());
        return names;
    }

    @Test
    void noRepositoryOfAVersionedEntityHasABulkUpdateOrDelete() throws IOException {
        Set<String> versioned = versionedEntities();
        List<String> offences = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = Files.readString(file);
            String entity = repositoryEntity(source);
            if (entity != null && versioned.contains(entity) && MODIFYING.matcher(source).find()) {
                offences.add(MAIN.relativize(file) + " (" + entity + ")");
            }
        }
        assertThat(offences)
                .as("change versioned rows through the entities, or name them to HistoryTracker.touch in the same "
                        + "transaction and allow the file here")
                .isEmpty();
    }

    @Test
    void noQueryAnywhereBulkUpdatesOrDeletesAVersionedEntity() throws IOException {
        Set<String> versioned = versionedEntities();
        List<String> offences = new ArrayList<>();
        for (Path file : javaFiles()) {
            if (ALLOWED.stream().anyMatch(a -> a.test(file))) continue;
            for (String entity : bulkQueryTargets(Files.readString(file))) {
                if (versioned.contains(entity)) offences.add(MAIN.relativize(file) + " (" + entity + ")");
            }
        }
        assertThat(offences)
                .as("a bulk JPQL update or delete of a versioned entity skips history capture, whichever repository "
                        + "declares it")
                .isEmpty();
    }

    @Test
    void nothingElseWritesWithSqlTheEntityEventsNeverSee() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : javaFiles()) {
            if (ALLOWED.stream().anyMatch(a -> a.test(file))) continue;
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (bypasses(lines.get(i))) {
                    offences.add(MAIN.relativize(file) + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        }
        assertThat(offences)
                .as("native SQL and bulk updates skip history capture; use the entities, or HistoryTracker.touch "
                        + "and an entry in ALLOWED")
                .isEmpty();
    }

    @Test
    void theAllowListNamesFilesThatExist() {
        assertThat(ROOT.resolve("common/RowVersionUpgrade.java")).isRegularFile();
        assertThat(ROOT.resolve("common/schema")).isDirectory();
        assertThat(ROOT.resolve("history")).isDirectory();
        assertThat(ROOT.resolve("email/connection/GmailConnectionRepository.java")).isRegularFile();
        assertThat(ROOT.resolve("region/CustomerRegionMoveRepository.java")).isRegularFile();
        assertThat(ROOT.resolve("task/TaskMoveParticipant.java")).isRegularFile();
        assertThat(ALLOWED.get(0).test(ROOT.resolve("common/RowVersionUpgrade.java"))).isTrue();
        assertThat(ALLOWED.get(0).test(ROOT.resolve("region/RegionSchemaUpgrade.java"))).isTrue();
        assertThat(ALLOWED.stream().anyMatch(a -> a.test(ROOT.resolve("invoice/InvoiceService.java")))).isFalse();
    }

    @Test
    void theGuardRecognisesEachWayOfBypassingTheEvents() {
        assertThat(List.of(
                "em.createNativeQuery(\"update invoices set notes = null\").executeUpdate();",
                "int n = query.executeUpdate();",
                "private final JdbcTemplate jdbc;",
                "private final NamedParameterJdbcTemplate named;",
                "JdbcClient.create(dataSource).sql(\"update invoices set notes = null\").update();",
                "try (Statement st = connection.createStatement()) {",
                "connection.prepareStatement(\"delete from invoices\").execute();",
                "@Query(value = \"delete from customer_pocs where id = ?1\", nativeQuery = true)",
                "@Query(value = \"...\", nativeQuery=true)"))
                .allSatisfy(line -> assertThat(bypasses(line)).as(line).isTrue());
        assertThat(List.of(
                "em.createQuery(\"select i from Invoice i\", Invoice.class)",
                "repository.save(invoice);",
                "@Query(\"select p from Payment p where p.id = :id\")",
                "boolean nativeQueryFlag = false;"))
                .allSatisfy(line -> assertThat(bypasses(line)).as(line).isFalse());

        assertThat(bulkQueryTargets("""
                @Modifying
                @Query("update Invoice i set i.notes = null")
                int a();
                @Query(value = "delete from Payment p where p.id = :id")
                int b();
                @Query(\"""
                        update Document d
                           set d.deleted = true\""")
                int c();
                @Query("DELETE Dispute d")
                int d();
                @Query("select i from Invoice i where i.notes = 'update Customer'")
                List<Invoice> e();
                """)).containsExactly("Invoice", "Payment", "Document", "Dispute");

        assertThat(repositoryEntity("public interface InvoiceRepository extends JpaRepository<Invoice, Long> {"))
                .isEqualTo("Invoice");
        assertThat(repositoryEntity("interface CustomerPocRepository extends JpaRepository<CustomerPoc, Long>,"))
                .isEqualTo("CustomerPoc");
        assertThat(versionedEntities()).contains("Customer", "Invoice", "InvoiceItem", "Payment", "PaymentAllocation",
                "PaymentPromise", "Dispute", "CustomerPoc", "Product", "Task");
    }

    static boolean bypasses(String line) {
        return BYPASSES_EVENTS.stream().anyMatch(p -> p.matcher(line).find());
    }

    static List<String> bulkQueryTargets(String source) {
        List<String> targets = new ArrayList<>();
        Matcher m = BULK_QUERY.matcher(source);
        while (m.find()) targets.add(m.group(1));
        return targets;
    }

    static String repositoryEntity(String source) {
        Matcher m = REPOSITORY_OF.matcher(source);
        return m.find() ? m.group(1) : null;
    }

    static List<Path> javaFiles() throws IOException {
        assertThat(MAIN).isDirectory();
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }
}
