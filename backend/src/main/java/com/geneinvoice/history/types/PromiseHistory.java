package com.geneinvoice.history.types;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.LinkHistory;
import com.geneinvoice.history.Rehydration;
import com.geneinvoice.history.TypeHistory;
import com.geneinvoice.history.versions.PromiseVersion;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.geneinvoice.history.TypeHistory.sameAmount;
import static com.geneinvoice.history.TypeHistory.sameInstant;

/**
 * Promises. {@code updatedAt} moves on every save and {@code brokenNotifiedAt} only records that a notice went
 * out: both are kept on a version but never make one. The invoice and payment links are versioned separately.
 */
public class PromiseHistory implements TypeHistory<PaymentPromise, PromiseVersion> {

    public static final String CODE = "PROMISE";

    @Override public String code() { return CODE; }
    @Override public Class<PaymentPromise> liveType() { return PaymentPromise.class; }
    @Override public Class<PromiseVersion> versionType() { return PromiseVersion.class; }
    @Override public int rank() { return 40; }

    @Override
    public Map<Long, PromiseVersion> snapshotAll(Collection<PaymentPromise> live, SharedSessionContractImplementor s) {
        Map<Long, PromiseVersion> versions = new LinkedHashMap<>();
        for (PaymentPromise p : live) {
            PromiseVersion v = new PromiseVersion();
            v.setId(p.getId());
            v.setCustomerId(p.getCustomer() == null ? null : p.getCustomer().getId());
            v.setAmount(p.getAmount());
            v.setPromisedDate(p.getPromisedDate());
            v.setCollectionPocId(p.getCollectionPoc() == null ? null : p.getCollectionPoc().getId());
            v.setNotes(p.getNotes());
            v.setStatus(p.getStatus());
            v.setFulfilledAmount(p.getFulfilledAmount());
            v.setStatusOverridden(p.isStatusOverridden());
            v.setOverrideReason(p.getOverrideReason());
            v.setOverriddenByUserId(p.getOverriddenByUserId());
            v.setOverriddenAt(p.getOverriddenAt());
            v.setBrokenNotifiedAt(p.getBrokenNotifiedAt());
            v.setCreatedByUserId(p.getCreatedByUserId());
            v.setRegionId(Region.idOf(p.getRegion()));
            v.setCreatedAt(p.getCreatedAt());
            v.setUpdatedAt(p.getUpdatedAt());
            versions.put(p.getId(), v);
        }
        return versions;
    }

    @Override
    public boolean sameState(PromiseVersion a, PromiseVersion b) {
        return Objects.equals(a.getCustomerId(), b.getCustomerId())
                && sameAmount(a.getAmount(), b.getAmount())
                && Objects.equals(a.getPromisedDate(), b.getPromisedDate())
                && Objects.equals(a.getCollectionPocId(), b.getCollectionPocId())
                && Objects.equals(a.getNotes(), b.getNotes())
                && a.getStatus() == b.getStatus()
                && sameAmount(a.getFulfilledAmount(), b.getFulfilledAmount())
                && Objects.equals(a.getStatusOverridden(), b.getStatusOverridden())
                && Objects.equals(a.getOverrideReason(), b.getOverrideReason())
                && Objects.equals(a.getOverriddenByUserId(), b.getOverriddenByUserId())
                && sameInstant(a.getOverriddenAt(), b.getOverriddenAt())
                && Objects.equals(a.getCreatedByUserId(), b.getCreatedByUserId())
                && Objects.equals(a.getRegionId(), b.getRegionId())
                && sameInstant(a.getCreatedAt(), b.getCreatedAt());
    }

    /**
     * Each promise with its customer as it was at the point (shallow), its Collection POC as they are now, and the
     * invoices and payments it was linked to then, each as it was then. One query per kind for the page.
     */
    @Override
    public List<PaymentPromise> rehydrate(List<PromiseVersion> versions, Rehydration ctx) {
        List<Long> ids = versions.stream().map(PromiseVersion::getId).toList();
        Map<Long, Customer> customers = ctx.at(Customer.class,
                versions.stream().map(PromiseVersion::getCustomerId).toList());
        Map<Long, User> pocs = ctx.usersOrStub(versions.stream().map(PromiseVersion::getCollectionPocId).toList());
        Map<Long, Region> regions = ctx.regions(versions.stream().map(PromiseVersion::getRegionId).toList());
        Map<Long, List<Long>> invoiceLinks = ctx.linksAt(LinkHistory.PROMISE_INVOICE, ids);
        Map<Long, List<Long>> paymentLinks = ctx.linksAt(LinkHistory.PROMISE_PAYMENT, ids);
        Map<Long, Invoice> invoices = ctx.at(Invoice.class,
                invoiceLinks.values().stream().flatMap(List::stream).toList());
        Map<Long, Payment> payments = ctx.at(Payment.class,
                paymentLinks.values().stream().flatMap(List::stream).toList());

        List<PaymentPromise> promises = new ArrayList<>(versions.size());
        for (PromiseVersion v : versions) {
            HistoricalPaymentPromise p = new HistoricalPaymentPromise();
            p.setId(v.getId());
            p.setCustomer(v.getCustomerId() == null ? null : customers.get(v.getCustomerId()));
            p.setAmount(v.getAmount());
            p.setPromisedDate(v.getPromisedDate());
            p.setCollectionPoc(v.getCollectionPocId() == null ? null : pocs.get(v.getCollectionPocId()));
            p.setNotes(v.getNotes());
            p.setStatus(v.getStatus());
            p.setFulfilledAmount(v.getFulfilledAmount());
            p.setStatusOverridden(Boolean.TRUE.equals(v.getStatusOverridden()));
            p.setOverrideReason(v.getOverrideReason());
            p.setOverriddenByUserId(v.getOverriddenByUserId());
            p.setOverriddenAt(v.getOverriddenAt());
            p.setBrokenNotifiedAt(v.getBrokenNotifiedAt());
            p.setCreatedByUserId(v.getCreatedByUserId());
            p.setRegion(v.getRegionId() == null ? null : regions.get(v.getRegionId()));
            p.setCreatedAt(v.getCreatedAt());
            p.setUpdatedAt(v.getUpdatedAt());
            LinkedHashSet<Invoice> linkedInvoices = new LinkedHashSet<>();
            invoiceLinks.getOrDefault(v.getId(), List.of()).forEach(id -> linkedInvoices.add(invoices.get(id)));
            p.setInvoices(linkedInvoices);
            LinkedHashSet<Payment> linkedPayments = new LinkedHashSet<>();
            paymentLinks.getOrDefault(v.getId(), List.of()).forEach(id -> linkedPayments.add(payments.get(id)));
            p.setPayments(linkedPayments);
            promises.add(p);
        }
        return promises;
    }
}
