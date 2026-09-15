package com.geneinvoice.invoice;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Hands out invoice numbers, INV-yyyyMMdd-NNNN, sequential within a UTC day. One locked row carries
 * the day and the last number issued, so invoices created at the same moment can never be given the
 * same number — which a count of today's invoices, read without a lock, could.
 */
@Component
@RequiredArgsConstructor
public class InvoiceNumbers {

    private static final long ROW = 1L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final InvoiceNumberSequenceRepository sequenceRepository;
    private final InvoiceRepository invoiceRepository;

    /** Creates the row up front, so concurrent first invoices never race to insert it. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureSequenceRow() {
        if (!sequenceRepository.existsById(ROW)) {
            sequenceRepository.save(new InvoiceNumberSequence(ROW, null, 0));
        }
    }

    /**
     * The next number. Runs in the caller's transaction, which holds the row until it commits. Call
     * it before that transaction writes anything else, so concurrent creates queue here instead of
     * deadlocking over other rows.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next() {
        String day = LocalDate.now(ZoneOffset.UTC).format(DAY);
        String prefix = "INV-" + day + "-";
        InvoiceNumberSequence seq = sequenceRepository.lockById(ROW)
                .orElseGet(() -> sequenceRepository.saveAndFlush(new InvoiceNumberSequence(ROW, null, 0)));
        if (!day.equals(seq.getIssuedDay())) {
            // First invoice of the day: carry on from any numbers already issued today.
            seq.setIssuedDay(day);
            seq.setLastNumber(invoiceRepository.countByInvoiceNumberStartingWith(prefix));
        }
        seq.setLastNumber(seq.getLastNumber() + 1);
        return prefix + String.format("%04d", seq.getLastNumber());
    }
}
