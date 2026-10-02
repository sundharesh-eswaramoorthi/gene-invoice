package com.geneinvoice.common.scope;

import java.time.LocalDate;

/** Says whether history can answer for a past day; throws an {@link AsOfException} when it cannot. */
public interface AsOfSupport {

    void requireAvailable(LocalDate d);
}
