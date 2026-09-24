package com.geneinvoice.history;

import com.geneinvoice.common.asof.AsOfSupport;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Which tables can be asked as of a date, answered off the twins themselves rather than off a
 * hand-kept list beside them (B3).
 *
 * <p>Supplying this bean is the whole of the wire: AsOfController and TableSchemaController both
 * take it as an ObjectProvider, so {@code GET /api/as-of} stops answering {@code "entities": []}
 * and {@code GET /api/table-schemas/{entity}} stops answering {@code asOfSupported: false} with no
 * edit to either of them. Until B3-SCHEMAS there was nothing honest to put there — a table with no
 * mirror schema cannot be asked as of a date, and advertising one that could not be served is the
 * failure this port exists to make impossible.
 *
 * <p>IT SAYS "THERE IS A TWIN", NOT "THE ENDPOINT IS ALLOWLISTED". AsOfEndpoints is still the only
 * thing that decides whether a request carrying ?asOf is served or refused, and it is empty until
 * the slice units land. The two are deliberately separate: this one is about the SCHEMA and the
 * other about the HANDLER, and a table can have a twin some days before its list learns to use it.
 */
@Component
public class HistoryAsOfSupport implements AsOfSupport {

    @Override
    public boolean supports(String entity) {
        return HistorySchemas.byEntity(entity) != null;
    }

    @Override
    public List<String> entities() {
        return HistorySchemas.entities();
    }
}
