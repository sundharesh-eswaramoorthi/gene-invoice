package com.geneinvoice.common.bulk;

import java.util.List;

/** Minimal RFC-4180 CSV writer for the "export selected" action. */
public final class Csv {

    private Csv() {}

    public static String of(List<String> headers, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(line(headers.stream().map(h -> (Object) h).toList()));
        for (List<Object> row : rows) {
            sb.append(line(row));
        }
        return sb.toString();
    }

    private static String line(List<Object> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(cells.get(i)));
        }
        return sb.append("\r\n").toString();
    }

    private static String escape(Object value) {
        if (value == null) return "";
        String s = value.toString();
        // A leading =, +, - or @ makes spreadsheets treat the cell as a formula.
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
