package com.geneinvoice.auth;

import com.geneinvoice.region.RegionRights;
import com.geneinvoice.user.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

@Getter
public class AppUserDetails implements UserDetails {

    private final User user;
    private final Collection<? extends GrantedAuthority> authorities;
    /** Plain data built with the principal, so asking about a region needs neither SQL nor a session. */
    private final RegionRights rights;

    public AppUserDetails(User user, Collection<? extends GrantedAuthority> authorities, RegionRights rights) {
        this.user = user;
        this.authorities = authorities;
        this.rights = rights;
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return user.getPassword(); }
    @Override public String getUsername() { return user.getUsername(); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return user.isActive(); }
}
