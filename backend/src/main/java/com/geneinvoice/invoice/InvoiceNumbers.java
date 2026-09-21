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

@Component
@RequiredArgsConstructor
public class InvoiceNumbers {

    private static final long ROW = 1L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final InvoiceNumberSequenceRepository sequenceRepository;
    private final InvoiceRepository invoiceRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureSequenceRow() {
        if (!sequenceRepository.existsById(ROW)) {
            sequenceRepository.save(new InvoiceNumberSequence(ROW, null, 0));
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next() {
        String day = LocalDate.now(ZoneOffset.UTC).format(DAY);
        String prefix = "INV-" + day + "-";
        InvoiceNumberSequence seq = sequenceRepository.lockById(ROW)
                .orElseGet(() -> sequenceRepository.saveAndFlush(new InvoiceNumberSequence(ROW, null, 0)));
        if (!day.equals(seq.getIssuedDay())) {
            seq.setIssuedDay(day);
            seq.setLastNumber(invoiceRepository.countByInvoiceNumberStartingWith(prefix));
        }
        seq.setLastNumber(seq.getLastNumber() + 1);
        return prefix + String.format("%04d", seq.getLastNumber());
    }
}
