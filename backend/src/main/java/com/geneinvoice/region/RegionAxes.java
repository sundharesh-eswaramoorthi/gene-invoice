package com.geneinvoice.region;

import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.document.Document;
import com.geneinvoice.email.Email;
import com.geneinvoice.email.EmailRecipient;
import com.geneinvoice.email.connection.GmailConnection;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceItem;
import com.geneinvoice.invoice.InvoiceNumberSequence;
import com.geneinvoice.notification.Notification;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/**
 * Where "which region is this row in?" is answered, once, per entity. Nothing else in the
 * application may hold an opinion about it: the predicate is built from this table inside
 * TableQueryExecutor, so an endpoint written next year cannot omit the axis by passing an empty
 * scope list, and an entity added next year cannot be listed without being classified (B1).
 */
public final class RegionAxes {

    private RegionAxes() {
    }

    // The CORE classification: every entity that existed before B1, plus the three B1 adds. A
    // feature that brings entities of its own contributes them through RegionAxisRegistry rather
    // than editing this map, because region must never import approval, task, automation or
    // history (B1).
    private static final Map<Class<?>, RegionAxis> CORE = Map.ofEntries(
            entry(Customer.class, RegionAxis.OWN),
            entry(Invoice.class, RegionAxis.VIA_CUSTOMER),
            entry(Payment.class, RegionAxis.VIA_CUSTOMER),
            entry(PaymentPromise.class, RegionAxis.VIA_CUSTOMER),
            entry(Dispute.class, RegionAxis.VIA_CUSTOMER_ID),
            entry(User.class, RegionAxis.VIA_USER_GRANTS),
            entry(Product.class, RegionAxis.NONE),
            entry(Role.class, RegionAxis.NONE),
            entry(Privilege.class, RegionAxis.NONE),
            entry(Notification.class, RegionAxis.NONE),
            entry(EmailRecipient.class, RegionAxis.NONE),
            entry(Email.class, RegionAxis.NONE),
            entry(Document.class, RegionAxis.NONE),
            entry(AuditLog.class, RegionAxis.NONE),
            entry(CustomerPoc.class, RegionAxis.NONE),
            entry(InvoiceItem.class, RegionAxis.NONE),
            entry(PaymentAllocation.class, RegionAxis.NONE),
            entry(GmailConnection.class, RegionAxis.NONE),
            entry(InvoiceNumberSequence.class, RegionAxis.NONE),
            entry(Region.class, RegionAxis.NONE),
            entry(UserRegionGrant.class, RegionAxis.NONE),
            entry(CustomerRegionHistory.class, RegionAxis.NONE));

    // Every NONE must say why, in a sentence a reviewer reads and can disagree with. NONE on its
    // own is a shrug, and a shrug is how an unregioned list gets shipped by accident (B1).
    private static final Map<Class<?>, String> CORE_REASONS = Map.ofEntries(
            entry(Product.class, "one catalogue; a price is not a branch's record"),
            entry(Role.class, "the permission vocabulary is identical in every region"),
            entry(Privilege.class, "the permission vocabulary is identical in every region"),
            entry(Notification.class, "a person's own queue, already scoped by ownedByCaller()"),
            entry(EmailRecipient.class,
                    "a person's own mailbox; splitting it loses their mail when they lose a region"),
            entry(Email.class, "derived: gated by EmailTargets.requireVisible on the owning record"),
            entry(Document.class,
                    "derived: gated by DocumentTargets.requireVisible on the owning record"),
            entry(AuditLog.class,
                    "gated per record by AuditController.ensureCallerCanSee; region is the record's"),
            entry(CustomerPoc.class, "a seat's region IS its customer's, by definition"),
            entry(InvoiceItem.class, "inside the invoice aggregate; never listed on its own"),
            entry(PaymentAllocation.class, "inside the payment aggregate; never listed on its own"),
            entry(GmailConnection.class, "a user's own outbound mailbox, keyed on user_id"),
            entry(InvoiceNumberSequence.class,
                    "one global numbering series; regionalising it renumbers every invoice"),
            entry(Region.class, "the region map itself"),
            entry(UserRegionGrant.class,
                    "the grant table the axis is computed FROM; scoping it would recurse"),
            entry(CustomerRegionHistory.class,
                    "the custody ledger; read only through RegionCustodyService and B3"));

    private static final Map<Class<?>, RegionAxis> AXES;
    private static final Map<Class<?>, String> UNREGIONED_BECAUSE;

    static {
        Map<Class<?>, RegionAxis> axes = new LinkedHashMap<>(CORE);
        Map<Class<?>, String> reasons = new LinkedHashMap<>(CORE_REASONS);
        for (RegionAxisRegistry.Contribution c : RegionAxisRegistry.contributions()) {
            axes.putAll(c.axes());
            reasons.putAll(c.unregionedBecause());
        }
        AXES = Map.copyOf(axes);
        UNREGIONED_BECAUSE = Map.copyOf(reasons);
    }

    public static RegionAxis of(Class<?> entity) {
        RegionAxis axis = AXES.get(entity);
        // An unlisted entity that silently answered "visible everywhere" is exactly the leak B1
        // exists to close, so it fails loudly on its first query instead (B1).
        if (axis == null) {
            throw new IllegalStateException("No region axis declared for " + entity.getName()
                    + "; add it to RegionAxes or to your feature's RegionAxisRegistry contribution (B1)");
        }
        return axis;
    }

    /** The sentence behind a NONE, or "" for an entity that has a real axis. Checked at boot (B1). */
    public static String reason(Class<?> entity) {
        String because = UNREGIONED_BECAUSE.get(entity);
        return because == null ? "" : because;
    }

    /** Every entity this table knows about, for the boot check to walk against the metamodel. */
    public static Set<Class<?>> classified() {
        return AXES.keySet();
    }
}
