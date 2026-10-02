package com.geneinvoice.history.types;

import com.geneinvoice.history.Historical;
import com.geneinvoice.payment.Payment;

/** A payment as it was at a point, with the allocations it had then, each on its invoice as it was then. */
public class HistoricalPayment extends Payment implements Historical {
}
