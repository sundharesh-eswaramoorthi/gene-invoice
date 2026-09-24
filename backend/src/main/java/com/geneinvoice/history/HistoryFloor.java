package com.geneinvoice.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The one row that says WHEN this installation started keeping history — the date before which an
 * as-of answer is a seed and not a reconstruction (B3).
 *
 * <p>It is a singleton with a fixed id and NOT a generated one, because "install once, never
 * twice" has to be a property of the table rather than of the bean that writes it: a second
 * instance racing the first on boot would otherwise write a second floor and every pre-floor
 * answer would depend on which one was read. B3-UPGRADES writes it and publishes it through
 * {@code AsOfFloor}; nothing here reads it (B3).
 */
@Entity
@Table(name = "history_floor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HistoryFloor {

    /** The only id there is. A second row would be a second floor (B3). */
    public static final long SINGLETON = 1L;

    @Id
    private Long id;

    @Column(name = "installed_at", nullable = false)
    private Instant installedAt;
}
