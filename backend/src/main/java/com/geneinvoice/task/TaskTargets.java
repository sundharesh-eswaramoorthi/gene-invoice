package com.geneinvoice.task;

import com.geneinvoice.common.Fit;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.region.Region;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The record a task is put on, read as the caller: one they cannot see is not found (404). */
@Component
@RequiredArgsConstructor
public class TaskTargets {

    static final int LABEL_MAX = 200;

    private final CustomerService customerService;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;

    /** What a task keeps of its record: the label it goes by, its customer, and its region. */
    public record Target(TaskEntityType type, Long id, String label, Long customerId, Long regionId) {}

    @Transactional(readOnly = true)
    public Target load(TaskEntityType type, Long id) {
        return switch (type) {
            case CUSTOMER -> {
                Customer c = customerService.get(id);
                yield target(type, c.getId(), "Customer " + c.getName(), c.getId(), c.getRegion());
            }
            case INVOICE -> {
                Invoice i = invoiceService.get(id);
                yield target(type, i.getId(), "Invoice " + i.getInvoiceNumber(), i.getCustomer().getId(),
                        i.getRegion());
            }
            case PAYMENT -> {
                Payment p = paymentService.get(id);
                yield target(type, p.getId(), "Payment #" + p.getId(), p.getCustomer().getId(), p.getRegion());
            }
        };
    }

    private static Target target(TaskEntityType type, Long id, String label, Long customerId, Region region) {
        return of(type, id, label, customerId, Region.idOf(region));
    }

    /** A record already read by its caller (a rule's record context), its label fitted as a task keeps it. */
    public static Target of(TaskEntityType type, Long id, String label, Long customerId, Long regionId) {
        return new Target(type, id, Fit.to(label, LABEL_MAX), customerId, regionId);
    }
}
