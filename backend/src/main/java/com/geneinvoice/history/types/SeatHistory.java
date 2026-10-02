package com.geneinvoice.history.types;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.Rehydration;
import com.geneinvoice.history.TypeHistory;
import com.geneinvoice.history.versions.CustomerPocVersion;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.user.User;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.geneinvoice.history.TypeHistory.sameInstant;

/** A customer's POC seats. */
public class SeatHistory implements TypeHistory<CustomerPoc, CustomerPocVersion> {

    public static final String CODE = "SEAT";

    @Override public String code() { return CODE; }
    @Override public Class<CustomerPoc> liveType() { return CustomerPoc.class; }
    @Override public Class<CustomerPocVersion> versionType() { return CustomerPocVersion.class; }
    @Override public int rank() { return 60; }

    @Override
    public Map<Long, CustomerPocVersion> snapshotAll(Collection<CustomerPoc> live, SharedSessionContractImplementor s) {
        Map<Long, CustomerPocVersion> versions = new LinkedHashMap<>();
        for (CustomerPoc seat : live) {
            CustomerPocVersion v = new CustomerPocVersion();
            v.setId(seat.getId());
            v.setCustomerId(seat.getCustomer() == null ? null : seat.getCustomer().getId());
            v.setUserId(seat.getUser() == null ? null : seat.getUser().getId());
            v.setPocType(seat.getPocType());
            v.setPrimary(seat.isPrimary());
            v.setCreatedByUserId(seat.getCreatedByUserId());
            v.setCreatedAt(seat.getCreatedAt());
            versions.put(seat.getId(), v);
        }
        return versions;
    }

    @Override
    public boolean sameState(CustomerPocVersion a, CustomerPocVersion b) {
        return Objects.equals(a.getCustomerId(), b.getCustomerId())
                && Objects.equals(a.getUserId(), b.getUserId())
                && a.getPocType() == b.getPocType()
                && Objects.equals(a.getPrimary(), b.getPrimary())
                && Objects.equals(a.getCreatedByUserId(), b.getCreatedByUserId())
                && sameInstant(a.getCreatedAt(), b.getCreatedAt());
    }

    /** Each seat with its customer as it was at the point (shallow) and its holder as they are now. */
    @Override
    public List<CustomerPoc> rehydrate(List<CustomerPocVersion> versions, Rehydration ctx) {
        Map<Long, Customer> customers = ctx.at(Customer.class,
                versions.stream().map(CustomerPocVersion::getCustomerId).toList());
        Map<Long, User> holders = ctx.usersOrStub(versions.stream().map(CustomerPocVersion::getUserId).toList());
        List<CustomerPoc> seats = new ArrayList<>(versions.size());
        for (CustomerPocVersion v : versions) {
            HistoricalCustomerPoc seat = new HistoricalCustomerPoc();
            seat.setId(v.getId());
            seat.setCustomer(v.getCustomerId() == null ? null : customers.get(v.getCustomerId()));
            seat.setUser(v.getUserId() == null ? null : holders.get(v.getUserId()));
            seat.setPocType(v.getPocType());
            seat.setPrimary(Boolean.TRUE.equals(v.getPrimary()));
            seat.setCreatedByUserId(v.getCreatedByUserId());
            seat.setCreatedAt(v.getCreatedAt());
            seats.add(seat);
        }
        return seats;
    }
}
