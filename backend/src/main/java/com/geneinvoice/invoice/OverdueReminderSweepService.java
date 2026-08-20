package com.geneinvoice.invoice;

import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Overdue-reminder sweep. Each run selects every UNPAID or PARTIALLY_PAID invoice,
 * asks {@link InvoiceAgeingPolicy} (static, pure) for the latest due reminder step as
 * of the supplied instant, and raises at most that one step per invoice per run.
 *
 * <p>Ordering per invoice: atomic overlap claim &rarr; audit-history recheck for the
 * exact step action &rarr; {@code notifyAdmins} &rarr; one permanent audit row
 * ({@code OVERDUE_REMINDER_STEP_<step>}). Notification precedes the audit write, so a
 * failed notification leaves no row and a failed audit write leaves a committed
 * notification without a row (at-least-once delivery, exactly-once auditing).
 *
 * <p>The sweep is deliberately non-transactional: {@link NotificationService} and
 * {@link AuditService} run each call in its own transaction, and an exception for one
 * invoice is caught at the invoice boundary so the remaining invoices continue and the
 * failed invoice (left without an audit row) is retried by the next day's sweep.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OverdueReminderSweepService {

    private static final String ENTITY_TYPE = "INVOICE";
    private static final String ACTION_PREFIX = "OVERDUE_REMINDER_STEP_";
    private static final String NOTIF_TYPE = "OVERDUE_REMINDER";

    private final InvoiceRepository invoiceRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;

    /**
     * Atomic in-process overlap claims keyed by (invoice id, due step, UTC day of the
     * run). At most one competing sweep execution wins a key; because the day is part of
     * the key, an unaudited failed claim blocks nothing after the next 02:00 UTC
     * eligibility boundary. Claims are never persisted to the audit log and never
     * reported as raised reminders.
     */
    private final Set<String> overlapClaims = ConcurrentHashMap.newKeySet();

    public void runOverdueReminderSweep(Instant now) {
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        List<Invoice> candidates = invoiceRepository.findByStatusIn(
                List.of(InvoiceStatus.UNPAID, InvoiceStatus.PARTIALLY_PAID));
        for (Invoice invoice : candidates) {
            try {
                processInvoice(invoice, now, day);
            } catch (Exception e) {
                log.warn("Overdue reminder sweep failed for invoice {}", invoice.getId(), e);
            }
        }
    }

    private void processInvoice(Invoice invoice, Instant now, LocalDate day) {
        AgeingDecision decision = InvoiceAgeingPolicy.decide(
                invoice.getInvoiceDate(), invoice.getTotal(), invoice.getPaidAmount(), now);
        if (!decision.overdue()) {
            return;
        }

        String claimKey = invoice.getId() + ":" + decision.dueStep() + ":" + day;
        if (!overlapClaims.add(claimKey)) {
            return;
        }

        String action = ACTION_PREFIX + decision.dueStep();
        for (AuditLog row : auditService.historyFor(ENTITY_TYPE, invoice.getId())) {
            if (action.equals(row.getAction())) {
                return;
            }
        }

        notificationService.notifyAdmins(
                NOTIF_TYPE,
                "Invoice " + invoice.getInvoiceNumber() + " is overdue",
                "Invoice " + invoice.getInvoiceNumber() + " is overdue with an outstanding balance of "
                        + invoice.getBalance() + ". Overdue reminder step " + decision.dueStep()
                        + " has been raised.",
                "/invoices/" + invoice.getId());

        auditService.record(ENTITY_TYPE, invoice.getId(), action, null, null, null, null, null);
    }
}
