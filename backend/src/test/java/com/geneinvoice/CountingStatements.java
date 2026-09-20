package com.geneinvoice;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Counts the SQL one request runs, so a test can say a table is read once rather than once per
 * thing asked of it. Hibernate builds it by name (application-test.yml) and it counts nothing
 * until a test asks it to, so the rest of the suite pays one field read per statement.
 */
public class CountingStatements implements StatementInspector {

    private static final List<String> RUN = new ArrayList<>();
    private static volatile boolean counting;

    /** Runs the body with the SQL counted, and gives back how often it read that table. */
    public static long reads(String table, ThrowingRunnable body) throws Exception {
        synchronized (RUN) {
            RUN.clear();
        }
        counting = true;
        try {
            body.run();
        } finally {
            counting = false;
        }
        synchronized (RUN) {
            return RUN.stream().filter(sql -> sql.startsWith("select") && sql.contains(table)).count();
        }
    }

    /** A test body that may throw, as a request does. */
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Override
    public String inspect(String sql) {
        if (counting) {
            synchronized (RUN) {
                RUN.add(sql.toLowerCase(Locale.ROOT).trim());
            }
        }
        return sql;
    }
}
