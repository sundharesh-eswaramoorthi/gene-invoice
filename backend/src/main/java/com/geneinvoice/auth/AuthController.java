package com.geneinvoice.auth;

import com.geneinvoice.auth.dto.LoginRequest;
import com.geneinvoice.auth.dto.LoginResponse;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Passwords;
import com.geneinvoice.common.scope.AsOfIgnored;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.RegionRights;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@AsOfIgnored
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUser currentUser;
    private final RegionRepository regionRepository;
    private final Clock clock;

    @PostMapping("/login")
    @AsOfIgnored
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
                    userInfo(principal)
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
        return userInfo(p);
    }

    /**
     * What the caller may do, as the server will judge it: the privileges effective in at least one region,
     * and per granted region what is effective there. A customer login lists no regions.
     */
    private LoginResponse.UserInfo userInfo(AppUserDetails principal) {
        User u = principal.getUser();
        RegionRights rights = principal.getRights();
        List<LoginResponse.RegionAccessDto> regions = rights.isCustomer() ? List.of()
                : regionRepository.findAllById(rights.grantedRegions()).stream()
                        .sorted(Comparator.comparing(Region::getCode))
                        .map(r -> new LoginResponse.RegionAccessDto(r.getId(), r.getCode(), r.getName(),
                                r.isActive(), r.isDefaultRegion(),
                                rights.grantIn(r.getId()).map(g -> g.level().name()).orElse(null),
                                rights.grantIn(r.getId()).map(g -> g.scope().name()).orElse(null),
                                List.copyOf(rights.effectiveIn(r.getId()))))
                        .toList();
        return new LoginResponse.UserInfo(u.getId(), u.getUsername(), u.getFullName(),
                u.getRole().getName(), List.copyOf(rights.effectiveAnywhere()), u.getCustomerId(), regions);
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank String newPassword
    ) {}

    @PostMapping("/change-password")
    @AsOfIgnored
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
        u.setCredentialsChangedAt(clock.instant());
        // credentials_set_by_user_id is kept: whoever set the old password knows it and may be the one
        // changing it here, and clearing it would let them approve their own proposal as the customer (SC-10).
        User saved = userRepository.save(u);
        return Map.of("status", "ok",
                "token", jwtService.generateToken(saved.getUsername(), jwtService.claimsFor(saved)));
    }
}
