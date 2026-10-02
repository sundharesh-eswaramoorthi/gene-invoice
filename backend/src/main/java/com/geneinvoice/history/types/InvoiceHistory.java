package com.geneinvoice.history.types;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.Rehydration;
import com.geneinvoice.history.TypeHistory;
import com.geneinvoice.history.VersionStore;
import com.geneinvoice.history.versions.InvoiceVersion;
import com.geneinvoice.history.versions.ItemSnapshot;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceItem;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.Region;
import com.geneinvoice.user.User;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.geneinvoice.history.TypeHistory.sameAmount;
import static com.geneinvoice.history.TypeHistory.sameInstant;

/**
 * Invoices, each with its lines as {@code items_json}: the items' own columns only, sorted by item id, amounts as
 * strings at scale 2. So the text is equal exactly when the lines are, and nothing in it belongs to another
 * record (a product's name has versions of its own).
 */
public class InvoiceHistory implements TypeHistory<Invoice, InvoiceVersion> {

    public static final String CODE = "INVOICE";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ITEMS = """
            select i.invoice.id, i.id, i.product.id, i.quantity, i.unitPrice, i.lineTotal
              from InvoiceItem i
             where i.invoice.id in :ids""";

    @Override public String code() { return CODE; }
    @Override public Class<Invoice> liveType() { return Invoice.class; }
    @Override public Class<InvoiceVersion> versionType() { return InvoiceVersion.class; }
    @Override public int rank() { return 20; }

    @Override
    public Map<Long, InvoiceVersion> snapshotAll(Collection<Invoice> live, SharedSessionContractImplementor s) {
        Map<Long, List<ItemSnapshot>> items = itemsOf(live.stream().map(Invoice::getId).toList(), s);
        Map<Long, InvoiceVersion> versions = new LinkedHashMap<>();
        for (Invoice inv : live) {
            InvoiceVersion v = new InvoiceVersion();
            v.setId(inv.getId());
            v.setInvoiceNumber(inv.getInvoiceNumber());
            v.setCustomerId(inv.getCustomer() == null ? null : inv.getCustomer().getId());
            v.setInvoiceDate(inv.getInvoiceDate());
            v.setDueDate(inv.getDueDate());
            v.setPaymentTerm(inv.getPaymentTerm());
            v.setTotal(inv.getTotal());
            v.setPaidAmount(inv.getPaidAmount());
            v.setStatus(inv.getStatus());
            v.setNotes(inv.getNotes());
            v.setSalesPocId(inv.getSalesPoc() == null ? null : inv.getSalesPoc().getId());
            v.setRegionId(Region.idOf(inv.getRegion()));
            v.setCreatedAt(inv.getCreatedAt());
            v.setItemsJson(itemsJson(items.getOrDefault(inv.getId(), List.of())));
            versions.put(inv.getId(), v);
        }
        return versions;
    }

    /** One query per 500 invoices for all their lines, instead of a collection load per invoice. */
    private static Map<Long, List<ItemSnapshot>> itemsOf(List<Long> invoiceIds, SharedSessionContractImplementor s) {
        Map<Long, List<ItemSnapshot>> items = new HashMap<>();
        for (int from = 0; from < invoiceIds.size(); from += VersionStore.CHUNK) {
            List<Long> chunk = invoiceIds.subList(from, Math.min(invoiceIds.size(), from + VersionStore.CHUNK));
            for (Object[] row : s.createQuery(ITEMS, Object[].class).setParameter("ids", chunk).getResultList()) {
                items.computeIfAbsent((Long) row[0], id -> new ArrayList<>()).add(new ItemSnapshot(
                        (Long) row[1], (Long) row[2], (Integer) row[3], (BigDecimal) row[4], (BigDecimal) row[5]));
            }
        }
        return items;
    }

    public static String itemsJson(List<ItemSnapshot> items) {
        List<ItemSnapshot> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(ItemSnapshot::id, Comparator.nullsLast(Comparator.naturalOrder())));
        try {
            return JSON.writeValueAsString(sorted);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invoice lines could not be written as JSON", e);
        }
    }

    public static List<ItemSnapshot> items(String itemsJson) {
        if (itemsJson == null) return List.of();
        try {
            return List.of(JSON.readValue(itemsJson, ItemSnapshot[].class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invoice lines could not be read from JSON", e);
        }
    }

    @Override
    public boolean sameState(InvoiceVersion a, InvoiceVersion b) {
        return Objects.equals(a.getInvoiceNumber(), b.getInvoiceNumber())
                && Objects.equals(a.getCustomerId(), b.getCustomerId())
                && sameInstant(a.getInvoiceDate(), b.getInvoiceDate())
                && Objects.equals(a.getDueDate(), b.getDueDate())
                && a.getPaymentTerm() == b.getPaymentTerm()
                && sameAmount(a.getTotal(), b.getTotal())
                && sameAmount(a.getPaidAmount(), b.getPaidAmount())
                && a.getStatus() == b.getStatus()
                && Objects.equals(a.getNotes(), b.getNotes())
                && Objects.equals(a.getSalesPocId(), b.getSalesPocId())
                && Objects.equals(a.getRegionId(), b.getRegionId())
                && sameInstant(a.getCreatedAt(), b.getCreatedAt())
                && Objects.equals(a.getItemsJson(), b.getItemsJson());
    }

    /**
     * Each invoice with its customer as it was at the point (shallow), its Sales POC as they are now, and its lines
     * from {@code items_json}, each naming its product as it was at the point. One query per kind for the page.
     */
    @Override
    public List<Invoice> rehydrate(List<InvoiceVersion> versions, Rehydration ctx) {
        Map<Long, Customer> customers = ctx.at(Customer.class,
                versions.stream().map(InvoiceVersion::getCustomerId).toList());
        Map<Long, User> pocs = ctx.usersOrStub(versions.stream().map(InvoiceVersion::getSalesPocId).toList());
        Map<Long, Region> regions = ctx.regions(versions.stream().map(InvoiceVersion::getRegionId).toList());
        List<List<ItemSnapshot>> lines = versions.stream().map(v -> items(v.getItemsJson())).toList();
        Map<Long, Product> products = ctx.at(Product.class,
                lines.stream().flatMap(List::stream).map(ItemSnapshot::productId).toList());

        List<Invoice> invoices = new ArrayList<>(versions.size());
        for (int i = 0; i < versions.size(); i++) {
            InvoiceVersion v = versions.get(i);
            HistoricalInvoice inv = new HistoricalInvoice();
            inv.setId(v.getId());
            inv.setInvoiceNumber(v.getInvoiceNumber());
            inv.setCustomer(v.getCustomerId() == null ? null : customers.get(v.getCustomerId()));
            inv.setInvoiceDate(v.getInvoiceDate());
            inv.setDueDate(v.getDueDate());
            inv.setPaymentTerm(v.getPaymentTerm());
            inv.setTotal(v.getTotal());
            inv.setPaidAmount(v.getPaidAmount());
            inv.setStatus(v.getStatus());
            inv.setNotes(v.getNotes());
            inv.setSalesPoc(v.getSalesPocId() == null ? null : pocs.get(v.getSalesPocId()));
            inv.setRegion(v.getRegionId() == null ? null : regions.get(v.getRegionId()));
            inv.setCreatedAt(v.getCreatedAt());
            List<InvoiceItem> items = new ArrayList<>();
            for (ItemSnapshot line : lines.get(i)) {
                HistoricalInvoiceItem item = new HistoricalInvoiceItem();
                item.setId(line.id());
                item.setInvoice(inv);
                item.setProduct(line.productId() == null ? null : products.get(line.productId()));
                item.setQuantity(line.quantity() == null ? 0 : line.quantity());
                item.setUnitPrice(line.unitPrice());
                item.setLineTotal(line.lineTotal());
                items.add(item);
            }
            inv.setItems(List.copyOf(items));
            invoices.add(inv);
        }
        return invoices;
    }
}
