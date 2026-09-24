package com.geneinvoice.common.asof;

import java.time.LocalDate;

/**
 * The day the mirror started recording. A one-method port so common.asof can ask the question
 * without importing com.geneinvoice.history, which owns the answer and the table it lives in:
 * the package direction is history -> common.asof and never back (B3).
 *
 * <p>Absent until B3-UPGRADES supplies an implementation, so every collaborator injects it as an
 * ObjectProvider and reads a null floor as "nothing has been recorded yet, so no date is
 * pre-floor".
 */
public interface AsOfFloor {

    /** Null while no floor has been installed; otherwise the UTC day the seed was written. */
    LocalDate floorOrNull();
}
