package com.geneinvoice.history.types;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.history.Historical;

/** A customer as it was at a point; its row version is not kept, so it is null. */
public class HistoricalCustomer extends Customer implements Historical {
}
