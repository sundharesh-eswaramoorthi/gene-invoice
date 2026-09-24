package com.geneinvoice.poc;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.RegionRight;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pocs")
@RequiredArgsConstructor
public class PocController {

    private final PocService pocService;
    private final ScopeResolver scopeResolver;
    private final com.geneinvoice.auth.CurrentUser currentUser;
    // Only to turn a customerId into the branch it is in, and only through the scoped read, so
    // the picker cannot be used to probe for accounts the caller may not see (B1, AUTH-08).
    private final CustomerService customerService;

    @GetMapping("/assignable")
    @PreAuthorize("hasAuthority('" + Privileges.POC_VIEW + "')")
    public List<PocDtos.PocUserDto> assignable(@RequestParam PocType type,
                                               @RequestParam(required = false) Long customerId,
                                               @RequestParam(required = false) Long regionId,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "25") int limit) {
        requireNonCustomerCaller();
        return pocService.assignable(type, branch(customerId, regionId), q, limit).stream()
                .map(PocDtos.PocUserDto::from).toList();
    }

    /**
     * Which branch the picker is being opened for. The account's own region is preferred because
     * the caller is almost always already looking at a record; naming a bare regionId is the form
     * the grant editor and the new-account screen need, before there is an account to name (B1).
     */
    private Long branch(Long customerId, Long regionId) {
        // Through the scoped read, which already 404s: an account outside this caller's book or
        // their regions must not be usable as a way to ask about a branch (B1, AUTH-08).
        if (customerId != null) return customerService.get(customerId).getRegion().getId();
        if (regionId != null) {
            // The same question the customerId arm above asks, asked about the branch directly:
            // findAssignableInRegion is a hand-written @Query that never enters the funnel, so
            // RegionAxis.VIA_USER_GRANTS — which closes GET /api/users and /api/emails/people over
            // the very same people — never runs here. Without this, naming any branch returned its
            // POC-eligible staff directory to a caller who holds nothing there. 404 and not 403,
            // because an id space is being probed and this parameter must not become a way of
            // asking which branches exist (B1, AUTH-08).
            if (!currentUser.grants().may(regionId, RegionRight.VIEW)) {
                throw new NotFoundException("Region not found");
            }
            return regionId;
        }
        // A wildcard holder may browse the whole directory, because for them "anywhere" is an
        // answer. For everybody else "who can be a POC" has no answer that is true in every
        // branch, and returning their first one would be a guess (B1).
        if (currentUser.grants().allRegions(RegionRight.VIEW)) return null;
        throw new BadRequestException("Name a customerId or a regionId to list assignable people");
    }

    @GetMapping("/types")
    @PreAuthorize("hasAuthority('" + Privileges.POC_VIEW + "')")
    public List<Map<String, Object>> types() {
        requireNonCustomerCaller();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PocType t : PocType.values()) {
            out.add(Map.of(
                    "type", t.name(),
                    "label", t.label(),
                    "assignabilityPrivilege", t.assignabilityPrivilege(),
                    "callerIsAssignable", scopeResolver.isAssignableAs(t)));
        }
        return out;
    }

    @GetMapping("/my-scope")
    @PreAuthorize("hasAuthority('" + Privileges.POC_VIEW + "')")
    public Map<String, Object> myScope() {
        requireNonCustomerCaller();
        return Map.of(
                "userId", currentUser.require().getId(),
                "clearable", scopeResolver.canSeeEverything(),
                "sales", scopeResolver.isAssignableAs(PocType.SALES),
                "success", scopeResolver.isAssignableAs(PocType.SUCCESS),
                "collection", scopeResolver.isAssignableAs(PocType.COLLECTION));
    }

    private void requireNonCustomerCaller() {
        if (currentUser.isCustomer()) {
            throw new AccessDeniedException("Not allowed");
        }
    }
}
