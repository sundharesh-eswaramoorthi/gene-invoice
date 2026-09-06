package com.geneinvoice.strategy;

import com.geneinvoice.invoice.InvoiceStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "notification_strategies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationStrategy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(length = 500)
    private String description;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "notification_strategy_statuses",
            joinColumns = @JoinColumn(name = "strategy_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private Set<InvoiceStatus> statuses = new LinkedHashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "date_operator", nullable = false, length = 20)
    private DateOperator dateOperator;

    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "amount_operator", nullable = false, length = 20)
    private AmountOperator amountOperator;

    @Column(name = "amount_from", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountFrom;

    @Column(name = "amount_to", precision = 14, scale = 2)
    private BigDecimal amountTo;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "notification_strategy_recipients",
            joinColumns = @JoinColumn(name = "strategy_id"))
    @Column(name = "user_id", nullable = false)
    @Builder.Default
    private Set<Long> additionalRecipientUserIds = new LinkedHashSet<>();

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
