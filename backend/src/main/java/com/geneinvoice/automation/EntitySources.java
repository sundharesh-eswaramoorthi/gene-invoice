package com.geneinvoice.automation;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.asof.AsOfSource;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.history.HistorySchemas;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * WHERE A RULE READS FROM, AND IT IS THE SAME SWITCH A LIST READS FROM (B3).
 *
 * <p>A2's conditions already compile to {@code FilterSpec} leaves over the same
 * {@link TableSchema} a list screen uses, so "evaluate this rule as of 31 January" needs no
 * evaluation path of its own: it needs the rule's query rooted where the January invoice list is
 * rooted. This class is that one decision, in one place, so a rule and a list can never disagree
 * about what "invoices as of January" means.
 *
 * <p>THE TWIN IS FOUND BY THE LIVE SCHEMA'S OWN ENTITY NAME and never by a second table of
 * constants. {@code HistorySchemas.byEntity("invoices")} is the very lookup
 * {@code HistoryAsOfSupport} answers {@code GET /api/as-of} with, and the twin deliberately keeps
 * the live {@code entity()} string, so the pair this returns is the pair
 * {@code InvoiceService.invoiceSource()} returns by construction rather than by agreement.
 * {@code RuleAsOfTest#aRuleAndAListAgreeOnWhatInvoicesAsOfAPastDateMeans} asserts it.
 *
 * <p>DEVIATION, STATED: IT DOES NOT CALL THOSE {@code *Source()} METHODS, AND THERE ARE THREE OF
 * THEM RATHER THAN SIX. The unit text says to switch "from rule.entityType() to the six public
 * *Source() methods"; {@link SubjectType} has exactly three values — CUSTOMER, INVOICE and PAYMENT
 * — because a rule's subject has to be something with an account behind it, so promises, disputes
 * and tasks have twins that no rule can name. Two facts about the real source refuse it. First,
 * each one begins {@code scopeResolver.forInvoices()}, which begins {@code currentUser.require()}
 * — and a scheduled fan-out runs on a sweeper thread with no principal at all, so the call throws
 * before it can return anything. Second, a rule is nobody's book (blueprint: automation passes NO
 * ScopeResolver scope), so the book half of what those methods build would have to be discarded
 * again on arrival. What is left of them is the root and the schema, which is exactly what this
 * returns, and the discarded half is replaced at the CALL SITES by the narrowing hatch
 * {@code RegionScope.asRegions} already wraps them in, under AUTOMATION_FANOUT, which is where a
 * rule's own bound has always lived. (Spelled without its parentheses on purpose: RegionCoverageTest
 * reads the source tree for that call and would otherwise list this file as a widening site (B1).)
 *
 * <p>THE SCOPE LIST IS THE ONE LINE THAT MATTERS. Under a date it leads with {@link AsOf#at},
 * because the twin carries the interval clause only inside its correlated subqueries and nothing
 * filters the ROOT: without it a rule evaluated as of January would match every VERSION of every
 * record and a dry run would report the number of edits. {@code locked} and {@code fetch} are
 * empty and stay empty — a rule renders no chips and reads no association, it selects ids.
 */
@Component
public class EntitySources {

    /**
     * The root, the schema and the interval clause for one rule, live or as of the date this
     * thread is answering in.
     *
     * <p>{@link AsOfSource} rather than three return values, for the reason that record was
     * written: a caller that swapped the root and forgot {@code AsOf.at(T)} would evaluate the
     * rule against every version of every record, and the three have to move together or not at
     * all.
     */
    public AsOfSource<Object> forSubject(SubjectType type) {
        TableSchema live = AutomationRuleService.schemaOf(type);
        if (!AsOfContext.isActive()) {
            return new AsOfSource<>(live.entityType(), live, List.of(), List.of(), List.of());
        }
        TableSchema twin = HistorySchemas.byEntity(live.entity());
        if (twin == null) {
            // Unreachable while the three subject types are customers, invoices and payments, all
            // three of which are mirrored — and a loud 400 rather than a silent fall back to the
            // live table, because answering today's records under a January banner is the one
            // thing this feature exists to prevent (B3).
            throw new BadRequestException("The " + live.entity()
                    + " table cannot be asked as of a date");
        }
        return new AsOfSource<>(twin.entityType(), twin,
                List.of(AsOf.at(AsOfContext.instant())), List.of(), List.of());
    }
}
