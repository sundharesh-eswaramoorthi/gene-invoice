package com.geneinvoice.region;

import com.geneinvoice.common.SchemaSupport;
import com.geneinvoice.privilege.Privileges;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/**
 * The gate that turns "every table is region-classified and every privilege is region-partitioned"
 * from a thing somebody audited once into a property of starting up.
 *
 * <p>Three of B1's five gates fail before a request is served — the compiler (a TableSchema cannot
 * be built without naming its entity), class initialisation (that name is looked up in RegionAxes,
 * which throws for an unclassified entity) and this bean. The compiler and class-init gates only
 * fire for an entity somebody put a TABLE SCHEMA over; this one walks the whole Hibernate
 * metamodel, so an @Entity added next year with no list of its own — read through a repository, a
 * join, or a feature's own query — still cannot reach production unclassified (B1).
 *
 * <p>Nothing here is a schema UPGRADE and nothing here repairs anything: upgrades run first and
 * never throw, checks run last and do. @DependsOn names the upgrade this bean validates, which is
 * the rule the blueprint fixes for every check in the programme (B1).
 */
@Component
@DependsOn("regionSchemaUpgrade")
@Slf4j
class RegionCoverageCheck implements InitializingBean {

    /** The two spellings of app.region.boot-check; warn is the one that serves traffic anyway. */
    static final String STRICT = "strict";
    static final String WARN = "warn";

    // The metamodel is read from it, and it is also what orders this bean after Hibernate's schema
    // export — the InvoiceSchemaUpgrade:34 / RowVersionUpgrade:22 idiom, one constructor parameter
    // doing both jobs (B1).
    private final EntityManagerFactory entityManagerFactory;
    private final DataSource dataSource;
    // Read straight off the property rather than through RegionProperties: the settled config puts
    // the boot check under app.region and the default region under app.regions, two sibling
    // prefixes differing by one letter, and binding a second prefix onto a @ConfigurationProperties
    // class named after the first would be worse than the one @Value (B1).
    private final String bootCheck;

    RegionCoverageCheck(EntityManagerFactory entityManagerFactory, DataSource dataSource,
                        @Value("${app.region.boot-check:strict}") String bootCheck) {
        this.entityManagerFactory = entityManagerFactory;
        this.dataSource = dataSource;
        this.bootCheck = bootCheck;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        Set<EntityType<?>> mapped = entityManagerFactory.getMetamodel().getEntities();
        for (EntityType<?> entity : mapped) {
            declaresAnAxis(entity.getJavaType());
        }
        // needed() throws for a privilege in neither half of the partition, so the loop IS the
        // assertion: a constant added next year and never classified stops the application here
        // rather than being silently exercisable in every region (B1).
        for (String privilege : Privileges.ALL) {
            RegionRights.needed(privilege);
        }
        try (Connection connection = dataSource.getConnection()) {
            regionColumnIsTight(SchemaSupport.isNullable(connection, "customers", "region_id"), strict());
        }
        log.info("Region coverage checked: {} mapped entities classified, {} privileges partitioned",
                mapped.size(), Privileges.ALL.size());
    }

    /**
     * An entity is classified, and a NONE says why in a sentence a reviewer reads. NONE on its own
     * is a shrug, and a shrug is how an unregioned list gets shipped by accident (B1).
     */
    static void declaresAnAxis(Class<?> entity) {
        RegionAxis axis = RegionAxes.of(entity);   // throws, naming the class, if it is unlisted
        requireReason(entity, axis, RegionAxes.reason(entity));
    }

    static void requireReason(Class<?> entity, RegionAxis axis, String reason) {
        if (axis == RegionAxis.NONE && reason.isBlank()) {
            throw new IllegalStateException(entity.getName() + " is unregioned with no reason; add"
                    + " the sentence to RegionAxes.UNREGIONED_BECAUSE or to your feature's"
                    + " RegionAxisRegistry contribution (B1)");
        }
    }

    /**
     * The whole guarantee rests on customers.region_id: while it is still nullable a customer can
     * exist in no region, and a region-scoped read silently skips it rather than refusing. That is
     * a half-finished migration, and a half-regioned table must not serve traffic — so the default
     * is to refuse to start, with REGION_BOOT_CHECK=warn as the operator's only lever, the
     * SCHEMA_HALT_ON_ERROR convention this application already runs under (B1, D-01).
     */
    static void regionColumnIsTight(boolean stillNullable, boolean strict) {
        if (!stillNullable) return;
        String message = "customers.region_id is still nullable; the region upgrade did not finish."
                + " Check the log for the backfill's WARN and restart, or set REGION_BOOT_CHECK=warn"
                + " to serve traffic anyway (B1)";
        if (strict) throw new IllegalStateException(message);
        log.error(message);
    }

    private boolean strict() {
        String setting = bootCheck == null ? "" : bootCheck.trim().toLowerCase(Locale.ROOT);
        if (WARN.equals(setting)) return false;
        // Anything that is neither spelling is a typo in somebody's environment, and guessing
        // "warn" for a typo would disarm the check silently, which is the one outcome this bean
        // exists to prevent (B1).
        if (!STRICT.equals(setting)) {
            log.warn("app.region.boot-check is '{}'; the only values are strict and warn, so this"
                    + " start is treated as strict", bootCheck);
        }
        return true;
    }
}
