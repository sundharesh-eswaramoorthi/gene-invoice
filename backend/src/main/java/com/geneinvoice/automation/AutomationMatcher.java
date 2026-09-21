package com.geneinvoice.automation;

import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * The WHERE half of a rule (R9). A rule's filters are the chips the list page already speaks, so
 * asking whether a record matches is asking the list page's own question with one more chip on it:
 * {@code id:eq:<the record>}. One count that comes back above zero is a match. Nothing here knows
 * what a customer or an invoice is, which is why a fourth kind of record costs a line in
 * {@link AutomationEntityType} and nothing here.
 *
 * <p><b>The scope is always empty, and that is not an oversight.</b> Every
 * {@code ScopeResolver.forX()} begins with {@code currentUser.require()}, and a consumer thread has
 * no SecurityContext at all — asking for a scope there does not return a wide one, it throws. The
 * access question is settled once, at authoring time, by AUTOMATION_MANAGE: a rule acts for the
 * whole app, so somebody who may write one may reach every record of its kind, and a customer login
 * never holds that privilege (R7). Handing this method a real scope would be a second, quieter
 * answer to a question that already has one.
 */
@Service
@RequiredArgsConstructor
public class AutomationMatcher {

    private final TableQueryExecutor queryExecutor;

    /**
     * Validates the filters against the kind's schema and returns the query they make. Called both
     * when a rule is written — where a bad chip is a 400 naming the column — and on every run,
     * because a column the schema has since dropped has to make the run say so rather than make the
     * rule quietly stop matching.
     */
    public TableQuery query(AutomationEntityType type, List<String> filters) {
        return TableQuery.parseUnpaged(type.tableSchema(), null, filters);
    }

    /** True when this one record passes the rule's WHERE right now. */
    @Transactional(readOnly = true)
    public boolean matches(AutomationEntityType type, List<String> filters, Long entityId) {
        TableSchema schema = type.tableSchema();
        List<String> chips = new ArrayList<>(filters);
        chips.add("id:eq:" + entityId);
        return queryExecutor.count(type.entityClass(), schema, query(type, chips), List.of()) > 0;
    }

    /**
     * The ids of every record the rule's WHERE matches, up to {@code limit}. This is what a daily or
     * weekly run walks, and what Run now backfills over; a rule with no filters matches the whole
     * table, which is exactly why the caller passes a ceiling and says so in its answer.
     */
    @Transactional(readOnly = true)
    public List<Long> idsMatching(AutomationEntityType type, List<String> filters, int limit) {
        return queryExecutor.ids(type.entityClass(), type.tableSchema(), query(type, filters),
                List.of(), limit);
    }
}
