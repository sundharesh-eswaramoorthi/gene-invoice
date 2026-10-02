package com.geneinvoice.automation;

import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Moves an earlier build's unreadable automation rows aside before Hibernate's DDL runs (MIG-R2-1, DESIGN §3.1):
 * {@code ddl-auto: update} adds this build's NOT NULL columns (kind, claims, actions_json…) with no default, which
 * Postgres refuses on a table that still has rows. The entity manager factory depends on this bean, and this bean
 * on nothing but the {@link DataSource}.
 */
@Configuration(proxyBeanMethods = false)
class AutomationPreSchemaConfig {

    static final String BEAN = "automationLegacyRows";

    @Bean(name = BEAN, initMethod = "archiveAll")
    LegacyAutomationRows automationLegacyRows(DataSource dataSource) {
        return new LegacyAutomationRows(dataSource);
    }

    @Bean
    static EntityManagerFactoryDependsOnPostProcessor automationLegacyRowsFirst() {
        return new EntityManagerFactoryDependsOnPostProcessor(BEAN);
    }
}
