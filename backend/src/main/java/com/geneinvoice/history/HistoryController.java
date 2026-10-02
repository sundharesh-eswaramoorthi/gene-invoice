package com.geneinvoice.history;

import com.geneinvoice.common.scope.AsOfIgnored;
import com.geneinvoice.privilege.Privileges;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** What dates history can answer for, and a repair on demand. Both answer live, whatever date is viewed. */
@RestController
@AsOfIgnored
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryRange range;
    private final HistoryReconciler reconciler;

    /** {@code historyStartsOn} null: no lower bound. */
    public record RangeDto(LocalDate historyStartsOn, LocalDate latestAsOf, String timezone) {}

    public record ReconcileDto(int checked, int missing, int drifted, int orphaned, int duplicates, int linkDrift,
                               int repaired) {
        static ReconcileDto from(ReconcileReport r) {
            return new ReconcileDto(r.checked(), r.missing().size(), r.drifted().size(), r.orphaned().size(),
                    r.duplicates().size(), r.linkDrift().size(), r.repaired());
        }
    }

    @GetMapping("/api/history/range")
    @PreAuthorize("isAuthenticated()")
    public RangeDto range() {
        return new RangeDto(range.historyStartsOn().orElse(null), range.latestAsOf(), "UTC");
    }

    @PostMapping("/api/admin/history/reconcile")
    @AsOfIgnored
    @PreAuthorize("hasAuthority('" + Privileges.USER_MANAGE + "')")
    public ReconcileDto reconcile() {
        return ReconcileDto.from(reconciler.reconcile());
    }
}
