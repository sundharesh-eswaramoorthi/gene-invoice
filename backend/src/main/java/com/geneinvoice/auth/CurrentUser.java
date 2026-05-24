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

    public boolean isCustomer() {
        return require().getCustomerId() != null;
    }
}
