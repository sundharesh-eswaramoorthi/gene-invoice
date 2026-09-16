package com.geneinvoice.email;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.email.EmailDtos.SendOutcome;
import com.geneinvoice.email.EmailDtos.SendRequest;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
import com.geneinvoice.setting.AppSetting;
import com.geneinvoice.setting.AppSettingKeys;
import com.geneinvoice.setting.AppSettingRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Send-time sender and recipient resolution, unit-level: the multi-role membership case the
 * integration suite cannot reach (a user holds exactly one role in this domain), plus the
 * named-user sender fallback the settled clarification assigns to the application-wide setting.
 */
class EmailRecipientResolutionTest {

    EmailRepository emailRepository;
    EmailRecipientRepository recipientRepository;
    EmailRecipientReadRepository readRepository;
    UserRepository userRepository;
    RoleRepository roleRepository;
    AppSettingRepository settingRepository;
    CustomerService customerService;
    CurrentUser currentUser;
    EmailService service;

    @BeforeEach
    void setUp() {
        emailRepository = mock(EmailRepository.class);
        recipientRepository = mock(EmailRecipientRepository.class);
        readRepository = mock(EmailRecipientReadRepository.class);
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        settingRepository = mock(AppSettingRepository.class);
        customerService = mock(CustomerService.class);
        InvoiceService invoiceService = mock(InvoiceService.class);
        currentUser = mock(CurrentUser.class);
        service = new EmailService(emailRepository, recipientRepository, readRepository,
                userRepository, roleRepository, settingRepository, customerService,
                invoiceService, currentUser);
    }

    // ---- EDGE2 / AC11: one recipient occurrence per selected role ----------------

    @Test
    void userInTwoSelectedToRolesAppearsOnceUnderEachSelectedRole() {
        Role roleA = Role.builder().id(1L).name("ROLE_A").build();
        Role roleB = Role.builder().id(2L).name("ROLE_B").build();
        // The same user is a member of BOTH selected roles.
        User shared = User.builder().id(9L).username("uma").fullName("UMA")
                .email("uma@test.local").role(roleA).active(true).build();
        User sender = User.builder().id(5L).username("sam").fullName("SAM")
                .email("sam@test.local").role(roleA).build();

        when(roleRepository.findById(1L)).thenReturn(Optional.of(roleA));
        when(roleRepository.findById(2L)).thenReturn(Optional.of(roleB));
        when(userRepository.findByRoleId(1L)).thenReturn(List.of(shared));
        when(userRepository.findByRoleId(2L)).thenReturn(List.of(shared));
        when(userRepository.findById(5L)).thenReturn(Optional.of(sender));
        when(customerService.get(11L)).thenReturn(mock(Customer.class));
        when(currentUser.require()).thenReturn(sender);
        when(emailRepository.save(any(Email.class))).thenAnswer(inv -> inv.getArgument(0));

        SendOutcome out = service.sendForCustomer(11L,
                new SendRequest(5L, null, null, List.of(1L, 2L), false, "Two roles", null));
        assertThat(out.created()).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailRecipient>> captor = ArgumentCaptor.forClass(List.class);
        verify(recipientRepository).saveAll(captor.capture());
        List<EmailRecipient> rowsForShared = captor.getValue().stream()
                .filter(r -> Long.valueOf(9L).equals(r.getUserId())).toList();
        assertThat(rowsForShared).hasSize(2);   // once under EACH selected role, not deduped
        assertThat(rowsForShared.stream().map(EmailRecipient::getRoleName))
                .containsExactlyInAnyOrder("ROLE_A", "ROLE_B");
        assertThat(rowsForShared).allMatch(r -> r.getKind() == EmailRecipientKind.ROLE);
    }

    // ---- Settled clarification: a named sender without an address uses the fallback ---------

    @Test
    void aNamedSenderWithoutAnAddressUsesTheConfiguredAdminFallback() {
        User sender = User.builder().id(5L).username("sam").fullName("SAM").build();
        User recipient = User.builder().id(9L).username("cara").fullName("CARA")
                .email("cara@test.local").active(true).build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(sender));
        when(userRepository.findById(9L)).thenReturn(Optional.of(recipient));
        when(settingRepository.findById(AppSettingKeys.ADMIN_EMAIL)).thenReturn(Optional.of(
                AppSetting.builder().key(AppSettingKeys.ADMIN_EMAIL)
                        .value("  ops@geneinvoice.test  ").build()));
        when(customerService.get(11L)).thenReturn(mock(Customer.class));
        when(currentUser.require()).thenReturn(sender);
        when(emailRepository.save(any(Email.class))).thenAnswer(inv -> inv.getArgument(0));

        SendOutcome out = service.sendForCustomer(11L,
                new SendRequest(5L, null, List.of(9L), null, false, "Hello", null));

        assertThat(out.created()).isTrue();
        ArgumentCaptor<Email> captor = ArgumentCaptor.forClass(Email.class);
        verify(emailRepository).save(captor.capture());
        // The stored snapshot names the user and carries the normalized fallback address.
        assertThat(captor.getValue().getSenderDisplay()).isEqualTo("SAM");
        assertThat(captor.getValue().getSenderAddress()).isEqualTo("ops@geneinvoice.test");
    }

    @Test
    void aNamedSenderWithoutAnAddressIsRefusedWhileTheFallbackIsUnset() {
        User sender = User.builder().id(5L).username("sam").fullName("SAM").build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(sender));
        when(settingRepository.findById(AppSettingKeys.ADMIN_EMAIL)).thenReturn(Optional.empty());

        // Only a send that needs the missing fallback is rejected: this one does.
        assertThatThrownBy(() -> service.sendForCustomer(11L,
                new SendRequest(5L, null, List.of(9L), null, false, "Hello", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("application-wide admin email");
        verify(emailRepository, never()).save(any());
        verify(recipientRepository, never()).saveAll(any());
    }
}
