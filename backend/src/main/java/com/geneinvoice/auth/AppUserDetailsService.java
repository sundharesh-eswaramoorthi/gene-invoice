package com.geneinvoice.auth;

import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.region.RegionGrants;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.RegionRights;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Case-insensitively, matching the uniqueness rule: one account, whatever case it is
        // typed in, rather than "admin" signing in and "Admin" being refused (CP-06).
        User user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        return new AppUserDetails(user, buildAuthorities(user));
    }

    /**
     * A pure function of the User, which is why the test base's actAs/as can call it directly and
     * why signing in costs no extra query: the grants arrive with the principal (B1).
     */
    public static List<GrantedAuthority> buildAuthorities(User user) {
        if (user.getRole() == null) return List.of();
        RegionGrants grants = RegionGrants.of(user.getRegionGrants());
        // A customer login's reach is its own account, decided by customer_id and the POC book;
        // it holds no region grants by design, so the drop rule below would take DISPUTE_CREATE,
        // EMAIL_SEND and DOCUMENT_MANAGE off every customer and end self-service. Regions neither
        // widen nor narrow a customer, so a customer keeps every privilege its role gives (B1).
        boolean regionGated = user.getCustomerId() == null;
        List<GrantedAuthority> out = new ArrayList<>();
        out.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().getName()));
        for (Privilege p : user.getRole().getPrivileges()) {
            // A privilege you can exercise in no region is not in the authority set, so
            // hasAuthority('X') keeps its exact text and means "may I do X" — now read as
            // "somewhere" — and all 109 @PreAuthorize annotations are correct unchanged.
            // A caller with NO grant at all keeps its view-level privileges, so it meets the empty
            // list and the "regionId:isEmpty:" chip rather than a 403 — the ScopeResolver.nothing()
            // shape (B1, AUTH-08).
            // needed() is asked on every login for every privilege, so a privilege nobody
            // classified breaks authentication loudly instead of quietly granting it everywhere.
            RegionRight needed = RegionRights.needed(p.getName());
            boolean keep = needed == null
                    || !regionGated
                    || (grants.isEmpty() ? needed == RegionRight.VIEW : grants.holdsAnywhere(needed));
            if (keep) out.add(new SimpleGrantedAuthority(p.getName()));
        }
        return out;
    }
}
