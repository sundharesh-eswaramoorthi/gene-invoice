package com.geneinvoice.approval;

import com.geneinvoice.common.scope.AsOfIgnored;
import com.geneinvoice.privilege.Privileges;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A region's approval limits (§6.11). Configuration: always answered live. A PUT that only tightens answers 200; one
 * that also loosens answers 202 with the limits as they now are and the change held for another approver.
 */
@RestController
@AsOfIgnored
@RequestMapping("/api/regions/{regionId}/approval-thresholds")
@RequiredArgsConstructor
public class ApprovalThresholdController {

    private final ApprovalThresholdService service;

    /** Those who set or approve changes in the region; the service decides (403). */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApprovalThresholdService.ThresholdsView get(@PathVariable Long regionId) {
        return service.get(regionId);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('" + Privileges.APPROVAL_THRESHOLD_MANAGE + "')")
    public ResponseEntity<ApprovalThresholdService.ThresholdsView> put(
            @PathVariable Long regionId, @RequestBody(required = false) ApprovalThresholdService.PutRequest req) {
        ApprovalThresholdService.Saved saved = service.put(regionId, req);
        if (saved.held() == null) return ResponseEntity.ok(saved.view());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, "/api/approvals/" + saved.held().getId())
                .body(saved.view());
    }
}
