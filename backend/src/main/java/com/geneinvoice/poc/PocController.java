package com.geneinvoice.poc;

import com.geneinvoice.privilege.Privileges;
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

    /** Searchable list of users offerable as this POC kind. Always bounded (AC-A3). */
    @GetMapping("/assignable")
    @PreAuthorize("hasAuthority('" + Privileges.POC_VIEW + "')")
    public List<PocDtos.PocUserDto> assignable(@RequestParam PocType type,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "25") int limit) {
        requireNonCustomerCaller();
        return pocService.assignable(type, q, limit).stream()
                .map(PocDtos.PocUserDto::from).toList();
    }

    /** The three kinds plus whether the caller is themselves assignable, for pre-selecting forms. */
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

    /**
     * The caller's own default book scope: which filter the UI should pre-apply and whether they
     * may clear it (AC-A6).
     */
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
