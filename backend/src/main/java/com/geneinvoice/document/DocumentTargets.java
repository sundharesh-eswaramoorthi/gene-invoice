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

/**
 * Everything documents know about the records they hang off (§4.5): which privilege lets a caller
 * see one and which lets them change one, how one is loaded under the caller's customer
 * restriction and POC book, what it is called and which customer it belongs to.
 *
 * <p>The order of the checks is the point. The document privilege comes first, on the endpoint;
 * then the parent record's own privilege; then the record's scope, which is the record's own read
 * by id and so answers exactly as the record itself would — 403 for a customer login reaching
 * across, 404 for a POC reaching outside their book (AC-C11). Visibility is last, and only for a
 * customer login.
 */
@Component
@RequiredArgsConstructor
public class DocumentTargets {

    /** The privilege that lets a caller see each kind of record, and so read its documents. */
    private static final Map<DocumentEntityType, String> VIEW_PRIVILEGE = Map.of(
            CUSTOMER, Privileges.CUSTOMER_VIEW,
            INVOICE, Privileges.INVOICE_VIEW,
            PAYMENT, Privileges.PAYMENT_VIEW);

    /** And the one that lets them change it, and so attach to it or edit what is attached. */
    private static final Map<DocumentEntityType, String> MANAGE_PRIVILEGE = Map.of(
            CUSTOMER, Privileges.CUSTOMER_MANAGE,
            INVOICE, Privileges.INVOICE_MANAGE,
            PAYMENT, Privileges.PAYMENT_MANAGE);

    private final CustomerService customerService;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final CurrentUser currentUser;

    /** A record as a document needs it: what it is called, and whose it is. */
    public record Target(DocumentEntityType type, Long id, String label, Long customerId) {

        public String link() {
            return type.link(id);
        }
    }

    /** The record, for reading its documents: its view privilege, then its scope. */
    @Transactional(readOnly = true)
    public Target requireVisible(DocumentEntityType type, Long id) {
        requirePrivilege(VIEW_PRIVILEGE.get(type));
        return describe(type, id);
    }

    /**
     * The record, for attaching to it or changing what is attached: its manage privilege, then its
     * scope. A customer login needs only the record's <em>view</em> privilege — with
     * {@code DOCUMENT_MANAGE}, which the endpoint has already asked for. That is the one deliberate
     * exception to AC-C10, made because customers upload on their own records (§1, answer 4).
     */
    @Transactional(readOnly = true)
    public Target requireManageable(DocumentEntityType type, Long id) {
        requirePrivilege(currentUser.isCustomer()
                ? VIEW_PRIVILEGE.get(type)
                : MANAGE_PRIVILEGE.get(type));
        return describe(type, id);
    }

    /**
     * True when the caller could change a document on this kind of record at all — the privileges
     * alone, so the DTO can say what the UI may offer (AC-C22). Whether they can reach the record
     * is settled where it is loaded.
     */
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

    /**
     * Loads the record through its own service, which applies the customer restriction and the POC
     * book, and snapshots what a document shows of it.
     */
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

    /** A customer's name can be longer than the label column; the label is a snapshot, not a key. */
    private static String label(String text) {
        return text.length() <= Document.LABEL_MAX
                ? text
                : text.substring(0, Document.LABEL_MAX - 1) + "…";
    }
}
