package com.geneinvoice.email;

import com.geneinvoice.email.RecordingMailTransport.Mode;
import com.geneinvoice.email.connection.GmailDisconnects;
import com.geneinvoice.email.transport.ConnectionStatus;
import com.geneinvoice.email.transport.MailConnectException;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GmailOffboardingTest extends EmailTestBase {

    @Autowired GmailDisconnects gmailDisconnects;
    @Autowired PrivilegeRepository privilegeRepository;

    @BeforeEach
    void serviceUp() {
        mailTransport.mode(Mode.SUCCESS);
    }

    private void connected(User u) throws Exception {
        mockMvc.perform(put("/api/me/gmail").with(as(u)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("clientId", "id-" + u.getId(), "clientSecret", "secret", "refreshToken", "token"))))
                .andExpect(status().isOk());
        assertThat(mailTransport.held(u.getId()).status()).isEqualTo(ConnectionStatus.CONNECTED);
        gmailConnectionRepository.findById(u.getId()).ifPresent(copy -> {
            copy.setLastSyncedAt(Instant.parse("2026-09-20T10:05:00Z"));
            copy.setLastSyncError("Gmail is unavailable (500): Backend Error");
            gmailConnectionRepository.save(copy);
        });
    }

    private ResultActions updateUser(long id, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put("/api/users/" + id).with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private void assertDisconnected(User u) {
        assertThat(mailTransport.held(u.getId()).status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(gmailConnectionRepository.findById(u.getId())).get().satisfies(c -> {
            assertThat(c.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
            assertThat(c.getDisconnectRequestedAt()).isNull();
            assertThat(c.getReason()).isNull();
            assertThat(c.getLastSyncedAt()).isNull();
            assertThat(c.getLastSyncError()).isNull();
        });
    }

    private void assertStillConnected(User u) {
        assertThat(mailTransport.held(u.getId()).status()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(mailTransport.disconnectCalls()).doesNotContain(u.getId());
    }

    @Test
    void deactivatingSomeoneRemovesTheirGmailConnection() throws Exception {
        connected(collections);
        connected(sales);

        updateUser(collections.getId(), Map.of("active", false)).andExpect(status().isOk());

        assertDisconnected(collections);
        assertStillConnected(sales);
        assertThat(getOk("/api/users/" + collections.getId() + "/gmail", admin).get("status").asText())
                .isEqualTo("NOT_CONNECTED");
    }

    @Test
    void movingSomeoneToARoleThatDoesNotSendEmailRemovesTheirs() throws Exception {
        connected(collections);

        updateUser(collections.getId(), Map.of("fullName", "Cara C.")).andExpect(status().isOk());
        assertStillConnected(collections);
        updateUser(collections.getId(), Map.of("roleId", role("VIEWER").getId())).andExpect(status().isOk());

        assertDisconnected(collections);
    }

    @Test
    void deletingSomeoneRemovesTheirsAndTheAppsCopyOfIt() throws Exception {
        User leaver = user("lee.leaver", "SALES_POC");
        connected(leaver);

        mockMvc.perform(delete("/api/users/" + leaver.getId()).with(as(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true));

        assertThat(mailTransport.held(leaver.getId()).status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(gmailConnectionRepository.findById(leaver.getId())).isEmpty();
    }

    @Test
    void someoneDeactivatedInsteadOfDeletedLosesTheirsToo() throws Exception {
        connected(sales);
        invoice(acme, sales);

        mockMvc.perform(delete("/api/users/" + sales.getId()).with(as(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deactivated").value(true));

        assertDisconnected(sales);
    }

    @Test
    void aBulkDeactivationRemovesEachOnes() throws Exception {
        connected(collections);
        connected(success);
        connected(sales);

        mockMvc.perform(post("/api/users/bulk")
                        .with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "DEACTIVATE", "ids", List.of(collections.getId(), success.getId())))))
                .andExpect(status().isOk());

        assertDisconnected(collections);
        assertDisconnected(success);
        assertStillConnected(sales);
    }

    @Test
    void takingEmailSendFromARoleRemovesItsHoldersConnections() throws Exception {
        Role mailer = roleRepository.findByName("OFFBOARDING_MAILER").orElseGet(() -> roleRepository.save(
                Role.builder().name("OFFBOARDING_MAILER").description("Sends email").build()));
        mailer.setPrivileges(privileges(Privileges.EMAIL_VIEW, Privileges.EMAIL_SEND));
        roleRepository.save(mailer);
        User holder = userRepository.save(User.builder().username("mo.mailer").email("mo@test.local").fullName("Mo")
                .password(passwordEncoder.encode("password")).role(mailer).active(true).build());
        connected(holder);
        connected(sales);

        mockMvc.perform(put("/api/roles/" + mailer.getId()).with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "OFFBOARDING_MAILER", "privileges", List.of(Privileges.EMAIL_VIEW)))))
                .andExpect(status().isOk());

        assertDisconnected(holder);
        assertStillConnected(sales);
    }

    @Test
    void aRemovalTheServiceCouldNotTakeIsAskedAgainBySweeper() throws Exception {
        connected(collections);
        mailTransport.failConnections(new MailConnectException(503, "Could not reach the mail service: refused"));

        updateUser(collections.getId(), Map.of("active", false)).andExpect(status().isOk());

        assertThat(userRepository.findById(collections.getId()).orElseThrow().isActive()).isFalse();
        assertThat(mailTransport.held(collections.getId()).status()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(gmailConnectionRepository.findById(collections.getId())).get()
                .satisfies(c -> assertThat(c.getDisconnectRequestedAt()).isNotNull());
        gmailDisconnects.sweep();
        assertThat(mailTransport.held(collections.getId()).status()).isEqualTo(ConnectionStatus.CONNECTED);

        mailTransport.failConnections(null);
        gmailDisconnects.sweep();

        assertDisconnected(collections);
    }

    @Test
    void someoneBackBeforeTheSweeperAskedKeepsTheirConnection() throws Exception {
        connected(collections);
        mailTransport.failConnections(new MailConnectException(503, "Could not reach the mail service: refused"));
        updateUser(collections.getId(), Map.of("active", false)).andExpect(status().isOk());
        updateUser(collections.getId(), Map.of("active", true)).andExpect(status().isOk());
        mailTransport.failConnections(null);
        int asked = mailTransport.disconnectCalls().size();

        gmailDisconnects.sweep();

        assertThat(mailTransport.disconnectCalls()).hasSize(asked);
        assertThat(mailTransport.held(collections.getId()).status()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(gmailConnectionRepository.findById(collections.getId())).get()
                .satisfies(c -> assertThat(c.getDisconnectRequestedAt()).isNull());
    }

    @Test
    void someoneWhoNoLongerSendsEmailCanStillDisconnectTheirOwn() throws Exception {
        User demoted = user("dee.demoted", "SALES_POC");
        connected(demoted);
        User fresh = userRepository.findById(demoted.getId()).orElseThrow();
        fresh.setRole(role("VIEWER"));
        userRepository.save(fresh);

        mockMvc.perform(delete("/api/me/gmail").with(as(demoted))).andExpect(status().isNoContent());

        assertDisconnected(demoted);
        mockMvc.perform(delete("/api/me/gmail").with(as(acmeLogin))).andExpect(status().isForbidden());
    }

    @Test
    void whoeverManagesUsersCanDisconnectSomeoneElses() throws Exception {
        connected(collections);

        mockMvc.perform(delete("/api/users/" + collections.getId() + "/gmail").with(as(sales)))
                .andExpect(status().isForbidden());
        assertStillConnected(collections);

        mockMvc.perform(delete("/api/users/" + collections.getId() + "/gmail").with(as(admin)))
                .andExpect(status().isNoContent());
        assertDisconnected(collections);

        mockMvc.perform(delete("/api/users/999999/gmail").with(as(admin))).andExpect(status().isNotFound());
        mailTransport.failConnections(new MailConnectException(503, "Could not reach the mail service: refused"));
        mockMvc.perform(delete("/api/users/" + sales.getId() + "/gmail").with(as(admin)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Could not reach the mail service: refused"));
    }

    @Test
    void withoutTheMailServiceNothingIsMarkedForSomeoneWhoNeverConnected() throws Exception {
        mailTransport.mode(Mode.NOT_CONFIGURED);

        updateUser(collections.getId(), Map.of("active", false)).andExpect(status().isOk());

        assertThat(gmailConnectionRepository.findById(collections.getId())).isEmpty();
        assertThat(mailTransport.disconnectCalls()).isEmpty();
    }

    private Set<Privilege> privileges(String... names) {
        Set<Privilege> found = new HashSet<>();
        for (String name : names) found.add(privilegeRepository.findByName(name).orElseThrow());
        return found;
    }
}
