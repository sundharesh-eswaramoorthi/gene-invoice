package com.geneinvoice.config;

import com.geneinvoice.GeneInvoiceApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A database the schema tool cannot bring up to date stops startup (D-01, regression 2026-09-21).
 *
 * <p>{@code ddl-auto: update} cannot add a NOT NULL column to a table that already has rows. It
 * logs the failure at WARN and carries on, so the app announced itself started and then answered
 * 500 on every request against that table — which is how a whole feature stayed broken in a
 * deployment while the app looked healthy. {@code hbm2ddl.halt_on_error} makes that failure fatal,
 * at the moment someone is watching.
 *
 * <p>Each case gets its own in-memory database so one test's drift is not another's.
 */
class SchemaDriftTest {

    @Test
    @DisplayName("a table the schema tool cannot migrate stops startup, naming what it could not do")
    void driftStopsStartup() throws Exception {
        String url = driftedDatabase();

        Throwable thrown = catchThrowable(() -> boot(url, true));

        assertThat(thrown)
                .as("startup fails rather than serving a table the schema tool could not migrate")
                .isNotNull();
        // The whole chain, not the top message: the schema failure is the root cause under
        // "unable to start web server", and the table is named in whatever case the database uses.
        assertThat(stackTraceOf(thrown).toLowerCase(Locale.ROOT))
                .as("the failure names the table that could not be brought up to date")
                .contains("documents");
    }

    @Test
    @DisplayName("the halt can be turned off for a database whose drift is known and being dealt with")
    void theHaltCanBeTurnedOff() throws Exception {
        String url = driftedDatabase();

        // SCHEMA_HALT_ON_ERROR=false: the same drift, started anyway. The escape hatch has to work,
        // or an operator meeting this at 3am has no way to get the rest of the app up.
        assertThatCode(() -> {
            try (ConfigurableApplicationContext started = boot(url, false)) {
                assertThat(started.isRunning()).isTrue();
            }
        }).doesNotThrowAnyException();
    }

    /**
     * A database an older version left behind: {@code documents} exists with none of the entity's
     * columns and a row in it, so every {@code alter table ... add column ... not null} fails.
     */
    private static String driftedDatabase() throws SQLException {
        String url = "jdbc:h2:mem:geneinvoice-drift-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table documents (id bigint primary key)");
            statement.execute("insert into documents (id) values (1)");
        }
        return url;
    }

    private static String stackTraceOf(Throwable thrown) {
        StringWriter out = new StringWriter();
        thrown.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private static ConfigurableApplicationContext boot(String url, boolean halt) {
        return new SpringApplicationBuilder(GeneInvoiceApplication.class)
                // A servlet context, not NONE: the security configuration wants one, and this is
                // meant to be the real application starting, not a cut-down version of it.
                .web(WebApplicationType.SERVLET)
                .profiles("test")
                // As command-line arguments, not builder properties: those are *default*
                // properties, which application.yml then overrides — including the datasource,
                // which would quietly point this at the ordinary test database instead of the
                // drifted one, and pass for the wrong reason.
                .run("--server.port=0",
                        "--spring.datasource.url=" + url,
                        "--spring.jpa.hibernate.ddl-auto=update",
                        "--spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=" + halt);
    }
}
