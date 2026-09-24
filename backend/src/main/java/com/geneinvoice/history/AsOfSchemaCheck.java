package com.geneinvoice.history;

import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.SingularAttribute;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The halt_on_error philosophy, applied to the temporal boundary: the application refuses to start
 * when what it will answer "as of a date" is not the same thing it answers about today (B3).
 *
 * <p>Nothing here repairs anything. Upgrades run first and never throw; checks run last and do —
 * and this one names the entity and the column, at boot, while somebody is watching. The failure
 * it exists for is silent by nature: a field added to {@code Invoice} next quarter with no mirror
 * column does not break a single test, it just quietly stops being part of the past, and the first
 * person to notice is someone reading a number off an as-of report a year later.
 *
 * <p>THREE CHECKS.
 * <ol>
 *   <li>For each (live, as-of) {@link TableSchema} pair the column-NAME sets are equal, and each
 *       pair of {@link ColumnDef}s agrees on type, sortable, filterable, enumValues, referenceKind
 *       and pocRestricted. {@code asOfMode} is the one field allowed to differ, because saying "this
 *       label is today's" is the whole point of having it.</li>
 *   <li>Every as-of {@code ColumnDef} RESOLVES against the mirror's root — actually resolved
 *       through Hibernate's criteria metamodel, not compared against a list of names, because a
 *       path is a string and a misspelling is not a compile error.</li>
 *   <li>Every persisted attribute and every physical column of a mirrored live entity exists on
 *       the mirror, unless the binding names it in {@code notMirrored}.</li>
 * </ol>
 *
 * <p>AT THIS WAVE (1) AND (2) ARE VACUOUS AND SAY SO IN THE LOG: no as-of {@link TableSchema}
 * exists until B3-SCHEMAS writes the twins, so {@link #pairs} finds none. They are written and
 * tested anyway, against hand-built schemas, so the unit that adds the twins inherits a check
 * rather than a promise — and {@link #pairs} needs no edit when it does, because it recognises a
 * twin by the one thing that cannot be got wrong: its schema names a MIRROR class as its entity.
 *
 * <p>DEVIATION, STATED: B3's design puts this class in {@code common/asof}. It has to read
 * {@link HistoryRegistry}, and {@code common.asof} is deliberately a leaf that both {@code region}
 * and {@code common.query} depend on, so putting it there would make a package cycle. It lives in
 * {@code history} and imports downward, which is the direction the blueprint fixes (B3).
 */
@Component
@DependsOn({"historySeedUpgrade", "historyEnumUpgrade", "regionCoverageCheck"})
@Slf4j
class AsOfSchemaCheck implements InitializingBean {

    // The metamodel is read from it, and it is also what orders this bean after Hibernate's schema
    // export — the InvoiceSchemaUpgrade:34 idiom, one constructor parameter doing both jobs (B3).
    private final EntityManagerFactory entityManagerFactory;
    private final DataSource dataSource;
    private final HistoryRegistry registry;

    AsOfSchemaCheck(EntityManagerFactory entityManagerFactory, DataSource dataSource,
                    HistoryRegistry registry) {
        this.entityManagerFactory = entityManagerFactory;
        this.dataSource = dataSource;
        this.registry = registry;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        Metamodel metamodel = entityManagerFactory.getMetamodel();
        try (Connection connection = dataSource.getConnection()) {
            for (HistoryBinding binding : registry.all()) {
                everyAttributeIsMirroredOrDeclared(binding, columnsOf(connection, binding.liveTable()),
                        columnsOf(connection, binding.mirrorTable()), metamodel, registry);
            }
        }

        List<Pair> pairs = pairs(schemas(), registry);
        CriteriaBuilder cb = entityManagerFactory.getCriteriaBuilder();
        for (Pair pair : pairs) {
            columnSetsAgree(pair.live(), pair.asOf());
            for (ColumnDef column : pair.asOf().columns()) {
                mirrorHasAttribute(pair.asOf(), column, cb);
            }
        }
        log.info("As-of boundary checked: {} mirrors complete, {} as-of table schema pair(s)",
                registry.all().size(), pairs.size());
    }

    // ---- (c) the mirror is complete ---------------------------------------------------------------

    /**
     * Lifted from {@code HistoryRegistryTest} rather than written a second time, because a check
     * that a test already proves is a check somebody has already argued with (B3).
     *
     * <p>Both halves matter and neither implies the other. The COLUMN half reads the live table
     * from {@link DatabaseMetaData}, so a column that exists on the database but is mapped by
     * nothing is still caught — which is exactly what a half-finished migration looks like. The
     * ATTRIBUTE half reads the JPA metamodel, so a mapped field whose column is spelled in a way
     * this check would not guess is caught by name and type instead. A to-one association is not
     * required to be an association on the mirror: a mirrored target is a flat Long there ON
     * PURPOSE, and the column half has already proved its foreign key is present.
     */
    static void everyAttributeIsMirroredOrDeclared(HistoryBinding binding, Set<String> liveColumns,
                                                   Set<String> mirrorColumns, Metamodel metamodel,
                                                   HistoryRegistry registry) {
        if (liveColumns.isEmpty() || mirrorColumns.isEmpty()) {
            throw new IllegalStateException(binding.mirrorTable() + " or " + binding.liveTable()
                    + " has no columns at all; the history schema did not finish (B3)");
        }
        for (String declared : binding.mirrorColumns()) {
            if (!mirrorColumns.contains(declared)) {
                throw new IllegalStateException(binding.mirrorTable() + " declares the mirror column "
                        + declared + ", which the table does not have (B3)");
            }
        }
        Set<String> excused = binding.notMirrored().stream()
                .map(HistoryEnumUpgrade::snake)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String column : liveColumns) {
            // The record's own id is mapped to businessIdColumn and is asserted by the loop above.
            if (column.equals("id") || excused.contains(column)) continue;
            if (!mirrorColumns.contains(column)) {
                throw new IllegalStateException(binding.liveTable() + "." + column + " has no column"
                        + " on " + binding.mirrorTable() + ", so it silently stopped being part of"
                        + " the past. Mirror it, or name it in this binding's notMirrored with a"
                        + " written reason (B3)");
            }
        }

        if (isLink(binding, registry)) return;   // a join table has no entity to read attributes off
        EntityType<?> live = metamodel.entity(binding.entityClass());
        Map<String, Class<?>> mirror = singularTypes(metamodel.entity(binding.mirrorClass()));
        for (SingularAttribute<?, ?> attribute : live.getSingularAttributes()) {
            String name = attribute.getName();
            if (binding.notMirrored().contains(name)) continue;
            if (attribute.isAssociation()) {
                Class<?> onMirror = mirror.get(name);
                if (onMirror != null && !onMirror.equals(attribute.getJavaType())) {
                    throw new IllegalStateException(binding.mirrorClass().getSimpleName() + "."
                            + name + " points at " + onMirror.getName() + " and the live entity"
                            + " points at " + attribute.getJavaType().getName() + " (B3)");
                }
                continue;
            }
            if (!mirror.containsKey(name)) {
                throw new IllegalStateException(binding.mirrorClass().getSimpleName()
                        + " does not declare " + name + ", which "
                        + binding.entityClass().getSimpleName() + " maps as a plain column."
                        + " Mirror it under the SAME attribute name, or name it in this binding's"
                        + " notMirrored with a written reason (B3)");
            }
            if (!boxed(mirror.get(name)).equals(boxed(attribute.getJavaType()))) {
                throw new IllegalStateException(binding.mirrorClass().getSimpleName() + "." + name
                        + " is a " + mirror.get(name).getName() + " and the live attribute is a "
                        + attribute.getJavaType().getName() + "; every ColumnDef and every scope"
                        + " predicate has to resolve identically on both roots (B3)");
            }
        }
    }

    // ---- (a) the two schemas describe the same table -----------------------------------------------

    /**
     * An as-of list that offered one column fewer, or the same column with a different type, would
     * be a second product wearing the first one's name: the filter dialog, the CSV header and
     * every saved view are built from this metadata (B3).
     */
    static void columnSetsAgree(TableSchema live, TableSchema asOf) {
        Set<String> liveNames = new TreeSet<>(live.byName().keySet());
        Set<String> asOfNames = new TreeSet<>(asOf.byName().keySet());
        if (!liveNames.equals(asOfNames)) {
            Set<String> missing = new TreeSet<>(liveNames);
            missing.removeAll(asOfNames);
            Set<String> extra = new TreeSet<>(asOfNames);
            extra.removeAll(liveNames);
            throw new IllegalStateException("The as-of table schema " + asOf.entity() + " does not"
                    + " offer the same columns as " + live.entity() + ": missing " + missing
                    + ", unexpected " + extra + " (B3)");
        }
        for (String name : liveNames) {
            ColumnDef a = live.byName().get(name);
            ColumnDef b = asOf.byName().get(name);
            // asOfMode is the ONE field allowed to differ: a denormalised label that renders
            // today's value says so there, and saying so is the point of the field (B3).
            requireSame(asOf, name, "type", a.type(), b.type());
            requireSame(asOf, name, "sortable", a.sortable(), b.sortable());
            requireSame(asOf, name, "filterable", a.filterable(), b.filterable());
            requireSame(asOf, name, "enumValues", a.enumValues(), b.enumValues());
            requireSame(asOf, name, "referenceKind", a.referenceKind(), b.referenceKind());
            requireSame(asOf, name, "pocRestricted", a.pocRestricted(), b.pocRestricted());
        }
    }

    private static void requireSame(TableSchema asOf, String column, String field, Object live,
                                    Object mirror) {
        if (!Objects.equals(live, mirror)) {
            throw new IllegalStateException(asOf.entity() + "." + column + " declares " + field
                    + " = " + mirror + " as of a date and " + live + " today (B3)");
        }
    }

    // ---- (b) every as-of column resolves on the mirror ----------------------------------------------

    /**
     * RESOLVED, not compared. The path is a lambda over attribute NAMES, so nothing about it is
     * checked at compile time and a rename on the mirror shows up as a 500 on the first as-of
     * request. Building a throwaway query against the mirror root makes Hibernate answer the
     * question now instead (B3).
     */
    static void mirrorHasAttribute(TableSchema asOf, ColumnDef column, CriteriaBuilder cb) {
        CriteriaQuery<Object> query = cb.createQuery();
        Root<?> root = query.from(asOf.entityType());
        try {
            column.path().resolve(root, query, cb);
        } catch (RuntimeException e) {
            throw new IllegalStateException(asOf.entity() + "." + column.name() + " does not"
                    + " resolve against " + asOf.entityType().getSimpleName() + ": " + e.getMessage()
                    + " (B3)", e);
        }
    }

    // ---- the pairs ------------------------------------------------------------------------------------

    /** One live table and the as-of twin of it. */
    record Pair(TableSchema live, TableSchema asOf) {
    }

    /**
     * An as-of twin is recognised by the only thing about it that cannot be a matter of taste: its
     * {@code entityType} is a MIRROR class. So this needs no naming convention, no registration
     * list and no edit when B3-SCHEMAS lands — the twins simply start being checked (B3).
     */
    static List<Pair> pairs(List<TableSchema> schemas, HistoryRegistry registry) {
        List<Pair> pairs = new ArrayList<>();
        for (TableSchema schema : schemas) {
            HistoryBinding binding = bindingOfMirror(schema.entityType(), registry);
            if (binding == null) continue;
            TableSchema live = schemas.stream()
                    .filter(s -> s.entityType() == binding.entityClass())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("The as-of table schema "
                            + schema.entity() + " reads " + binding.mirrorTable() + " but there is"
                            + " no live table schema over " + binding.entityClass().getSimpleName()
                            + " to compare it with (B3)"));
            pairs.add(new Pair(live, schema));
        }
        return List.copyOf(pairs);
    }

    @SuppressWarnings("unchecked")
    private static HistoryBinding bindingOfMirror(Class<?> type, HistoryRegistry registry) {
        if (!HistoryRow.class.isAssignableFrom(type)) return null;
        return registry.forMirror((Class<? extends HistoryRow>) type);
    }

    /**
     * The live register PLUS the six twins, and the twins have to be added here by name (B3).
     *
     * <p>B3-UPGRADES left a note saying this bean would need no edit when the twins landed,
     * because {@link #pairs} recognises one by its entityType. That half is true and is untouched.
     * The half that was not: {@code TableSchemas.entities()} is the REGISTERED tables, and a twin
     * is deliberately not registered — it keeps its live entity() string ("invoices"), so
     * registering it would replace the live schema under that key and every ordinary list in the
     * application would start reading a mirror. So the twins are handed to the check directly and
     * the boot gate covers them; without this line it would keep reporting "0 as-of table schema
     * pair(s)" with six of them sitting in the class next door (B3).
     */
    static List<TableSchema> schemas() {
        List<TableSchema> all = new ArrayList<>(
                TableSchemas.entities().stream().map(TableSchemas::byEntity).toList());
        all.addAll(HistorySchemas.all());
        return List.copyOf(all);
    }

    // ---- plumbing ---------------------------------------------------------------------------------------

    /**
     * A link binding's {@code entityClass} is the OWNING promise rather than an entity of its own,
     * so reading attributes off it would compare a join table against a promise. Asked of the
     * registry rather than guessed from a name: {@code forType} answers with the PRIMARY binding
     * for an entity, so a binding that is not the one its own entity resolves to is a link (B3).
     */
    private static boolean isLink(HistoryBinding binding, HistoryRegistry registry) {
        return registry.forType(binding.entityClass()) != binding;
    }

    static Set<String> columnsOf(Connection connection, String table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        Set<String> columns = new LinkedHashSet<>();
        // H2 folds unquoted identifiers to upper case and Postgres to lower; asking for both
        // spellings is what the schema upgrades already do (B3).
        for (String spelling : List.of(table, table.toUpperCase(Locale.ROOT))) {
            try (ResultSet rows = metaData.getColumns(null, null, spelling, null)) {
                while (rows.next()) columns.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            if (!columns.isEmpty()) return columns;
        }
        return columns;
    }

    private static Map<String, Class<?>> singularTypes(EntityType<?> type) {
        Map<String, Class<?>> out = new java.util.LinkedHashMap<>();
        for (SingularAttribute<?, ?> a : type.getSingularAttributes()) out.put(a.getName(), a.getJavaType());
        return out;
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        return type;
    }
}
