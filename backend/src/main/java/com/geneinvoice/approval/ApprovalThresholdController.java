package com.geneinvoice.approval;

import com.geneinvoice.privilege.Privileges;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a branch's approval limit is read and where it is proposed (B2).
 *
 * <p>A second @RequestMapping class under /api/approvals rather than four more mappings on
 * {@link ApprovalController}, because a threshold is configuration and a pending change is work:
 * they have different privileges, different lifetimes and different readers. The two are safe
 * side by side because /api/approvals/thresholds/{regionId} is three segments and
 * /api/approvals/{id} is two, so no literal segment can ever bind to {id} (B2).
 *
 * <p>The PUT never takes effect on its own. APPROVAL_THRESHOLD_SET is always-checked, so
 * {@link ApprovalThresholdService#set} answers 202 PENDING_APPROVAL every single time and the
 * limit moves only when somebody else approves it (B2).
 */
@RestController
@RequestMapping("/api/approvals/thresholds")
@RequiredArgsConstructor
public class ApprovalThresholdController {

    private final ApprovalThresholdService service;

    /**
     * APPROVAL_VIEW and not APPROVAL_CONFIGURE: everybody who can read the queue needs to know
     * what the limit in that branch is, or the number a held change was measured against is a
     * figure with nothing behind it. {@code fromDefault} tells the screen whether to say "the
     * deployment default" rather than showing an amount no operator can find a row for (B2).
     */
    @GetMapping("/{regionId}")
    @PreAuthorize("hasAuthority('" + Privileges.APPROVAL_VIEW + "')")
    public ApprovalDtos.ThresholdDto get(@PathVariable Long regionId) {
        return service.get(regionId);
    }

    /**
     * The annotation answers "may you configure a limit SOMEWHERE"; the narrowing to THIS branch
     * is the service's regionAccess.require, because @PreAuthorize cannot see the path variable's
     * region and a per-region rule written in SpEL would be a second copy of B1's ladder (B2, B1).
     */
    @PutMapping("/{regionId}")
    @PreAuthorize("hasAuthority('" + Privileges.APPROVAL_CONFIGURE + "')")
    public ApprovalDtos.ThresholdDto set(@PathVariable Long regionId,
                                         @Valid @RequestBody ApprovalDtos.ThresholdRequest req) {
        return service.set(regionId, req);
    }
}
