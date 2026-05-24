package com.geneinvoice.customer;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerRepository repository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public record CustomerDto(
            Long id, String name, String phone, String email,
            String address, BigDecimal creditBalance, String username
    ) {
        public static CustomerDto from(Customer c, String username) {
            return new CustomerDto(c.getId(), c.getName(), c.getPhone(), c.getEmail(),
                    c.getAddress(), c.getCreditBalance(), username);
        }
    }

    public record CustomerCreateRequest(
            @NotBlank String name,
            String phone,
            String email,
            String address,
            @NotBlank String username,
            @NotBlank String password
    ) {}

    public record CustomerUpdateRequest(
            @NotBlank String name,
            String phone,
            String email,
            String address,
            String password
    ) {}

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.CUSTOMER_VIEW + "')")
    public List<CustomerDto> list() {
        return repository.findAll().stream()
                .map(c -> CustomerDto.from(c, usernameFor(c.getId())))
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.CUSTOMER_VIEW + "')")
    public CustomerDto get(@PathVariable Long id) {
        Customer c = repository.findById(id).orElseThrow(() -> new NotFoundException("Customer not found"));
        return CustomerDto.from(c, usernameFor(c.getId()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.CUSTOMER_MANAGE + "')")
    @Transactional
    public CustomerDto create(@Valid @RequestBody CustomerCreateRequest in) {
        if (userRepository.existsByUsername(in.username())) {
            throw new BadRequestException("Username already taken");
        }
        Role customerRole = roleRepository.findByName("CUSTOMER")
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role not seeded"));

        Customer c = Customer.builder()
                .name(in.name()).phone(in.phone()).email(in.email()).address(in.address())
                .build();
        c = repository.save(c);

        User u = User.builder()
                .username(in.username())
                .email(in.email())
                .fullName(in.name())
                .password(passwordEncoder.encode(in.password()))
                .role(customerRole)
                .customerId(c.getId())
                .active(true)
                .build();
        userRepository.save(u);

        return CustomerDto.from(c, u.getUsername());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.CUSTOMER_MANAGE + "')")
    @Transactional
    public CustomerDto update(@PathVariable Long id, @Valid @RequestBody CustomerUpdateRequest in) {
        Customer c = repository.findById(id).orElseThrow(() -> new NotFoundException("Customer not found"));
        c.setName(in.name());
        c.setPhone(in.phone());
        c.setEmail(in.email());
        c.setAddress(in.address());
        Customer saved = repository.save(c);

        User linked = userRepository.findByCustomerId(id).orElse(null);
        if (linked != null) {
            linked.setFullName(in.name());
            linked.setEmail(in.email());
            if (in.password() != null && !in.password().isBlank()) {
                linked.setPassword(passwordEncoder.encode(in.password()));
            }
            userRepository.save(linked);
        }
        return CustomerDto.from(saved, linked == null ? null : linked.getUsername());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.CUSTOMER_MANAGE + "')")
    @Transactional
    public void delete(@PathVariable Long id) {
        userRepository.findByCustomerId(id).ifPresent(userRepository::delete);
        repository.deleteById(id);
    }

    private String usernameFor(Long customerId) {
        return userRepository.findByCustomerId(customerId).map(User::getUsername).orElse(null);
    }
}
