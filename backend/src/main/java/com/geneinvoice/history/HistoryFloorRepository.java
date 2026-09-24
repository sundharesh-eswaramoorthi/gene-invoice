package com.geneinvoice.history;

import org.springframework.data.jpa.repository.JpaRepository;

/** The one-row floor table. B3-UPGRADES installs the row; nothing here reads it (B3). */
public interface HistoryFloorRepository extends JpaRepository<HistoryFloor, Long> {
}
