package com.geneinvoice.auth;

import com.geneinvoice.region.RegionGrants;
import com.geneinvoice.user.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

@Getter
public class AppUserDetails implements UserDetails {

    private final User user;
    private final Collection<? extends GrantedAuthority> authorities;
    /**
     * Parsed once, here, so "may I do this in THIS region?" is answered off the principal and
     * costs no SQL on any request. The constructor signature is deliberately unchanged, so
     * anything that rebuilds a principal from a User still compiles (B1).
     */
    private final RegionGrants regionGrants;

    public AppUserDetails(User user, Collection<? extends GrantedAuthority> authorities) {
        this.user = user;
        this.authorities = authorities;
        this.regionGrants = RegionGrants.of(user.getRegionGrants());
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return user.getPassword(); }
    @Override public String getUsername() { return user.getUsername(); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return user.isActive(); }
}
