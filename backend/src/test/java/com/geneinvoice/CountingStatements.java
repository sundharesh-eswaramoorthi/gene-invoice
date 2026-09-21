package com.geneinvoice;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CountingStatements implements StatementInspector {

    private static final List<String> RUN = new ArrayList<>();
    private static volatile boolean counting;

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
