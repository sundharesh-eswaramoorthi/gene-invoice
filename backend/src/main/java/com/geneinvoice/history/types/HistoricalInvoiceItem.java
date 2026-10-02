package com.geneinvoice.history.types;

import com.geneinvoice.history.Historical;
import com.geneinvoice.invoice.InvoiceItem;

/** One line of a {@link HistoricalInvoice}, naming its product as it was at the same point. */
public class HistoricalInvoiceItem extends InvoiceItem implements Historical {
}
