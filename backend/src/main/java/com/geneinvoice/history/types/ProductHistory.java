package com.geneinvoice.history.types;

import com.geneinvoice.history.Rehydration;
import com.geneinvoice.history.TypeHistory;
import com.geneinvoice.history.versions.ProductVersion;
import com.geneinvoice.product.Product;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.geneinvoice.history.TypeHistory.sameAmount;
import static com.geneinvoice.history.TypeHistory.sameInstant;

public class ProductHistory implements TypeHistory<Product, ProductVersion> {

    public static final String CODE = "PRODUCT";

    @Override public String code() { return CODE; }
    @Override public Class<Product> liveType() { return Product.class; }
    @Override public Class<ProductVersion> versionType() { return ProductVersion.class; }
    @Override public int rank() { return 15; }

    @Override
    public Map<Long, ProductVersion> snapshotAll(Collection<Product> live, SharedSessionContractImplementor s) {
        Map<Long, ProductVersion> versions = new LinkedHashMap<>();
        for (Product p : live) {
            ProductVersion v = new ProductVersion();
            v.setId(p.getId());
            v.setName(p.getName());
            v.setDescription(p.getDescription());
            v.setPrice(p.getPrice());
            v.setActive(p.isActive());
            v.setCreatedAt(p.getCreatedAt());
            versions.put(p.getId(), v);
        }
        return versions;
    }

    @Override
    public boolean sameState(ProductVersion a, ProductVersion b) {
        return Objects.equals(a.getName(), b.getName())
                && Objects.equals(a.getDescription(), b.getDescription())
                && sameAmount(a.getPrice(), b.getPrice())
                && Objects.equals(a.getActive(), b.getActive())
                && sameInstant(a.getCreatedAt(), b.getCreatedAt());
    }

    @Override
    public List<Product> rehydrate(List<ProductVersion> versions, Rehydration ctx) {
        List<Product> products = new ArrayList<>(versions.size());
        for (ProductVersion v : versions) {
            HistoricalProduct p = new HistoricalProduct();
            p.setId(v.getId());
            p.setName(v.getName());
            p.setDescription(v.getDescription());
            p.setPrice(v.getPrice());
            p.setActive(Boolean.TRUE.equals(v.getActive()));
            p.setCreatedAt(v.getCreatedAt());
            products.add(p);
        }
        return products;
    }
}
