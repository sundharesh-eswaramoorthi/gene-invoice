package com.geneinvoice.history.versions;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * The instant of one history transaction that published automation events: the moment the rules evaluate those
 * events at (D10). Written by the history writer just before the transaction commits, so it is the same instant
 * as the transaction's versions. Nothing here can refuse the row: no foreign key and no unique constraint (the
 * transaction id is unique by construction).
 */
@Entity
@Table(name = "history_transactions", indexes = @Index(name = "idx_history_tx", columnList = "tx_id"))
@Getter
@NoArgsConstructor
public class HistoryTransactionRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tx_id", nullable = false, length = 36)
    private String txId;

    @Column(name = "instant", nullable = false)
    private Instant instant;

    public HistoryTransactionRow(String txId, Instant instant) {
        this.txId = txId;
        this.instant = instant;
    }
}
