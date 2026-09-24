package com.geneinvoice.common.asof;

import java.util.List;

/**
 * Which table entities can be asked as of a date, published at GET /api/as-of and, per schema, as
 * TableSchemaController.SchemaDto.asOfSupported. A port for the same reason as AsOfFloor: the
 * answer is the mirror registry's and the registry lives in com.geneinvoice.history (B3).
 *
 * <p>Absent until B3-SCHEMAS supplies one, which is why the feature ships announcing an empty
 * list: an endpoint that cannot answer says so, and nothing is advertised before it works.
 */
public interface AsOfSupport {

    boolean supports(String entity);

    /** The table entity names, in the order the UI should show them. */
    List<String> entities();
}
