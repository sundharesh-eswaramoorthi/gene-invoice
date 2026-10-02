package com.geneinvoice.history.types;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.Rehydration;
import com.geneinvoice.history.TypeHistory;
import com.geneinvoice.history.versions.CustomerVersion;
import com.geneinvoice.region.Region;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.geneinvoice.history.TypeHistory.sameAmount;
import static com.geneinvoice.history.TypeHistory.sameInstant;

public class CustomerHistory implements TypeHistory<Customer, CustomerVersion> {

    public static final String CODE = "CUSTOMER";

    @Override public String code() { return CODE; }
    @Override public Class<Customer> liveType() { return Customer.class; }
    @Override public Class<CustomerVersion> versionType() { return CustomerVersion.class; }
    @Override public int rank() { return 10; }

    @Override
    public Map<Long, CustomerVersion> snapshotAll(Collection<Customer> live, SharedSessionContractImplementor s) {
        Map<Long, CustomerVersion> versions = new LinkedHashMap<>();
        for (Customer c : live) {
            CustomerVersion v = new CustomerVersion();
            v.setId(c.getId());
            v.setName(c.getName());
            v.setPhone(c.getPhone());
            v.setEmail(c.getEmail());
            v.setAddress(c.getAddress());
            v.setCreditBalance(c.getCreditBalance());
            v.setPaymentTerm(c.getPaymentTerm());
            v.setRegionId(Region.idOf(c.getRegion()));
            v.setCreatedAt(c.getCreatedAt());
            versions.put(c.getId(), v);
        }
        return versions;
    }

    @Override
    public boolean sameState(CustomerVersion a, CustomerVersion b) {
        return Objects.equals(a.getName(), b.getName())
                && Objects.equals(a.getPhone(), b.getPhone())
                && Objects.equals(a.getEmail(), b.getEmail())
                && Objects.equals(a.getAddress(), b.getAddress())
                && sameAmount(a.getCreditBalance(), b.getCreditBalance())
                && a.getPaymentTerm() == b.getPaymentTerm()
                && Objects.equals(a.getRegionId(), b.getRegionId())
                && sameInstant(a.getCreatedAt(), b.getCreatedAt());
    }

    @Override
    public List<Customer> rehydrate(List<CustomerVersion> versions, Rehydration ctx) {
        Map<Long, Region> regions = ctx.regions(versions.stream().map(CustomerVersion::getRegionId).toList());
        List<Customer> customers = new ArrayList<>(versions.size());
        for (CustomerVersion v : versions) {
            HistoricalCustomer c = new HistoricalCustomer();
            c.setId(v.getId());
            c.setName(v.getName());
            c.setPhone(v.getPhone());
            c.setEmail(v.getEmail());
            c.setAddress(v.getAddress());
            c.setCreditBalance(v.getCreditBalance());
            c.setPaymentTerm(v.getPaymentTerm());
            c.setRegion(v.getRegionId() == null ? null : regions.get(v.getRegionId()));
            c.setCreatedAt(v.getCreatedAt());
            customers.add(c);
        }
        return customers;
    }
}
