package com.geneinvoice.history.types;

import com.geneinvoice.history.Historical;
import com.geneinvoice.payment.PaymentAllocation;

/** An allocation as it was at a point: part of a {@link HistoricalPayment}, on a {@link HistoricalInvoice}. */
public class HistoricalPaymentAllocation extends PaymentAllocation implements Historical {
}
