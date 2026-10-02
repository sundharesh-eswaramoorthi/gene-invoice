package com.geneinvoice.auth;

import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.region.RegionRights;
import com.geneinvoice.region.UserRegionGrantRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final UserRegionGrantRepository grantRepository;
    private final CustomerRepository customerRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Case-insensitively, matching the uniqueness rule: one account, whatever case it is
        // typed in, rather than "admin" signing in and "Admin" being refused (CP-06).
        User user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        return principalFor(user);
    }

    /**
     * The principal with everything it will be asked, loaded now: the principal outlives this transaction on
     * every request path (C-4), so its rights are plain data. One grants query for staff; one region lookup
     * for a customer login, whose region is read afresh each time so a region move is followed at once.
     */
    @Transactional(readOnly = true)
    public AppUserDetails principalFor(User user) {
        Set<String> role = user.getRole() == null ? Set.of()
                : user.getRole().getPrivileges().stream().map(Privilege::getName).collect(Collectors.toSet());
        RegionRights rights = user.getCustomerId() != null
                ? RegionRights.forCustomer(role, customerRepository.regionIdOf(user.getCustomerId()).orElse(null))
                : RegionRights.forStaff(role, grantRepository.rowsFor(user.getId()));
        return new AppUserDetails(user, buildAuthorities(user, rights), rights);
    }

    @Transactional(readOnly = true)
    public Optional<AppUserDetails> principalFor(Long userId) {
        return userRepository.findById(userId).map(this::principalFor);
    }

    /** The role, plus every privilege effective in at least one region: {@code @PreAuthorize} means "somewhere". */
    public static List<GrantedAuthority> buildAuthorities(User user, RegionRights rights) {
        if (user.getRole() == null) return List.of();
        Stream<SimpleGrantedAuthority> roleAuth = Stream.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().getName()));
        Stream<SimpleGrantedAuthority> privs = rights.effectiveAnywhere().stream().map(SimpleGrantedAuthority::new);
        return Stream.concat(roleAuth, privs).collect(Collectors.toList());
    }
}
