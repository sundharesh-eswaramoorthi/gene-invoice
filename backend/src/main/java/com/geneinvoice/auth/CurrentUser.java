package com.geneinvoice.auth;

import com.geneinvoice.region.RegionGrants;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.RegionRights;
import com.geneinvoice.user.User;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

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

    /**
     * This caller's region rights, straight off the principal: zero SQL, and RegionGrants.none()
     * when there is nobody signed in, so a headless path reads as "nothing anywhere" rather than
     * throwing. "May that OTHER person work there?" is a different fact and reads the grant
     * repository instead (B1).
     */
    public RegionGrants grants() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AppUserDetails p
                ? p.getRegionGrants()
                : RegionGrants.none();
    }

    /**
     * "May I do X in region R?" — the per-record question, asked where the button is rather than
     * where the page is. has(String) is unchanged and still means "may I do X somewhere" (B1).
     */
    public boolean has(String privilege, Long regionId) {
        RegionRight needed = RegionRights.needed(privilege);
        return has(privilege) && (needed == null || grants().may(regionId, needed));
    }

    public boolean canAssignPoc(com.geneinvoice.user.UserRepository userRepository) {
        User u = require();
        return u.getCustomerId() == null
                && userRepository.hasPrivilege(u.getId(), com.geneinvoice.privilege.Privileges.POC_ASSIGN);
    }
}
