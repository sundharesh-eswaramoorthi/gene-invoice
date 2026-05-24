package com.geneinvoice.auth;

import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        return new AppUserDetails(user, buildAuthorities(user));
    }

    public static List<GrantedAuthority> buildAuthorities(User user) {
        if (user.getRole() == null) return List.of();
        Stream<SimpleGrantedAuthority> roleAuth = Stream.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().getName()));
        Stream<SimpleGrantedAuthority> privs = user.getRole().getPrivileges().stream()
                .map(p -> new SimpleGrantedAuthority(p.getName()));
        return Stream.concat(roleAuth, privs).collect(Collectors.toList());
    }
}
