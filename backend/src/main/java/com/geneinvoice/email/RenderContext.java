package com.geneinvoice.email;

import com.geneinvoice.customer.Customer;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Everything one template needs to render against one record, gathered once by the caller (A4).
 *
 * @param type     the subject's kind, which decides which catalogue applies
 * @param entity   the loaded record itself — a Customer, an Invoice or a Payment
 * @param customer the account the record hangs off, which every {@code Customer.*} slot reads
 * @param target   the role snapshot, from RoleResolver.snapshot and therefore region-checked
 * @param asOf     the run's own date. DATE ARITHMETIC ANYWHERE IN PART A READS THIS, never
 *                 Instant.now() and never InvoiceDates.today(), so a replayed run renders the
 *                 same numbers it rendered the first time (A4, A5).
 * @param cache    resolved values by placeholder key, so a body naming a role five times resolves
 *                 it once. Deliberately mutable and deliberately shared between the subject, the
 *                 body and a task title rendered from the same record.
 */
public record RenderContext(EmailEntityType type, Object entity, Customer customer,
                            EmailTargets.Target target, LocalDate asOf, Map<String, String> cache) {

    public RenderContext {
        // A caller that passes no cache gets one rather than an NPE inside the renderer, which
        // must never throw (A4).
        if (cache == null) cache = new HashMap<>();
    }

    /** The shape every caller wants: one fresh cache per record (A4). */
    public static RenderContext of(EmailEntityType type, Object entity, Customer customer,
                                   EmailTargets.Target target, LocalDate asOf) {
        return new RenderContext(type, entity, customer, target, asOf, new HashMap<>());
    }
}
