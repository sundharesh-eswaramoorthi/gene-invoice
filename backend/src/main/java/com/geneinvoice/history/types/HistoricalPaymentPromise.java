package com.geneinvoice.history.types;

import com.geneinvoice.history.Historical;
import com.geneinvoice.promise.PaymentPromise;

/** A promise as it was at a point, linked to the invoices and payments it was linked to then, as they were then. */
public class HistoricalPaymentPromise extends PaymentPromise implements Historical {
}
