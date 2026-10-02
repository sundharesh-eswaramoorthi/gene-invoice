package com.geneinvoice.history;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query code reaches a versioned record only through {@link Temporal} (§5.7.14): a {@code from(Invoice.class)}
 * or a {@code join("customer")} written by hand reads today's table even while a past date is being viewed, and
 * the list would show today's figures as history without any error. Plain scan of the main sources.
 */
class TemporalConventionTest {

    static final Path MAIN = Path.of("src/main/java");
    static final Path ROOT = MAIN.resolve("com/geneinvoice");

    /** Where query code lives: the query engine, scope, the dashboard, tasks, assignees, and every service. */
    static final List<Path> SCANNED_DIRS = Stream.of("common/query", "poc", "dashboard", "task", "assignee")
            .map(ROOT::resolve).toList();

    /** Files whose as-of reads land in a later slice, with that slice; each must still need its entry. */
    static final Map<String, String> PENDING = Map.of();

    static final Pattern FROM_CLASS = Pattern.compile("\\.from\\(\\s*([\\w.]+)\\.class\\s*\\)");
    static final Pattern JOIN_BY_NAME = Pattern.compile("\\.join\\(\\s*\"(\\w+)\"");

    static Set<String> versionedNames() {
        return HistoryModel.types().stream().map(t -> t.liveType().getSimpleName()).collect(Collectors.toSet());
    }

    /** The associations of versioned records that lead to another versioned record: what history follows. */
    static Set<String> versionedAssociations() {
        Set<Class<?>> versioned = HistoryModel.types().stream().map(TypeHistory::liveType)
                .collect(Collectors.toSet());
        Set<String> names = new TreeSet<>();
        for (Class<?> type : versioned) {
            for (Field f : type.getDeclaredFields()) {
                Type target = f.getGenericType() instanceof ParameterizedType p
                        ? p.getActualTypeArguments()[0] : f.getType();
                if (target instanceof Class<?> c && versioned.contains(c)) names.add(f.getName());
            }
        }
        return names;
    }

    static List<String> offences(String source) {
        Set<String> versioned = versionedNames();
        Set<String> associations = versionedAssociations();
        List<String> found = new ArrayList<>();
        Matcher from = FROM_CLASS.matcher(source);
        while (from.find()) {
            String name = from.group(1).substring(from.group(1).lastIndexOf('.') + 1);
            if (versioned.contains(name)) found.add(from.group());
        }
        Matcher join = JOIN_BY_NAME.matcher(source);
        while (join.find()) {
            if (associations.contains(join.group(1))) found.add(join.group());
        }
        return found;
    }

    static List<Path> scannedFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> all = Files.walk(ROOT)) {
            all.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.startsWith(ROOT.resolve("history")))
                    .filter(f -> SCANNED_DIRS.stream().anyMatch(f::startsWith)
                            || f.getFileName().toString().endsWith("Service.java"))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }

    @Test
    void queryCodeReachesVersionedRecordsOnlyThroughTemporal() throws IOException {
        List<String> offences = new ArrayList<>();
        List<String> stillPending = new ArrayList<>();
        for (Path file : scannedFiles()) {
            String name = ROOT.relativize(file).toString().replace('\\', '/');
            List<String> found = offences(Files.readString(file));
            if (PENDING.containsKey(name)) {
                if (!found.isEmpty()) stillPending.add(name);
            } else {
                found.forEach(o -> offences.add(name + ": " + o));
            }
        }
        assertThat(offences)
                .as("use Temporal.from / Temporal.subquery / Temporal.join, so a past date reads versions")
                .isEmpty();
        assertThat(stillPending).as("a pending file that no longer needs its entry must leave PENDING " + PENDING)
                .containsExactlyInAnyOrderElementsOf(PENDING.keySet());
    }

    @Test
    void theScanCatchesWhatItIsFor() {
        assertThat(versionedAssociations()).contains("customer", "invoice", "payment", "invoices", "payments",
                "allocations").doesNotContain("region", "salesPoc", "user", "items");
        assertThat(offences("Root<Invoice> inv = sq.from(Invoice.class);")).hasSize(1);
        assertThat(offences("var inv = sq.from(com.geneinvoice.invoice.Invoice.class);")).hasSize(1);
        assertThat(offences("Join<?, ?> c = inv.join(\"customer\");")).hasSize(1);
        assertThat(offences("promise.join( \"invoices\", JoinType.LEFT)")).hasSize(1);
        assertThat(offences("Root<?> r = Temporal.from(q, Invoice.class);")).isEmpty();
        assertThat(offences("Root<EmailRecipient> r = cq.from(EmailRecipient.class);")).isEmpty();
        assertThat(offences("row.join(\"email\")")).isEmpty();
        assertThat(offences("root.join(\"region\", JoinType.LEFT)")).isEmpty();
    }
}
