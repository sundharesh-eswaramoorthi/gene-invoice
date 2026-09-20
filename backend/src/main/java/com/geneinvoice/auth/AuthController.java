package com.geneinvoice.auth;

import com.geneinvoice.auth.dto.LoginRequest;
import com.geneinvoice.auth.dto.LoginResponse;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Passwords;
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

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password())
            );
            AppUserDetails principal = (AppUserDetails) auth.getPrincipal();
            User u = principal.getUser();
            List<String> privs = u.getRole().getPrivileges().stream().map(p -> p.getName()).toList();
            // The token carries the credential generation it was minted against, so a later
            // password change ends this session with it (AUTH-04).
            String token = jwtService.generateToken(u.getUsername(), jwtService.claimsFor(u));
            return new LoginResponse(
                    token,
                    jwtService.getExpirationMs(),
                    new LoginResponse.UserInfo(u.getId(), u.getUsername(), u.getFullName(),
                            u.getRole().getName(), privs, u.getCustomerId())
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
        User u = p.getUser();
        List<String> privs = u.getRole().getPrivileges().stream().map(pr -> pr.getName()).toList();
        return new LoginResponse.UserInfo(u.getId(), u.getUsername(), u.getFullName(),
                u.getRole().getName(), privs, u.getCustomerId());
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
        // Including the one in the caller's own hand: this session just proved the current
        // password, so it is handed a token of the new generation rather than being signed out
        // mid-change. Everyone else's token is now stale, which is the point.
        return Map.of("status", "ok",
                "token", jwtService.generateToken(saved.getUsername(), jwtService.claimsFor(saved)));
    }
}
