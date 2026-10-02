package com.geneinvoice.poc;

import com.geneinvoice.common.scope.AsOfIgnored;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@AsOfIgnored
@RequestMapping("/api/pocs")
@RequiredArgsConstructor
public class PocController {

    private final PocService pocService;
    private final ScopeResolver scopeResolver;
    private final CustomerService customerService;
    private final RegionRepository regionRepository;
    private final com.geneinvoice.auth.CurrentUser currentUser;

    /**
     * Who may be picked, region by region (§6.8): for a customer, in that customer's region (404 when the customer
     * is not visible); for a region, only one the caller holds; otherwise in the regions this request asks about.
     */
    @GetMapping("/assignable")
    @PreAuthorize("hasAuthority('" + Privileges.POC_VIEW + "')")
    public List<PocDtos.PocUserDto> assignable(@RequestParam PocType type,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "25") int limit,
                                               @RequestParam(required = false) Long customerId,
                                               @RequestParam(required = false) Long regionId) {
        requireNonCustomerCaller();
        Set<Long> regions;
        if (customerId != null) {
            regions = Set.of(Region.idOf(customerService.get(customerId).getRegion()));
        } else if (regionId != null) {
            regions = currentUser.rights().holdsAnyIn(regionId) ? Set.of(regionId) : Set.of();
        } else {
            regions = ViewScopeHolder.current().narrow(currentUser.rights().grantedRegions());
        }
        return pocService.assignable(type, regions, q, limit).stream()
                .map(PocDtos.PocUserDto::from).toList();
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

    /** The caller's POC standing overall, and in each of the regions this request asks about (§6.8). */
    @GetMapping("/my-scope")
    @PreAuthorize("hasAuthority('" + Privileges.POC_VIEW + "')")
    public Map<String, Object> myScope() {
        requireNonCustomerCaller();
        Set<Long> asked = ViewScopeHolder.current().narrow(currentUser.rights().grantedRegions());
        List<Map<String, Object>> regions = new ArrayList<>();
        List<Region> found = new ArrayList<>(asked.isEmpty() ? List.of() : regionRepository.findAllById(asked));
        found.sort(Comparator.comparing(Region::getCode));
        for (Region r : found) {
            Map<String, Object> region = new LinkedHashMap<>();
            region.put("regionId", r.getId());
            region.put("code", r.getCode());
            region.put("clearable",
                    scopeResolver.tier(ScopeResolver.Kind.INVOICE, ScopeResolver.Access.VIEW, r.getId())
                            == ScopeResolver.Tier.FULL);
            region.put("sales", scopeResolver.isAssignableIn(PocType.SALES, r.getId()));
            region.put("success", scopeResolver.isAssignableIn(PocType.SUCCESS, r.getId()));
            region.put("collection", scopeResolver.isAssignableIn(PocType.COLLECTION, r.getId()));
            regions.add(region);
        }
        return Map.of(
                "userId", currentUser.require().getId(),
                "clearable", scopeResolver.canSeeEverything(),
                "sales", scopeResolver.isAssignableAs(PocType.SALES),
                "success", scopeResolver.isAssignableAs(PocType.SUCCESS),
                "collection", scopeResolver.isAssignableAs(PocType.COLLECTION),
                "regions", regions);
    }

    private void requireNonCustomerCaller() {
        if (currentUser.isCustomer()) {
            throw new AccessDeniedException("Not allowed");
        }
    }
}
