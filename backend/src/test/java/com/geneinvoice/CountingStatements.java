package com.geneinvoice;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CountingStatements implements StatementInspector {

    private static final List<String> RUN = new ArrayList<>();
    private static volatile boolean counting;

    public static long reads(String table, ThrowingRunnable body) throws Exception {
        return capture(body).stream().filter(sql -> sql.startsWith("select") && sql.contains(table)).count();
    }

    /**
     * The same recording handed back as the statements themselves rather than as a count, so a
     * test can assert what a query CONTAINED and not only how many there were. That is what the
     * region tripwire needs: "no statement against a regional table runs without a region
     * restriction" is a question about the text of the SQL, not about its cardinality (B1).
     */
    public static List<String> capture(ThrowingRunnable body) throws Exception {
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
            return List.copyOf(RUN);
        }
    }

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
