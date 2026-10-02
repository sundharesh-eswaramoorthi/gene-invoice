package com.geneinvoice.automation;

import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.rule.AutomationRule;
import com.geneinvoice.automation.rule.SuspendReason;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.automation.rule.TriggerKind;
import com.geneinvoice.automation.run.AutomationEffect;
import com.geneinvoice.automation.run.AutomationRun;
import com.geneinvoice.automation.run.EffectStatus;
import com.geneinvoice.automation.run.RunStatus;
import com.geneinvoice.common.schema.SchemaSupport;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Brings the automation tables up to the current mapping (§3.9). {@code ddl-auto: update} only adds columns, so a
 * database where an earlier build already made {@code automation_events} or {@code automation_rules} keeps that
 * build's NOT NULL columns and CHECK constraints, and would refuse every row this one writes: an outbox row would
 * roll back the user's save (P2, R5), a rule could not be created. So, before the enum columns are widened:
 * <ul>
 *   <li>every NOT NULL column the mapping does not require becomes nullable, and every varchar column shorter
 *       than the mapping declares (the earlier trigger_kind was varchar(20)) is lengthened;</li>
 *   <li>the outbox loses every CHECK (§3.6: nothing there can refuse a row); the other tables lose the CHECKs on
 *       columns the mapping does not have, and their enum CHECKs are widened;</li>
 *   <li>rows of the earlier build the mapping cannot read are moved to {@code <table>_legacy}, never dropped
 *       ({@link LegacyAutomationRows}, which also runs before Hibernate's DDL).</li>
 * </ul>
 * Every step changes nothing on a schema this build made, or on a second run.
 */
@Component
@Slf4j
class AutomationSchemaUpgrade implements InitializingBean {

    static final String EVENTS = LegacyAutomationRows.EVENTS;
    static final String RULES = LegacyAutomationRows.RULES;

    private final DataSource dataSource;
    private final EntityManagerFactory entityManagerFactory;

    AutomationSchemaUpgrade(DataSource dataSource, EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
        this.entityManagerFactory = schemaUpToDate;
    }

    @Override
    public void afterPropertiesSet() {
        relax(EVENTS, AutomationEvent.class, true);
        relax(RULES, AutomationRule.class, false);
        relax("automation_runs", AutomationRun.class, false);
        relax("automation_effects", AutomationEffect.class, false);
        // Before Hibernate's DDL too (AutomationPreSchemaConfig); again here, after relax, by the same rule.
        LegacyAutomationRows legacyRows = new LegacyAutomationRows(dataSource);
        legacyRows.archive(EVENTS);
        legacyRows.archive(RULES);
        widen(RULES, "trigger_kind", TriggerKind.class);
        widen(RULES, "entity_type", TriggerEntity.class);
        widen(RULES, "suspended_reason", SuspendReason.class);
        widen(RULES, "last_run_status", RunStatus.class);
        widen("automation_runs", "status", RunStatus.class);
        widen("automation_effects", "status", EffectStatus.class);
    }

    /**
     * The columns the mapping of {@code type} has in {@code table}, in lower case, each with whether it may be null,
     * as Hibernate's metamodel says.
     */
    static Map<String, Boolean> mappedColumns(EntityManagerFactory emf, Class<?> type, String table) {
        Map<String, Boolean> columns = new LinkedHashMap<>();
        EntityPersister persister = emf.unwrap(SessionFactoryImplementor.class).getMappingMetamodel()
                .getEntityDescriptor(type);
        persister.forEachSelectable((i, s) -> {
            if (s.isFormula() || !table.equalsIgnoreCase(unquote(s.getContainingTableExpression()))) return;
            columns.merge(unquote(s.getSelectionExpression()).toLowerCase(Locale.ROOT), s.isNullable(),
                    Boolean::logicalAnd);
        });
        // The metamodel calls a generated identifier nullable (it is, before the insert); its column never is.
        persister.getIdentifierMapping().forEachSelectable((i, s) ->
                columns.put(unquote(s.getSelectionExpression()).toLowerCase(Locale.ROOT), false));
        return columns;
    }

    /** The declared lengths of the mapping's bounded text columns in {@code table}, in lower case. */
    static Map<String, Long> mappedLengths(EntityManagerFactory emf, Class<?> type, String table) {
        Map<String, Long> lengths = new LinkedHashMap<>();
        emf.unwrap(SessionFactoryImplementor.class).getMappingMetamodel().getEntityDescriptor(type)
                .forEachSelectable((i, s) -> {
                    if (s.isFormula() || s.isLob() || s.getColumnDefinition() != null || s.getLength() == null
                            || !s.getJdbcMapping().getJdbcType().isStringLike()
                            || !table.equalsIgnoreCase(unquote(s.getContainingTableExpression()))) return;
                    lengths.merge(unquote(s.getSelectionExpression()).toLowerCase(Locale.ROOT), s.getLength(),
                            Math::max);
                });
        return lengths;
    }

    private void relax(String table, Class<?> type, boolean dropAllChecks) {
        Map<String, Boolean> mapped = mappedColumns(entityManagerFactory, type, table);
        Set<String> required = mapped.entrySet().stream().filter(c -> !c.getValue()).map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        try (Connection connection = dataSource.getConnection()) {
            SchemaSupport.relaxColumns(connection, table, mapped.keySet(), required, dropAllChecks);
            SchemaSupport.lengthen(connection, table, mappedLengths(entityManagerFactory, type, table));
        } catch (SQLException e) {
            log.warn("Could not relax {} to the current mapping: {}", table, e.getMessage());
        }
    }

    private void widen(String table, String column, Class<? extends Enum<?>> type) {
        try (Connection connection = dataSource.getConnection()) {
            SchemaSupport.widen(connection, table, column, type);
        } catch (SQLException e) {
            log.warn("Could not widen {}.{}: {}", table, column, e.getMessage());
        }
    }

    private static String unquote(String name) {
        return name == null ? "" : name.replace("\"", "").replace("`", "");
    }
}
