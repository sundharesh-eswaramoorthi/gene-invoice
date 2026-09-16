package com.geneinvoice.customer;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 30)
    private String phone;

    @Column(length = 120)
    private String email;

    /**
     * Other addresses the customer can be emailed at, besides {@link #email}. That one stays the
     * main address, which the customer's login shares.
     */
    @ElementCollection
    @CollectionTable(name = "customer_emails", joinColumns = @JoinColumn(name = "customer_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "email", length = 120, nullable = false)
    @BatchSize(size = 50)
    @Builder.Default
    private List<String> additionalEmails = new ArrayList<>();

    @Column(length = 500)
    private String address;

    @Column(nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal creditBalance = BigDecimal.ZERO;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    /** Every address on the customer, main one first, each once however it is capitalised. */
    public List<String> allEmailAddresses() {
        Map<String, String> byKey = new LinkedHashMap<>();
        if (email != null) byKey.put(email.toLowerCase(Locale.ROOT), email);
        for (String extra : additionalEmails) byKey.putIfAbsent(extra.toLowerCase(Locale.ROOT), extra);
        return List.copyOf(byKey.values());
    }
}
