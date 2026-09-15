package com.geneinvoice.auth;

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

    /** The caller's id, or null outside an authenticated request (a scheduled job, say). */
    public Long idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AppUserDetails p
                ? p.getUser().getId()
                : null;
    }

    public boolean isCustomer() {
        return require().getCustomerId() != null;
    }

    /** True when the caller holds POC_ASSIGN and is not a customer-scoped account. */
    public boolean canAssignPoc(com.geneinvoice.user.UserRepository userRepository) {
        User u = require();
        return u.getCustomerId() == null
                && userRepository.hasPrivilege(u.getId(), com.geneinvoice.privilege.Privileges.POC_ASSIGN);
    }
}
