package com.geneinvoice.auth;

import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.RegionRights;
import com.geneinvoice.user.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class CurrentUser {

    public User require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails p)) {
            throw new BadCredentialsException("Not authenticated");
        }
        return p.getUser();
    }

    public Long customerIdOrNull() {
        return require().getCustomerId();
    }

    public Long idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AppUserDetails p
                ? p.getUser().getId()
                : null;
    }

    public boolean isCustomer() {
        return require().getCustomerId() != null;
    }

    public boolean has(String privilege) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> privilege.equals(a.getAuthority()));
    }

    /** The principal's rights per region; none at all without a principal, so every check fails closed (C-8). */
    public RegionRights rights() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AppUserDetails p && p.getRights() != null
                ? p.getRights()
                : RegionRights.none();
    }

    public boolean hasIn(String privilege, Long regionId) {
        return rights().has(privilege, regionId);
    }

    public Set<Long> regionsWith(String privilege) {
        return rights().regionsWith(privilege);
    }

    /** For a record the caller can see: lacking the privilege in its region is a 403. */
    public void requireIn(String privilege, Long regionId) {
        if (!hasIn(privilege, regionId)) {
            throw new AccessDeniedException("Not allowed");
        }
    }

    /** A region the caller holds nothing in is answered as if it did not exist. */
    public void requireReachable(Long regionId, String notFoundMessage) {
        if (!rights().holdsAnyIn(regionId)) {
            throw new NotFoundException(notFoundMessage);
        }
    }

    public boolean canAssignPocIn(Long regionId) {
        return !isCustomer() && hasIn(Privileges.POC_ASSIGN, regionId);
    }

    /** The coarse check of a bulk POC change; each row is then held to its own region. */
    public boolean canAssignPocAnywhere() {
        return !isCustomer() && rights().anywhere(Privileges.POC_ASSIGN);
    }
}
