package com.geneinvoice.auth;

import com.geneinvoice.auth.dto.LoginRequest;
import com.geneinvoice.auth.dto.LoginResponse;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Passwords;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.region.RegionDtos;
import com.geneinvoice.region.RegionGrants;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUser currentUser;
    private final RegionRepository regionRepository;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password())
            );
            AppUserDetails principal = (AppUserDetails) auth.getPrincipal();
            User u = principal.getUser();
            // The token carries the credential generation it was minted against, so a later
            // password change ends this session with it (AUTH-04).
            String token = jwtService.generateToken(u.getUsername(), jwtService.claimsFor(u));
            return new LoginResponse(
                    token,
                    jwtService.getExpirationMs(),
                    info(u)
            );
        } catch (BadCredentialsException ex) {
            throw new BadCredentialsException("Invalid username or password");
        }
    }

    @GetMapping("/me")
    public LoginResponse.UserInfo me() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails p)) {
            throw new BadCredentialsException("Not authenticated");
        }
        return info(p.getUser());
    }

    /**
     * One shape for both sign-in and /me, so the client can never be told two different things
     * about the same person. privileges is filtered through the authority set rather than read
     * straight off the role: a privilege exercisable in no region is not in the authority set, so
     * listing it here would offer a button the server refuses (B1).
     */
    private LoginResponse.UserInfo info(User u) {
        List<String> exercisable = AppUserDetailsService.buildAuthorities(u).stream()
                .map(GrantedAuthority::getAuthority).toList();
        List<String> privs = u.getRole().getPrivileges().stream()
                .map(Privilege::getName)
                // By name equality against the privilege list, never by stripping a "ROLE_"
                // prefix: ROLE_VIEW and ROLE_MANAGE are privileges of this application (B1).
                .filter(exercisable::contains)
                .toList();
        RegionGrants grants = RegionGrants.of(u.getRegionGrants());
        return new LoginResponse.UserInfo(u.getId(), u.getUsername(), u.getFullName(),
                u.getRole().getName(), privs, u.getCustomerId(),
                // Any wildcard right covers VIEW, so this is "holds a null-region grant" (B1).
                grants.allRegions(RegionRight.VIEW),
                RegionDtos.heldBy(grants, regionRepository));
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank String newPassword
    ) {}

    @PostMapping("/change-password")
    public Map<String, String> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        Passwords.require(req.newPassword());
        User u = currentUser.require();
        if (!passwordEncoder.matches(req.currentPassword(), u.getPassword())) {
            throw new BadRequestException("Current password is incorrect");
        }
        if (passwordEncoder.matches(req.newPassword(), u.getPassword())) {
            throw new BadRequestException("New password must differ from current");
        }
        u.setPassword(passwordEncoder.encode(req.newPassword()));
        // Every token minted against the old password stops working here. Changing a password is
        // the ordinary way to end a session somebody else may have taken, and it did nothing of
        // the sort while the old token kept full access for the rest of its life (AUTH-04).
        u.setCredentialsChangedAt(Instant.now());
        User saved = userRepository.save(u);
        return Map.of("status", "ok",
                "token", jwtService.generateToken(saved.getUsername(), jwtService.claimsFor(saved)));
    }
}
