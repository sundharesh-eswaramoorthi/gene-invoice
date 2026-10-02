package com.geneinvoice.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The one row that says since when history can be read: {@code startedAt} is when the first baseline copied
 * the records that were already there, and stays null on a database that had none.
 */
@Entity
@Table(name = "history_meta")
@Getter
@Setter
@NoArgsConstructor
public class HistoryMeta {

    public static final short ID = 1;

    @Id
    private Short id;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "baseline_completed_at")
    private Instant baselineCompletedAt;

    public HistoryMeta(Short id) {
        this.id = id;
    }
}
