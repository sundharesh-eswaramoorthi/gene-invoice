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

class SchemaDriftTest {

    @Test
    @DisplayName("a table the schema tool cannot migrate stops startup, naming what it could not do")
    void driftStopsStartup() throws Exception {
        String url = driftedDatabase();

        Throwable thrown = catchThrowable(() -> boot(url, true));

        assertThat(thrown)
                .as("startup fails rather than serving a table the schema tool could not migrate")
                .isNotNull();
        assertThat(stackTraceOf(thrown).toLowerCase(Locale.ROOT))
                .as("the failure names the table that could not be brought up to date")
                .contains("documents");
    }

    @Test
    @DisplayName("the halt can be turned off for a database whose drift is known and being dealt with")
    void theHaltCanBeTurnedOff() throws Exception {
        String url = driftedDatabase();

        assertThatCode(() -> {
            try (ConfigurableApplicationContext started = boot(url, false)) {
                assertThat(started.isRunning()).isTrue();
            }
        }).doesNotThrowAnyException();
    }

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
                .web(WebApplicationType.SERVLET)
                .profiles("test")
                .run("--server.port=0",
                        "--spring.datasource.url=" + url,
                        "--spring.jpa.hibernate.ddl-auto=update",
                        "--spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=" + halt);
    }
}
