package com.geneinvoice.user;

import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public record UserDto(Long id, String username, String email, String fullName,
                          boolean active, String role, Long customerId) {
        static UserDto from(User u) {
            return new UserDto(u.getId(), u.getUsername(), u.getEmail(), u.getFullName(),
                    u.isActive(), u.getRole() == null ? null : u.getRole().getName(),
                    u.getCustomerId());
        }
    }

    public record CreateUserRequest(
            @NotBlank String username,
            String email,
            String fullName,
            @NotBlank String password,
            @NotNull Long roleId,
            Boolean active
    ) {}

    public record UpdateUserRequest(
            String email,
            String fullName,
            String password,
            Long roleId,
            Boolean active
    ) {}

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.USER_VIEW + "')")
    public List<UserDto> list() {
        return userRepository.findAll().stream().map(UserDto::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.USER_VIEW + "')")
    public UserDto get(@PathVariable Long id) {
        return userRepository.findById(id).map(UserDto::from)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.USER_MANAGE + "')")
    public UserDto create(@Valid @RequestBody CreateUserRequest in) {
        if (userRepository.existsByUsername(in.username())) {
            throw new BadRequestException("Username already exists");
        }
        Role role = roleRepository.findById(in.roleId())
                .orElseThrow(() -> new NotFoundException("Role not found"));
        if ("CUSTOMER".equalsIgnoreCase(role.getName())) {
            throw new BadRequestException("Use POST /api/customers to create a customer account");
        }
        User u = User.builder()
                .username(in.username())
                .email(in.email())
                .fullName(in.fullName())
                .password(passwordEncoder.encode(in.password()))
                .role(role)
                .active(in.active() == null || in.active())
                .build();
        return UserDto.from(userRepository.save(u));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.USER_MANAGE + "')")
    public UserDto update(@PathVariable Long id, @RequestBody UpdateUserRequest in) {
        User u = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        if (in.email() != null) u.setEmail(in.email());
        if (in.fullName() != null) u.setFullName(in.fullName());
        if (in.password() != null && !in.password().isBlank()) {
            u.setPassword(passwordEncoder.encode(in.password()));
        }
        if (in.roleId() != null) {
            Role role = roleRepository.findById(in.roleId())
                    .orElseThrow(() -> new NotFoundException("Role not found"));
            u.setRole(role);
        }
        if (in.active() != null) u.setActive(in.active());
        return UserDto.from(userRepository.save(u));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.USER_MANAGE + "')")
    public void delete(@PathVariable Long id) {
        userRepository.deleteById(id);
    }
}
