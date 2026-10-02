package com.geneinvoice.history.types;

import com.geneinvoice.history.Historical;
import com.geneinvoice.invoice.Invoice;

/** An invoice as it was at a point, with its customer as it was then and its lines from {@code items_json}. */
public class HistoricalInvoice extends Invoice implements Historical {
}
