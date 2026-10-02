package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.history.versions.DisputeVersion;
import com.geneinvoice.history.versions.EnumNames;
import com.geneinvoice.history.versions.EntityLinkVersion;
import com.geneinvoice.history.versions.InvoiceVersion;
import com.geneinvoice.history.versions.PaymentAllocationVersion;
import com.geneinvoice.history.versions.TaskVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Convert;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every version class is its live class, column for column: the same attributes with the same types, column
 * names and sizes, each association as a writable id plus a read-only association to the live record, and
 * nothing that could refuse a row (§3.5).
 */
class VersionShapeTest extends IntegrationTestBase {

    /** Columns every version has, which belong to the version rather than to the record. */
    static final Set<String> WINDOW = Set.of("versionId", "id", "validFrom", "validTo", "txId", "origin",
            "originRefId", "actorUserId");

    /** Live collections kept some other way: embedded as JSON, versioned as records, or versioned as links. */
    static final Map<Class<?>, Map<String, String>> COLLECTIONS = Map.of(
            Invoice.class, Map.of("items", "the itemsJson column"),
            Payment.class, Map.of("allocations", "PaymentAllocation versions"),
            PaymentPromise.class, Map.of("invoices", "PROMISE_INVOICE links", "payments", "PROMISE_PAYMENT links"));

    /** Version attributes with no live attribute of the same name. */
    static final Map<Class<?>, Set<String>> EXTRAS = Map.of(InvoiceVersion.class, Set.of("itemsJson"));

    /**
     * Live columns whose name a version row already uses for itself: a task's {@code origin} and {@code entity_id}
     * would be the version's provenance and record id, so the task's are kept under another attribute or column;
     * so is a dispute's {@code origin}. Live attribute → the version's attribute and column.
     */
    static final Map<Class<?>, Map<String, List<String>>> RENAMED = Map.of(
            TaskVersion.class, Map.of(
                    "origin", List.of(TaskVersion.TASK_ORIGIN, "task_origin"),
                    "entityId", List.of("entityId", "record_id")),
            DisputeVersion.class, Map.of(
                    "origin", List.of(DisputeVersion.DISPUTE_ORIGIN, "dispute_origin")));

    /** The same, table by table and in upper case, for the physical comparison: live column → version column. */
    static final Map<String, Map<String, String>> RENAMED_COLUMNS = Map.of(
            "TASK_VERSIONS", Map.of("ORIGIN", "TASK_ORIGIN", "ENTITY_ID", "RECORD_ID"),
            "DISPUTE_VERSIONS", Map.of("ORIGIN", "DISPUTE_ORIGIN"));

    @Autowired EntityManagerFactory emf;
    @Autowired JdbcTemplate jdbc;

    @Test
    void everyLiveAttributeHasItsColumnOnTheVersion() {
        List<String> problems = new ArrayList<>();
        for (TypeHistory<?, ?> history : HistoryModel.types()) {
            Class<?> live = history.liveType();
            Class<?> version = history.versionType();
            EntityType<?> liveType = emf.getMetamodel().entity(live);
            EntityType<?> versionType = emf.getMetamodel().entity(version);
            Set<String> accounted = new TreeSet<>(WINDOW);
            accounted.addAll(EXTRAS.getOrDefault(version, Set.of()));

            for (Attribute<?, ?> attribute : liveType.getAttributes()) {
                String name = attribute.getName();
                String where = live.getSimpleName() + "." + name + ": ";
                if (name.equals("version")) {
                    if (has(versionType, "version")) problems.add(where + "the row version is not stored");
                    continue;
                }
                if (name.equals("id")) continue;
                if (attribute instanceof PluralAttribute<?, ?, ?>) {
                    String keptAs = COLLECTIONS.getOrDefault(live, Map.of()).get(name);
                    if (keptAs == null) problems.add(where + "a collection nobody versions");
                    continue;
                }
                if (attribute.isAssociation()) {
                    String idName = name + "Id";
                    accounted.add(idName);
                    accounted.add(name);
                    if (!has(versionType, idName)) {
                        problems.add(where + "no " + version.getSimpleName() + "." + idName);
                        continue;
                    }
                    if (versionType.getAttribute(idName).getJavaType() != Long.class) {
                        problems.add(where + idName + " is not a Long");
                    }
                    if (!sameColumns(live, name, version, idName)) problems.add(where + "not the column of " + idName);
                    if (!has(versionType, name) || !versionType.getAttribute(name).isAssociation()
                            || versionType.getAttribute(name).getJavaType() != attribute.getJavaType()) {
                        problems.add(where + "no read-only association to " + attribute.getJavaType().getSimpleName());
                    } else if (!readOnlyWithoutForeignKey(version, name)) {
                        problems.add(where + "the " + name + " association is writable or has a foreign key");
                    }
                    continue;
                }
                List<String> renamed = RENAMED.getOrDefault(version, Map.of()).get(name);
                String kept = renamed == null ? name : renamed.get(0);
                accounted.add(kept);
                if (!has(versionType, kept)) {
                    problems.add(where + "missing on " + version.getSimpleName());
                    continue;
                }
                Class<?> versionJava = versionType.getAttribute(kept).getJavaType();
                if (versionJava != boxed(attribute.getJavaType())) {
                    problems.add(where + "a " + versionJava.getSimpleName() + " on the version");
                }
                if (renamed == null ? !sameColumns(live, name, version, kept)
                        : !columns(version, kept).equals(List.of(renamed.get(1)))) {
                    problems.add(where + "column name differs");
                }
                Column liveColumn = column(live, name);
                Column versionColumn = column(version, kept);
                if (length(liveColumn) != length(versionColumn) || precision(liveColumn) != precision(versionColumn)
                        || scale(liveColumn) != scale(versionColumn)) {
                    problems.add(where + "column size differs");
                }
            }

            for (Attribute<?, ?> attribute : versionType.getAttributes()) {
                if (!accounted.contains(attribute.getName())) {
                    problems.add(version.getSimpleName() + "." + attribute.getName() + ": no such live column");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    /**
     * The tables themselves, not the annotations: a TEXT column declared through {@code columnDefinition} has no
     * {@code @Column.length()} to compare, so a version column that lost it would silently become a short
     * varchar and refuse a long value just before commit.
     */
    @Test
    void everyLiveColumnHasTheSamePhysicalTypeInItsVersionTable() {
        List<String> problems = new ArrayList<>();
        for (TypeHistory<?, ?> history : HistoryModel.types()) {
            String liveTable = persister(history.liveType()).getTableName().toUpperCase(Locale.ROOT);
            String versionTable = persister(history.versionType()).getTableName().toUpperCase(Locale.ROOT);
            Map<String, String> live = physicalColumns(liveTable);
            Map<String, String> version = physicalColumns(versionTable);
            Map<String, String> renamed = RENAMED_COLUMNS.getOrDefault(versionTable, Map.of());
            assertThat(live).as(liveTable).isNotEmpty();
            for (Map.Entry<String, String> column : live.entrySet()) {
                String name = column.getKey();
                if (name.equals("ID") || name.equals("VERSION")) continue;
                String where = versionTable + "." + name + ": ";
                String kept = version.get(renamed.getOrDefault(name, name));
                if (kept == null) {
                    problems.add(where + "missing");
                } else if (column.getValue().startsWith("ENUM ")) {
                    // An enum is kept as its plain name, so a value the enum no longer has can still be stored.
                    if (!kept.startsWith("CHARACTER VARYING ")) problems.add(where + kept + " for an enum");
                } else if (!kept.equals(column.getValue())) {
                    problems.add(where + kept + " but live " + column.getValue());
                }
            }
        }
        assertThat(problems).isEmpty();
        // items_json has no live column to follow: it is TEXT, the type of the live TEXT column.
        String text = physicalColumns("DISPUTES").get("PROPOSED_CHANGE_JSON");
        assertThat(text).doesNotContain(" 255 ");
        assertThat(physicalColumns("INVOICE_VERSIONS")).containsEntry("ITEMS_JSON", text);
    }

    @Test
    void theRecordIdIsTheAttributeIdAndTheKeyIsTheVersionId() {
        for (Class<?> version : versionClasses()) {
            EntityType<?> type = emf.getMetamodel().entity(version);
            SingularAttribute<?, ?> key = type.getId(type.getIdType().getJavaType());
            assertThat(key.getName()).as(version.getSimpleName()).isEqualTo("versionId");
            assertThat(columns(version, "versionId")).as(version.getSimpleName()).containsExactly("version_id");
            assertThat(columns(version, "id")).as(version.getSimpleName())
                    .containsExactly(version == EntityLinkVersion.class ? "target_id" : "entity_id");
        }
    }

    @Test
    void businessColumnsAcceptNullAndEnumsAreStoredAsPlainNames() throws Exception {
        List<String> problems = new ArrayList<>();
        for (TypeHistory<?, ?> history : HistoryModel.types()) {
            Class<?> version = history.versionType();
            for (Field field : version.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                String where = version.getSimpleName() + "." + field.getName() + ": ";
                if (field.getType().isPrimitive()) problems.add(where + "primitive");
                Column column = field.getAnnotation(Column.class);
                if (column != null && !column.nullable()) problems.add(where + "NOT NULL");
                JoinColumn join = field.getAnnotation(JoinColumn.class);
                if (join != null && !join.nullable()) problems.add(where + "NOT NULL");
                if (field.getAnnotation(Enumerated.class) != null) problems.add(where + "@Enumerated");
                if (field.getType().isEnum()) {
                    Convert convert = field.getAnnotation(Convert.class);
                    if (convert == null || !EnumNames.EnumName.class.isAssignableFrom(convert.converter())) {
                        problems.add(where + "an enum without an EnumNames converter");
                    }
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void theModelKnowsEveryTypeInRankOrderAndRoutesReadsToVersionsOnlyAsOfADate() {
        assertThat(HistoryModel.types()).extracting(TypeHistory::code)
                .containsExactly("CUSTOMER", "PRODUCT", "INVOICE", "PAYMENT", "ALLOCATION", "PROMISE", "DISPUTE",
                        "SEAT", "TASK");
        assertThat(HistoryModel.types()).extracting(TypeHistory::rank).isSorted();
        for (TypeHistory<?, ?> history : HistoryModel.types()) {
            assertThat(HistoryModel.isVersioned(history.liveType())).isTrue();
            assertThat(HistoryModel.isVersionClass(history.versionType())).isTrue();
            assertThat(HistoryModel.isVersioned(history.versionType())).isFalse();
            assertThat(HistoryModel.byCode(history.code())).isSameAs(history);
            assertThat(HistoryModel.of(history.liveType())).isSameAs(history);
        }
        assertThat(HistoryModel.isVersionClass(EntityLinkVersion.class)).isTrue();
        assertThat(HistoryModel.isVersioned(User.class)).isFalse();
        assertThat(HistoryModel.links()).extracting(LinkHistory::code)
                .containsExactly("LINK:PROMISE_INVOICE", "LINK:PROMISE_PAYMENT");
        assertThat(HistoryModel.linksOf(PaymentPromise.class)).hasSize(2);
        assertThat(HistoryModel.linkByCode("LINK:PROMISE_PAYMENT").collectionAttribute()).isEqualTo("payments");
        assertThat(HistoryModel.navigation(InvoiceVersion.class, "customer")).isPresent();
        assertThat(HistoryModel.navigation(InvoiceVersion.class, "salesPoc")).isEmpty();

        assertThat(HistoryModel.rootFor(Invoice.class)).isEqualTo(Invoice.class);
        AsOfPoint yesterday = AsOfPoint.endOfDay(Today.date().minusDays(1));
        AsOf.with(yesterday, () -> {
            assertThat(HistoryModel.rootFor(Invoice.class)).isEqualTo(InvoiceVersion.class);
            assertThat(HistoryModel.rootFor(PaymentAllocation.class))
                    .isEqualTo(PaymentAllocationVersion.class);
            assertThat(HistoryModel.rootFor(User.class)).isEqualTo(User.class);
            return null;
        });
    }

    private List<Class<?>> versionClasses() {
        List<Class<?>> classes = new ArrayList<>();
        HistoryModel.types().forEach(h -> classes.add(h.versionType()));
        classes.add(EntityLinkVersion.class);
        return classes;
    }

    /** Column name to "type length precision scale" ("-" where the database reports none). */
    private Map<String, String> physicalColumns(String table) {
        Map<String, String> columns = new LinkedHashMap<>();
        jdbc.query("""
                select column_name, data_type, character_maximum_length, numeric_precision, numeric_scale
                  from information_schema.columns
                 where table_schema = 'PUBLIC' and table_name = ? order by column_name""", rs -> {
            columns.put(rs.getString(1), rs.getString(2) + " " + orDash(rs.getObject(3)) + " "
                    + orDash(rs.getObject(4)) + " " + orDash(rs.getObject(5)));
        }, table);
        return columns;
    }

    private static String orDash(Object value) {
        return value == null ? "-" : value.toString();
    }

    private static boolean has(EntityType<?> type, String name) {
        return type.getAttributes().stream().anyMatch(a -> a.getName().equals(name));
    }

    private AbstractEntityPersister persister(Class<?> entity) {
        return (AbstractEntityPersister) emf.unwrap(SessionFactoryImplementor.class)
                .getMappingMetamodel().getEntityDescriptor(entity);
    }

    private List<String> columns(Class<?> entity, String attribute) {
        AbstractEntityPersister persister = persister(entity);
        if (attribute.equals(persister.getIdentifierPropertyName())) {
            return List.of(persister.getIdentifierColumnNames());
        }
        return List.of(persister.getPropertyColumnNames(persister.getEntityMetamodel().getPropertyIndex(attribute)));
    }

    private boolean sameColumns(Class<?> live, String liveAttribute, Class<?> version, String versionAttribute) {
        return columns(live, liveAttribute).equals(columns(version, versionAttribute));
    }

    private boolean readOnlyWithoutForeignKey(Class<?> version, String association) {
        AbstractEntityPersister persister = persister(version);
        int index = persister.getEntityMetamodel().getPropertyIndex(association);
        for (boolean insertable : persister.getPropertyColumnInsertable()[index]) if (insertable) return false;
        for (boolean updatable : persister.getPropertyColumnUpdateable()[index]) if (updatable) return false;
        JoinColumn join = field(version, association).getAnnotation(JoinColumn.class);
        return join != null && join.foreignKey().value() == ConstraintMode.NO_CONSTRAINT;
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // declared higher up
            }
        }
        throw new IllegalArgumentException(type.getSimpleName() + " has no field " + name);
    }

    private static Column column(Class<?> type, String name) {
        return field(type, name).getAnnotation(Column.class);
    }

    private static int length(Column c) {
        return c == null ? 255 : c.length();
    }

    private static int precision(Column c) {
        return c == null ? 0 : c.precision();
    }

    private static int scale(Column c) {
        return c == null ? 0 : c.scale();
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == double.class) return Double.class;
        return type;
    }
}
