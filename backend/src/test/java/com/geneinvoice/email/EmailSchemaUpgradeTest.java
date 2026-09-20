package com.geneinvoice.email;

import com.geneinvoice.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A database made before PARTIAL has a check constraint on {@code emails.status} that refuses it,
 * which {@code ddl-auto: update} leaves as it is; the app widens it at startup.
 */
class EmailSchemaUpgradeTest extends IntegrationTestBase {

    private static final String OLD_STATUSES = "'QUEUED', 'SENDING', 'SENT', 'FAILED', 'NOT_SENT', 'RECEIVED'";

    @Autowired DataSource dataSource;

    /** The check constraints on emails.status, name and clause. */
    private List<String[]> statusChecks(Connection c) throws SQLException {
        List<String[]> checks = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rows = st.executeQuery("""
                select tc.constraint_name, cc.check_clause from information_schema.table_constraints tc
                  join information_schema.check_constraints cc
                    on cc.constraint_schema = tc.constraint_schema and cc.constraint_name = tc.constraint_name
                 where tc.constraint_type = 'CHECK' and tc.table_name = 'EMAILS' and tc.table_schema = schema()
                """)) {
            while (rows.next()) {
                if (rows.getString(2).contains("\"STATUS\"")) checks.add(new String[]{rows.getString(1), rows.getString(2)});
            }
        }
        return checks;
    }

    private void savePartial() {
        emailRepository.save(Email.builder().entityType(EmailEntityType.CUSTOMER).entityId(1L).entityLabel("Customer")
                .direction(EmailDirection.OUTBOUND).status(EmailStatus.PARTIAL).subject("Partly")
                .fromName("System Administrator").fromInternal(true).build());
    }

    @Test
    void anOldStatusConstraintIsWidenedToEveryStatusAndACurrentOneIsLeftAlone() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            // As a database made before PARTIAL has it.
            try (Statement st = c.createStatement()) {
                for (String[] check : statusChecks(c)) st.execute("alter table emails drop constraint \"" + check[0] + "\"");
                st.execute("alter table emails add constraint emails_status_check check (status in (" + OLD_STATUSES + "))");
            }
            try {
                assertThatThrownBy(this::savePartial).isInstanceOf(DataIntegrityViolationException.class);

                EmailSchemaUpgrade.widen(c, "emails", "status", EmailStatus.class);
            } finally {
                // Whatever happened above, the other tests get a table that takes every status.
                EmailSchemaUpgrade.widen(c, "emails", "status", EmailStatus.class);
            }
            assertThat(statusChecks(c)).singleElement().satisfies(check -> assertThat(check[1]).contains("'PARTIAL'"));

            savePartial();
            assertThat(emailRepository.findAll()).extracting(Email::getStatus).containsExactly(EmailStatus.PARTIAL);

            // Up to date: nothing to do.
            String before = statusChecks(c).get(0)[0];
            EmailSchemaUpgrade.widen(c, "emails", "status", EmailStatus.class);
            assertThat(statusChecks(c)).singleElement().satisfies(check -> assertThat(check[0]).isEqualTo(before));
        }
    }
}
