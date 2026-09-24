package com.geneinvoice.history;

import com.geneinvoice.customer.CustomerHistory;
import com.geneinvoice.dispute.DisputeHistory;
import com.geneinvoice.invoice.InvoiceHistory;
import com.geneinvoice.invoice.InvoiceItemHistory;
import com.geneinvoice.payment.PaymentAllocationHistory;
import com.geneinvoice.payment.PaymentHistory;
import com.geneinvoice.poc.CustomerPocHistory;
import com.geneinvoice.promise.PromiseHistory;
import com.geneinvoice.promise.PromiseInvoiceHistory;
import com.geneinvoice.promise.PromisePaymentHistory;
import com.geneinvoice.region.RegionAxis;
import com.geneinvoice.region.RegionAxisRegistry;
import com.geneinvoice.task.TaskHistory;

import java.util.Map;

import static java.util.Map.entry;

/**
 * History's answer to "which region is this row in?", contributed rather than edited into
 * region/RegionAxes so the type-safe reference to the mirrors goes the permitted direction and
 * region still imports nothing of ours (B3, B1).
 *
 * <p>It is in THIS unit and not the next one because {@code RegionCoverageCheck} walks the whole
 * Hibernate metamodel and refuses to start for an unclassified @Entity: twelve new entities have
 * to be classified in the same commit that maps them.
 *
 * <p>EVERY MIRROR THAT IS LISTED ON ITS OWN IS VIA_CUSTOMER_ID, INCLUDING THE CUSTOMER MIRROR, AND
 * THAT LAST ONE IS A DELIBERATE DEVIATION FROM THE BLUEPRINT'S TABLE. The blueprint says
 * CustomerHistory=OWN, but {@code RegionScope.clause}'s OWN arm is
 * {@code root.get("region").get("id")} — it would read the region from the MIRROR, which loses the
 * now-clause of B3-04's two-clause leak guard, and making OWN resolve any other way means editing
 * RegionScope.java, which R4 owns and B3 is told not to touch. CustomerHistory therefore maps
 * customer_id a second time, read-only, as {@code customerId}, and VIA_CUSTOMER_ID then produces
 * exactly the intended pair of clauses — an EXISTS over customers for NOW and an EXISTS over
 * customer_region_history for THEN — with no change at the injection point at all (B1, B3).
 *
 * <p>NO MIRROR CARRIES region_id AS AN AXIS. Blueprint conflict 1 struck B3's own per-mirror
 * region column: region is anchored on customers and nowhere else, and "which region was this in
 * then" is answered by {@code RegionPredicates.asOf} over customer_region_history for every axis
 * at once (B1, B3).
 */
public final class HistoryAxes {

    private HistoryAxes() {
    }

    public static final RegionAxisRegistry.Contribution CONTRIBUTION =
            new RegionAxisRegistry.Contribution(
                    Map.ofEntries(
                            entry(CustomerHistory.class, RegionAxis.VIA_CUSTOMER_ID),
                            entry(InvoiceHistory.class, RegionAxis.VIA_CUSTOMER_ID),
                            entry(PaymentHistory.class, RegionAxis.VIA_CUSTOMER_ID),
                            entry(PromiseHistory.class, RegionAxis.VIA_CUSTOMER_ID),
                            entry(DisputeHistory.class, RegionAxis.VIA_CUSTOMER_ID),
                            entry(TaskHistory.class, RegionAxis.VIA_CUSTOMER_ID),
                            entry(InvoiceItemHistory.class, RegionAxis.NONE),
                            entry(PaymentAllocationHistory.class, RegionAxis.NONE),
                            entry(CustomerPocHistory.class, RegionAxis.NONE),
                            entry(PromiseInvoiceHistory.class, RegionAxis.NONE),
                            entry(PromisePaymentHistory.class, RegionAxis.NONE),
                            entry(HistoryFloor.class, RegionAxis.NONE)),
                    Map.ofEntries(
                            entry(InvoiceItemHistory.class,
                                    "inside the invoice aggregate; never listed on its own, and its"
                                            + " region is its invoice's (B3)"),
                            entry(PaymentAllocationHistory.class,
                                    "inside the payment aggregate; never listed on its own, and its"
                                            + " region is its payment's (B3)"),
                            entry(CustomerPocHistory.class,
                                    "a seat's region IS its customer's, by definition, exactly as"
                                            + " the live customer_pocs table is classified (B1, B3)"),
                            entry(PromiseInvoiceHistory.class,
                                    "a link is reached only through the promise that owns it, whose"
                                            + " region is its customer's (B3)"),
                            entry(PromisePaymentHistory.class,
                                    "a link is reached only through the promise that owns it, whose"
                                            + " region is its customer's (B3)"),
                            entry(HistoryFloor.class,
                                    "one installation-wide date saying when this system started"
                                            + " keeping history; it belongs to no branch (B3)")));
}
