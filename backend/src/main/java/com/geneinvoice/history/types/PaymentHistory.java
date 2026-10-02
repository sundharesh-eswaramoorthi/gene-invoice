package com.geneinvoice.history.types;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.Rehydration;
import com.geneinvoice.history.TypeHistory;
import com.geneinvoice.history.versions.PaymentAllocationVersion;
import com.geneinvoice.history.versions.PaymentVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.geneinvoice.history.TypeHistory.sameAmount;
import static com.geneinvoice.history.TypeHistory.sameInstant;

/** Payments; their allocations are versioned as records of their own ({@link AllocationHistory}). */
public class PaymentHistory implements TypeHistory<Payment, PaymentVersion> {

    public static final String CODE = "PAYMENT";

    @Override public String code() { return CODE; }
    @Override public Class<Payment> liveType() { return Payment.class; }
    @Override public Class<PaymentVersion> versionType() { return PaymentVersion.class; }
    @Override public int rank() { return 30; }

    @Override
    public Map<Long, PaymentVersion> snapshotAll(Collection<Payment> live, SharedSessionContractImplementor s) {
        Map<Long, PaymentVersion> versions = new LinkedHashMap<>();
        for (Payment p : live) {
            PaymentVersion v = new PaymentVersion();
            v.setId(p.getId());
            v.setCustomerId(p.getCustomer() == null ? null : p.getCustomer().getId());
            v.setAmount(p.getAmount());
            v.setCreditApplied(p.getCreditApplied());
            v.setMethod(p.getMethod());
            v.setNotes(p.getNotes());
            v.setPaidAt(p.getPaidAt());
            v.setCollectionPocId(p.getCollectionPoc() == null ? null : p.getCollectionPoc().getId());
            v.setRegionId(Region.idOf(p.getRegion()));
            v.setStatus(p.getStatus());
            versions.put(p.getId(), v);
        }
        return versions;
    }

    @Override
    public boolean sameState(PaymentVersion a, PaymentVersion b) {
        return Objects.equals(a.getCustomerId(), b.getCustomerId())
                && sameAmount(a.getAmount(), b.getAmount())
                && sameAmount(a.getCreditApplied(), b.getCreditApplied())
                && Objects.equals(a.getMethod(), b.getMethod())
                && Objects.equals(a.getNotes(), b.getNotes())
                && sameInstant(a.getPaidAt(), b.getPaidAt())
                && Objects.equals(a.getCollectionPocId(), b.getCollectionPocId())
                && Objects.equals(a.getRegionId(), b.getRegionId())
                && a.getStatus() == b.getStatus();
    }

    /**
     * Each payment with its customer as it was at the point (shallow), its Collection POC as they are now, and the
     * allocations it had then, each on its invoice as it was then. One query per kind for the page.
     */
    @Override
    public List<Payment> rehydrate(List<PaymentVersion> versions, Rehydration ctx) {
        Map<Long, Customer> customers = ctx.at(Customer.class,
                versions.stream().map(PaymentVersion::getCustomerId).toList());
        Map<Long, User> pocs = ctx.usersOrStub(versions.stream().map(PaymentVersion::getCollectionPocId).toList());
        Map<Long, Region> regions = ctx.regions(versions.stream().map(PaymentVersion::getRegionId).toList());
        Map<Long, List<PaymentAllocationVersion>> allocations = ctx.allocationsAt(
                versions.stream().map(PaymentVersion::getId).toList());
        Map<Long, Invoice> invoices = ctx.at(Invoice.class, allocations.values().stream()
                .flatMap(List::stream).map(PaymentAllocationVersion::getInvoiceId).toList());

        List<Payment> payments = new ArrayList<>(versions.size());
        for (PaymentVersion v : versions) {
            HistoricalPayment p = new HistoricalPayment();
            p.setId(v.getId());
            p.setCustomer(v.getCustomerId() == null ? null : customers.get(v.getCustomerId()));
            p.setAmount(v.getAmount());
            p.setCreditApplied(v.getCreditApplied());
            p.setMethod(v.getMethod());
            p.setNotes(v.getNotes());
            p.setPaidAt(v.getPaidAt());
            p.setCollectionPoc(v.getCollectionPocId() == null ? null : pocs.get(v.getCollectionPocId()));
            p.setRegion(v.getRegionId() == null ? null : regions.get(v.getRegionId()));
            p.setStatus(v.getStatus());
            List<PaymentAllocation> applied = new ArrayList<>();
            for (PaymentAllocationVersion a : allocations.getOrDefault(v.getId(), List.of())) {
                HistoricalPaymentAllocation allocation = new HistoricalPaymentAllocation();
                allocation.setId(a.getId());
                allocation.setPayment(p);
                allocation.setInvoice(a.getInvoiceId() == null ? null : invoices.get(a.getInvoiceId()));
                allocation.setAmount(a.getAmount());
                applied.add(allocation);
            }
            p.setAllocations(List.copyOf(applied));
            payments.add(p);
        }
        return payments;
    }
}
