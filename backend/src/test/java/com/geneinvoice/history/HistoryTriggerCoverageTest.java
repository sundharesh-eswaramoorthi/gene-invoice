package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * MECHANISM 4 OF B3'S COVERAGE ARGUMENT, MADE INTO A BUILD FAILURE: the mirror write trigger is
 * complete by construction, and nothing may quietly make it incomplete again (B3).
 *
 * <p>Every mirror row in this application is written by a Hibernate {@code PostInsert} /
 * {@code PostUpdate} / {@code PostDelete} listener plus the three collection listeners, drained at
 * before-commit. That covers every write Hibernate MEDIATES — which is every write in the
 * application except two kinds. The raw-JDBC {@code *SchemaUpgrade} beans are one, and they run at
 * startup before any request and are swept by {@link HistoryReconciler}. The
 * {@code @Modifying @Query} bulk statements are the other, and they are the dangerous kind: they
 * are ordinary application code, they run inside ordinary request transactions, and Hibernate
 * raises no event for a single row of them. A bulk update of a mirrored table would leave the
 * mirror silently stale until the reconciler noticed — up to fifteen minutes of as-of answers that
 * are wrong and say they are exact.
 *
 * <p>So the claim this test pins is not "the listener is correct" — {@code HistoryWriteTest} owns
 * that — but "there is nothing for the listener to have missed". It reflects over every
 * {@code @Modifying} method in every Spring Data repository on the classpath, resolves the TABLE
 * each statement targets, and asserts that table is not mirrored, or else is named in
 * {@link #COMPENSATED} together with the code that writes the mirror row by hand.
 *
 * <p>TWO ASSERTIONS AND NOT ONE, BECAUSE THE RULE ALONE WOULD GO QUIET. The rule ("non-mirrored or
 * compensated") passes for ever without anybody looking, because almost nothing anybody writes
 * targets a mirrored table. So the INVENTORY is asserted too: a sixteenth bulk statement is a red
 * build whoever writes it, and the author clears it by adding one line here — which is the moment
 * somebody reads the query and asks whether the mirror needs to know. That is the review this test
 * exists to force, and it is the reason the design says "a tenth written in six months fails the
 * build until someone decides".
 *
 * <p>A PREMISE THE DESIGN HANDED THIS UNIT IS NO LONGER TRUE AND THE NUMBER BELOW IS THE REAL ONE.
 * B3's coverage argument counts NINE {@code @Modifying} statements and names them one by one; that
 * count was taken before Part A and B2 landed, and there are FIFTEEN today — the six automation
 * ones are new. The conclusion is unchanged and is now measured rather than quoted: not one of the
 * fifteen targets a mirrored table.
 */
class HistoryTriggerCoverageTest extends IntegrationTestBase {

    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired HistoryRegistry registry;

    /**
     * Every bulk statement in the application, and the table it writes. Hand-kept on purpose: this
     * is the list a reviewer reads, and a statement that appears here without anybody having
     * thought about the mirror is the failure the whole mechanism is guarding against (B3).
     *
     * <p>Fifteen, across nine repositories, and every one of them targets a table outside the
     * temporal boundary — the outbox and its steps, the rule scheduler's own claim and the undo
     * that hands a claimed slot back, a person, a notification, a document, a mailbox and a mail
     * row. None of the nine mirrored business tables appears, which is what makes the listener
     * complete (A5, A1, B3).
     */
    private static final Map<String, String> BULK_STATEMENTS = Map.ofEntries(
            // The automation engine's own claim-and-settle machinery (A5).
            Map.entry("AutomationEventRepository#claim", "automation_events"),
            Map.entry("AutomationEventRepository#settle", "automation_events"),
            Map.entry("AutomationStepRepository#claim", "automation_steps"),
            Map.entry("AutomationStepRepository#settle", "automation_steps"),
            Map.entry("AutomationRuleRepository#claimSchedule", "automation_rules"),
            // The other half of that claim: handing a slot back when the run never opened. Same
            // table, same answer — automation_rules is outside the temporal boundary, so the
            // mirror has nothing to hear about it (A5, B3).
            Map.entry("AutomationRuleRepository#releaseSchedule", "automation_rules"),
            // The mail path: claiming a send, and marking a mail about a record that has gone.
            Map.entry("EmailRepository#claim", "emails"),
            Map.entry("EmailRepository#markEntityDeleted", "emails"),
            Map.entry("EmailRecipientRepository#markAllRead", "email_recipients"),
            Map.entry("EmailRecipientRepository#markRead", "email_recipients"),
            Map.entry("EmailRecipientRepository#markUnread", "email_recipients"),
            Map.entry("GmailConnectionRepository#insertBlank", "gmail_connections"),
            // The three oldest, all pre-B1.
            Map.entry("NotificationRepository#markAllRead", "notifications"),
            Map.entry("DocumentRepository#softDeleteForCustomer", "documents"),
            Map.entry("UserRepository#clearBlankEmails", "users"));

    /**
     * A bulk statement that DOES write a mirrored table, and where the mirror row is written by
     * hand to make up for the event Hibernate did not raise.
     *
     * <p>EMPTY, AND THAT IS THE FINDING RATHER THAN AN OMISSION. Nothing in this application
     * bulk-updates a mirrored table, so the mirror writer has nothing to compensate for. An entry
     * here is a promise that somebody wrote the interval row themselves — closing the open one and
     * opening the next — and it is a promise this test can only record, not verify, which is why it
     * has to name the code that keeps it (B3).
     */
    private static final Map<String, String> COMPENSATED = Map.of();

    private static final Pattern TARGET = Pattern.compile(
            "^\\s*(?:insert\\s+into|update|delete\\s+from)\\s+([\\w.$]+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * The gate. Every bulk statement is the one it was, and every one of them writes a table the
     * mirror does not have to know about (B3).
     *
     * <p>The target is resolved to a TABLE and not to an entity name, so the JPQL statements and
     * the one native insert are judged by the same rule and against the same set — and a target
     * that resolves to neither a mapped entity nor a known table FAILS rather than being skipped,
     * because a statement this test could not read is a statement it cannot vouch for.
     */
    @Test
    void everyModifyingJpqlStatementIsDeclaredNonTemporalOrCompensated() throws Exception {
        Map<String, String> tables = tablesByEntityName();
        Set<String> known = knownTables(tables);
        Set<String> mirrored = registry.all().stream()
                .map(HistoryBinding::liveTable).collect(Collectors.toCollection(TreeSet::new));

        Map<String, String> found = new LinkedHashMap<>();
        for (Class<?> repository : repositories()) {
            for (Method method : repository.getDeclaredMethods()) {
                if (method.getAnnotation(Modifying.class) == null) continue;
                String where = repository.getSimpleName() + "#" + method.getName();
                Query query = method.getAnnotation(Query.class);
                if (query == null || query.value().isBlank()) {
                    // A derived @Modifying (deleteByX) writes whatever the repository's domain type
                    // is, and there is none today. It is a failure rather than a fallback because
                    // guessing which table it hits is exactly the reasoning this test replaces.
                    fail("%s is @Modifying with no @Query; name the table it writes here (B3)", where);
                }
                found.put(where, tableOf(where, query.value(), tables, known));
            }
        }

        // 1. THE RULE, FIRST, because it is the claim the write path's completeness rests on and
        // it is the message the author of a dangerous statement needs to read. The inventory below
        // would otherwise fail first and say only "this is new".
        for (Map.Entry<String, String> statement : found.entrySet()) {
            if (!mirrored.contains(statement.getValue())) continue;
            assertThat(COMPENSATED)
                    .describedAs("%s bulk-writes the mirrored table %s, which raises no Hibernate "
                            + "event and so writes no mirror row. Either stop bulk-updating it or "
                            + "name the code that writes the interval row by hand (B3)",
                            statement.getKey(), statement.getValue())
                    .containsKey(statement.getKey());
        }

        // 2. THE INVENTORY. A sixteenth statement is a red build until somebody writes it down,
        // even a harmless one — that moment is the review this whole mechanism exists to force.
        assertThat(new TreeMap<>(found))
                .describedAs("every @Modifying statement, and the table it writes. A new one is "
                        + "added here, and adding it is when somebody decides whether the mirror "
                        + "needs to know (B3)")
                .isEqualTo(new TreeMap<>(BULK_STATEMENTS));

        // Not vacuous: reflection really did read the repositories, and it found all of them.
        assertThat(found).hasSize(15);

        // 3. NO STALE PROMISES. A compensation for a statement that no longer exists is a claim
        // nobody is keeping, and it would hide the next one that needs it.
        assertThat(found.keySet()).containsAll(COMPENSATED.keySet());

        // 4. The gate is measured against a real temporal boundary and not an empty set.
        assertThat(mirrored).hasSize(11).contains("invoices", "customers", "payment_promises");
    }

    // ---------------------------------------------------------------- resolution

    /** The table a statement writes, or a failure naming the statement we could not read. */
    private String tableOf(String where, String sql, Map<String, String> tables, Set<String> known) {
        String flat = String.join(" ", sql.trim().split("\\s+"));
        Matcher matcher = TARGET.matcher(flat);
        if (!matcher.find()) {
            fail("%s: could not read which table this statement writes: %s (B3)", where, flat);
        }
        String raw = matcher.group(1);
        String simple = raw.substring(raw.lastIndexOf('.') + 1);
        String mapped = tables.get(simple.toLowerCase(Locale.ROOT));
        if (mapped != null) return mapped;
        if (known.contains(simple.toLowerCase(Locale.ROOT))) return simple.toLowerCase(Locale.ROOT);
        fail("%s targets \"%s\", which is neither a mapped entity nor a table this application "
                + "knows about. Classify it before shipping it (B3)", where, raw);
        return null;
    }

    /** Lower-cased entity name -> its table, read off the running metamodel rather than a list. */
    private Map<String, String> tablesByEntityName() {
        Map<String, String> out = new TreeMap<>();
        for (EntityType<?> type : entityManagerFactory.getMetamodel().getEntities()) {
            Class<?> java = type.getJavaType();
            Table table = java.getAnnotation(Table.class);
            String name = table == null || table.name().isBlank()
                    ? java.getSimpleName().toLowerCase(Locale.ROOT) : table.name();
            out.put(java.getSimpleName().toLowerCase(Locale.ROOT), name);
            out.put(type.getName().toLowerCase(Locale.ROOT), name);
        }
        return out;
    }

    /**
     * Every table a native statement is allowed to name: the mapped ones, plus the join tables that
     * have no entity of their own — which includes the two promise link tables the mirrors DO cover
     * and that a native statement could therefore reach behind Hibernate's back (B3).
     */
    private Set<String> knownTables(Map<String, String> tables) {
        Set<String> known = new TreeSet<>(tables.values());
        registry.all().forEach(binding -> known.add(binding.liveTable()));
        return known;
    }

    /**
     * Every Spring Data repository on the classpath, found by reading class files rather than by
     * asking the context: a repository behind a {@code @ConditionalOn…} this profile does not switch
     * on is still code somebody has to get right (B1's idiom, reused).
     */
    private static List<Class<?>> repositories() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> found = new ArrayList<>();
        for (Resource resource : resolver.getResources("classpath*:com/geneinvoice/**/*.class")) {
            var metadata = readers.getMetadataReader(resource).getClassMetadata();
            if (!metadata.isInterface()) continue;
            Class<?> type = Class.forName(metadata.getClassName());
            if (Repository.class.isAssignableFrom(type)) found.add(type);
        }
        found.sort(Comparator.comparing(Class::getName));
        return found;
    }
}
