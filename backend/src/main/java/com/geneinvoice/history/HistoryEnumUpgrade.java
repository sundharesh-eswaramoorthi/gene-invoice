package com.geneinvoice.history;

import com.geneinvoice.common.SchemaSupport;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every {@code @Enumerated(STRING)} column of every mirror, kept widenable — and enumerated from
 * {@link HistoryRegistry} rather than from a list somebody keeps by hand (B3).
 *
 * <p>THIS IS THE BEAN THAT MAKES B3'S OWN NAMED RISK STRUCTURALLY IMPOSSIBLE TO FORGET. Mirroring
 * the enum columns doubles the blast radius of adding an enum constant: what was one
 * {@code SchemaSupport.widen} call on {@code invoices.status} is now two, because
 * {@code invoice_history.status} carries a check constraint of its own that {@code ddl-auto:update}
 * generated when the column was created and will never modify again. A constant added in 2027 and
 * widened only on the live table would be accepted by the business insert and REJECTED by the
 * mirror insert — which happens inside the user's own transaction, at beforeCommit — so the user
 * would be told their perfectly ordinary save had failed, for a reason nobody could trace back to
 * a schema decision made years earlier.
 *
 * <p>So the list is not a list. It is read off the mirror classes the registry names, by
 * reflection over their {@code @Enumerated(EnumType.STRING)} fields: a twelfth mirror, or a
 * twelfth enum column on an existing one, is covered the moment it is mapped, and there is
 * nothing here for anyone to forget to edit. {@code HistorySchemaCheckTest} asserts the
 * enumeration against the mirrors themselves, in both directions (B3).
 *
 * <p>Raw JDBC through {@link SchemaSupport#widen}, an unused {@link EntityManagerFactory}
 * parameter to order this bean after Hibernate's schema export, catches SQLException, logs WARN
 * and NEVER throws — upgrades run first and carry on, checks run last and refuse.
 */
@Component
@DependsOn("historySeedUpgrade")
@Slf4j
class HistoryEnumUpgrade implements InitializingBean {

    private final DataSource dataSource;
    private final HistoryRegistry registry;

    HistoryEnumUpgrade(DataSource dataSource, HistoryRegistry registry,
                       EntityManagerFactory schemaUpToDate) {
        this.dataSource = dataSource;
        this.registry = registry;
    }

    @Override
    public void afterPropertiesSet() {
        try (Connection connection = dataSource.getConnection()) {
            for (EnumColumn column : enumColumns(registry)) {
                SchemaSupport.widen(connection, column.table(), column.column(), column.type());
            }
        } catch (SQLException e) {
            log.warn("Could not check the history check constraints: {}", e.getMessage());
        }
    }

    /** One enum column of one mirror table, and the enum whose constants it must accept. */
    record EnumColumn(String table, String column, Class<? extends Enum<?>> type) {
    }

    /**
     * Every enum column of every mirror in the registry, in binding order then field order. The
     * two link mirrors contribute none, which is not a special case: they have no enum fields (B3).
     */
    @SuppressWarnings("unchecked")
    static List<EnumColumn> enumColumns(HistoryRegistry registry) {
        List<EnumColumn> found = new ArrayList<>();
        for (HistoryBinding binding : registry.all()) {
            for (Field field : binding.mirrorClass().getDeclaredFields()) {
                Enumerated enumerated = field.getAnnotation(Enumerated.class);
                // ORDINAL columns carry no check constraint to widen, and no mirror has one; the
                // test asserts that stays true rather than this line assuming it (B3).
                if (enumerated == null || enumerated.value() != EnumType.STRING) continue;
                if (!field.getType().isEnum()) continue;
                found.add(new EnumColumn(binding.mirrorTable(), columnOf(field),
                        (Class<? extends Enum<?>>) field.getType()));
            }
        }
        return List.copyOf(found);
    }

    /**
     * The physical column, which is {@code @Column(name = ...)} when the mapping states one and
     * Spring Boot's CamelCaseToUnderscores strategy otherwise — {@code status} stays
     * {@code status} and {@code paymentStatus} becomes {@code payment_status} (B3).
     */
    static String columnOf(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null && !column.name().isBlank()) return column.name();
        return snake(field.getName());
    }

    /** Spring Boot's CamelCaseToUnderscoresNamingStrategy, for an attribute with no @Column (B3). */
    static String snake(String attribute) {
        return attribute.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
