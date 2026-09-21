package com.geneinvoice.document;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.privilege.Privileges;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static com.geneinvoice.document.DocumentEntityType.CUSTOMER;
import static com.geneinvoice.document.DocumentEntityType.INVOICE;
import static com.geneinvoice.document.DocumentEntityType.PAYMENT;

@Component
@RequiredArgsConstructor
public class DocumentTargets {

    private static final Map<DocumentEntityType, String> VIEW_PRIVILEGE = Map.of(
            CUSTOMER, Privileges.CUSTOMER_VIEW,
            INVOICE, Privileges.INVOICE_VIEW,
            PAYMENT, Privileges.PAYMENT_VIEW);

    private static final Map<DocumentEntityType, String> MANAGE_PRIVILEGE = Map.of(
            CUSTOMER, Privileges.CUSTOMER_MANAGE,
            INVOICE, Privileges.INVOICE_MANAGE,
            PAYMENT, Privileges.PAYMENT_MANAGE);

    private final CustomerService customerService;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final CurrentUser currentUser;

    public record Target(DocumentEntityType type, Long id, String label, Long customerId) {

        public String link() {
            return type.link(id);
        }
    }

    @Transactional(readOnly = true)
    public Target requireVisible(DocumentEntityType type, Long id) {
        requirePrivilege(VIEW_PRIVILEGE.get(type));
        return describe(type, id);
    }

    @Transactional(readOnly = true)
    public Target requireManageable(DocumentEntityType type, Long id) {
        requirePrivilege(currentUser.isCustomer()
                ? VIEW_PRIVILEGE.get(type)
                : MANAGE_PRIVILEGE.get(type));
        return describe(type, id);
    }

    public boolean canManage(DocumentEntityType type) {
        return !currentUser.isCustomer()
                && currentUser.has(Privileges.DOCUMENT_MANAGE)
                && currentUser.has(MANAGE_PRIVILEGE.get(type));
    }

    private void requirePrivilege(String privilege) {
        if (!currentUser.has(privilege)) {
            throw new AccessDeniedException("Not allowed");
        }
    }

    private Target describe(DocumentEntityType type, Long id) {
        return switch (type) {
            case CUSTOMER -> {
                Customer c = customerService.get(id);
                yield new Target(type, c.getId(), label("Customer " + c.getName()), c.getId());
            }
            case INVOICE -> {
                Invoice i = invoiceService.get(id);
                yield new Target(type, i.getId(), label("Invoice " + i.getInvoiceNumber()),
                        i.getCustomer().getId());
            }
            case PAYMENT -> {
                Payment p = paymentService.get(id);
                yield new Target(type, p.getId(), label("Payment #" + p.getId()),
                        p.getCustomer().getId());
            }
        };
    }

    private static String label(String text) {
        return text.length() <= Document.LABEL_MAX
                ? text
                : text.substring(0, Document.LABEL_MAX - 1) + "…";
    }
}
