package com.geneinvoice.automation.event;

import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.history.versions.HistoryTransactionRowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The moment an event's condition is evaluated at (D10, PC-1): for a change, the instant of the transaction that
 * made it, which its history writer recorded; for a schedule slot or a run by hand, the time it was for. So a
 * consumer that runs late, or was down, decides exactly what it would have decided at once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventPoints {

    public static final String NOT_RECORDED = "Evaluated on current data: the moment of the change was not recorded";

    private final HistoryTransactionRowRepository historyTransactions;

    /** The point, or null to evaluate live: a change whose moment was not recorded (its history was skipped). */
    @Transactional(readOnly = true)
    public AsOfPoint pointOf(AutomationEvent ev) {
        return switch (ev.getKind()) {
            case AutomationEvent.SCHEDULED, AutomationEvent.MANUAL ->
                    ev.getScheduledFor() == null ? null : AsOfPoint.at(ev.getScheduledFor());
            default -> {
                if (ev.getHistoryTxId() == null) yield missing(ev);
                yield historyTransactions.findFirstByTxId(ev.getHistoryTxId())
                        .map(h -> AsOfPoint.at(h.getInstant()))
                        .orElseGet(() -> missing(ev));
            }
        };
    }

    private static AsOfPoint missing(AutomationEvent ev) {
        log.warn("automation.event.point.missing event={} tx={}: evaluated on current data", ev.getId(),
                ev.getHistoryTxId());
        return null;
    }
}
