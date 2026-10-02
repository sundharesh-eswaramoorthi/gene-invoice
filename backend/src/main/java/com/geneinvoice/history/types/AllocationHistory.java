package com.geneinvoice.history.types;

import com.geneinvoice.history.Rehydration;
import com.geneinvoice.history.TypeHistory;
import com.geneinvoice.history.versions.PaymentAllocationVersion;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentAllocation;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.geneinvoice.history.TypeHistory.sameAmount;

public class AllocationHistory implements TypeHistory<PaymentAllocation, PaymentAllocationVersion> {

    public static final String CODE = "ALLOCATION";

    @Override public String code() { return CODE; }
    @Override public Class<PaymentAllocation> liveType() { return PaymentAllocation.class; }
    @Override public Class<PaymentAllocationVersion> versionType() { return PaymentAllocationVersion.class; }
    @Override public int rank() { return 35; }

    @Override
    public Map<Long, PaymentAllocationVersion> snapshotAll(Collection<PaymentAllocation> live,
                                                           SharedSessionContractImplementor s) {
        Map<Long, PaymentAllocationVersion> versions = new LinkedHashMap<>();
        for (PaymentAllocation a : live) {
            PaymentAllocationVersion v = new PaymentAllocationVersion();
            v.setId(a.getId());
            v.setPaymentId(a.getPayment() == null ? null : a.getPayment().getId());
            v.setInvoiceId(a.getInvoice() == null ? null : a.getInvoice().getId());
            v.setAmount(a.getAmount());
            versions.put(a.getId(), v);
        }
        return versions;
    }

    @Override
    public boolean sameState(PaymentAllocationVersion a, PaymentAllocationVersion b) {
        return Objects.equals(a.getPaymentId(), b.getPaymentId())
                && Objects.equals(a.getInvoiceId(), b.getInvoiceId())
                && sameAmount(a.getAmount(), b.getAmount());
    }

    /**
     * Each allocation as it hangs off its payment at the same point: the payment is rebuilt with every allocation it
     * had then, so an allocation, its payment and that payment's list agree, and neither rebuilds the other twice.
     * The rows given must therefore be the ones valid at {@code ctx.point()}, as every caller's are; one that differs
     * from the allocation rebuilt with its payment is refused rather than silently replaced.
     */
    @Override
    public List<PaymentAllocation> rehydrate(List<PaymentAllocationVersion> versions, Rehydration ctx) {
        Map<Long, Payment> payments = ctx.at(Payment.class,
                versions.stream().map(PaymentAllocationVersion::getPaymentId).toList());
        List<PaymentAllocation> allocations = new ArrayList<>(versions.size());
        for (PaymentAllocationVersion v : versions) {
            Payment payment = payments.get(v.getPaymentId());
            PaymentAllocation found = payment == null ? null : payment.getAllocations().stream()
                    .filter(a -> a.getId().equals(v.getId()))
                    .findFirst()
                    .orElse(null);
            if (found == null) {
                throw new IllegalStateException(
                        "Allocation " + v.getId() + " is not on payment " + v.getPaymentId() + " at the point");
            }
            if (!sameState(snapshotAll(List.of(found), null).get(found.getId()), v)) {
                throw new IllegalStateException(
                        "Allocation " + v.getId() + " as given is not the allocation valid at the point");
            }
            allocations.add(found);
        }
        return allocations;
    }
}
