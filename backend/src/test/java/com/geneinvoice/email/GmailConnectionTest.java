package com.geneinvoice.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.email.RecordingMailTransport.Mode;
import com.geneinvoice.email.connection.GmailConnection;
import com.geneinvoice.email.transport.ConnectionStatus;
import com.geneinvoice.email.transport.MailConnectException;
import com.geneinvoice.user.User;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Each internal user connects their own Gmail with three values (mail-service.md §5.6). */
class GmailConnectionTest extends EmailTestBase {

    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate transactions;

    private ResultActions connect(User caller, Object body) throws Exception {
        return mockMvc.perform(put("/api/me/gmail").with(as(caller))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private static Map<String, Object> values(String clientId, String clientSecret, String refreshToken) {
        return Map.of("clientId", clientId, "clientSecret", clientSecret, "refreshToken", refreshToken);
    }

    private JsonNode mine(User caller) throws Exception {
        return getOk("/api/me/gmail", caller);
    }

    @Test
    void connectingNeedsAllThreeValues() throws Exception {
        mailTransport.mode(Mode.SUCCESS);

        connect(sales, values(" ", "", "\t"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.clientId").value("Enter the client ID"))
                .andExpect(jsonPath("$.fieldErrors.clientSecret").value("Enter the client secret"))
                .andExpect(jsonPath("$.fieldErrors.refreshToken").value("Enter the refresh token"));
        connect(sales, Map.of("clientId", "123.apps.googleusercontent.com"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.clientId").doesNotExist())
                .andExpect(jsonPath("$.fieldErrors.refreshToken").value("Enter the refresh token"));
        // The service's limits, checked here so the words land under the field.
        connect(sales, values("c".repeat(301), "s".repeat(301), "t".repeat(2001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.clientId").value("The client ID is too long"))
                .andExpect(jsonPath("$.fieldErrors.clientSecret").value("The client secret is too long"))
                .andExpect(jsonPath("$.fieldErrors.refreshToken").value("The refresh token is too long"));
        assertThat(mailTransport.connectCalls()).isEmpty();
    }

    @Test
    void theThreeValuesGoToTheServiceTrimmedAndTheAppKeepsACopyOfTheOutcome() throws Exception {
        mailTransport.mode(Mode.SUCCESS);

        JsonNode connected = read(connect(sales, values(" 123.apps.googleusercontent.com ", " s3cret ", " 1//refresh "))
                .andExpect(status().isOk()));

        assertThat(mailTransport.connectCalls()).singleElement().satisfies(call -> {
            assertThat(call.userId()).isEqualTo(sales.getId());
            assertThat(call.name()).isEqualTo("SAM.SALES");
            assertThat(call.clientId()).isEqualTo("123.apps.googleusercontent.com");
            assertThat(call.clientSecret()).isEqualTo("s3cret");
            assertThat(call.refreshToken()).isEqualTo("1//refresh");
        });
        assertThat(connected.get("configured").asBoolean()).isTrue();
        assertThat(connected.get("status").asText()).isEqualTo("CONNECTED");
        assertThat(connected.get("gmailAddress").asText()).isEqualTo("user" + sales.getId() + "@gmail.com");
        assertThat(connected.get("clientId").asText()).isEqualTo("123.apps.googleusercontent.com");
        assertThat(connected.get("serviceError").isNull()).isTrue();
        assertThat(connected.toString()).doesNotContain("s3cret", "1//refresh");

        GmailConnection copy = gmailConnectionRepository.findById(sales.getId()).orElseThrow();
        assertThat(copy.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(copy.getGmailAddress()).isEqualTo("user" + sales.getId() + "@gmail.com");
        JsonNode seen = getOk("/api/users/" + sales.getId() + "/gmail", admin);
        assertThat(seen.get("status").asText()).isEqualTo("CONNECTED");
        assertThat(seen.get("gmailAddress").asText()).isEqualTo("user" + sales.getId() + "@gmail.com");
        assertThat(seen.get("updatedAt").isNull()).isFalse();
    }

    @Test
    void whatTheServiceOrGoogleSaidComesBackWithItsStatus() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        String refused = "Google did not accept the client ID and secret (invalid_client: The OAuth client was not found.).";

        mailTransport.failConnections(new MailConnectException(400, refused));
        connect(sales, values("id", "secret", "token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(refused));
        mailTransport.failConnections(new MailConnectException(502, "Could not reach Google: timed out"));
        connect(sales, values("id", "secret", "token"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.message").value("Could not reach Google: timed out"));
        mailTransport.failConnections(new MailConnectException(503, "Could not reach the mail service: refused"));
        connect(sales, values("id", "secret", "token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Could not reach the mail service: refused"));
        mockMvc.perform(delete("/api/me/gmail").with(as(sales))).andExpect(status().isServiceUnavailable());

        // Nothing was kept of a connection that did not happen.
        assertThat(gmailConnectionRepository.findById(sales.getId())).isEmpty();
    }

    @Test
    void withoutTheMailServiceNobodyCanConnect() throws Exception {
        JsonNode off = mine(sales);
        assertThat(off.get("configured").asBoolean()).isFalse();
        assertThat(off.get("status").asText()).isEqualTo("NOT_CONNECTED");

        connect(sales, values("id", "secret", "token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Email delivery is not configured (mail service)"));
    }

    @Test
    void theCallersConnectionIsAskedOfTheServiceAndOfTheAppsCopyWhenTheServiceCannotSay() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        assertThat(mine(sales).get("status").asText()).isEqualTo("NOT_CONNECTED");

        String reason = "Google no longer accepts this Gmail connection (invalid_grant: Token has been expired or revoked.). Reconnect Gmail.";
        mailTransport.hold(RecordingMailTransport.state(sales.getId(), "SAM.SALES", ConnectionStatus.NEEDS_RECONNECT,
                "sam@gmail.com", reason));
        JsonNode renew = mine(sales);
        assertThat(renew.get("status").asText()).isEqualTo("NEEDS_RECONNECT");
        assertThat(renew.get("reason").asText()).isEqualTo(reason);
        assertThat(renew.get("clientId").asText()).isEqualTo("client-" + sales.getId());
        assertThat(renew.get("connectedAt").asText()).isEqualTo("2026-09-20T10:00:00Z");
        assertThat(renew.get("lastSyncedAt").asText()).isEqualTo("2026-09-20T10:05:00Z");
        assertThat(gmailConnectionRepository.findById(sales.getId())).get()
                .satisfies(c -> assertThat(c.getStatus()).isEqualTo(ConnectionStatus.NEEDS_RECONNECT));

        mailTransport.failConnections(new MailConnectException(503, "Could not reach the mail service: refused"));
        JsonNode fromCopy = mine(sales);
        assertThat(fromCopy.get("configured").asBoolean()).isTrue();
        assertThat(fromCopy.get("status").asText()).isEqualTo("NEEDS_RECONNECT");
        assertThat(fromCopy.get("gmailAddress").asText()).isEqualTo("sam@gmail.com");
        assertThat(fromCopy.get("reason").asText()).isEqualTo(reason);
        assertThat(fromCopy.get("clientId").isNull()).isTrue();
        assertThat(fromCopy.get("serviceError").asText()).isEqualTo("Could not reach the mail service: refused");
    }

    @Test
    void disconnectingForgetsTheConnection() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        connect(sales, values("id", "secret", "token")).andExpect(status().isOk());

        mockMvc.perform(delete("/api/me/gmail").with(as(sales))).andExpect(status().isNoContent());

        assertThat(gmailConnectionRepository.findById(sales.getId())).get()
                .satisfies(c -> assertThat(c.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED));
        assertThat(mine(sales).get("status").asText()).isEqualTo("NOT_CONNECTED");
        assertThat(getOk("/api/users/" + sales.getId() + "/gmail", admin).get("status").asText())
                .isEqualTo("NOT_CONNECTED");
        // Nothing to disconnect is not an error.
        mockMvc.perform(delete("/api/me/gmail").with(as(collections))).andExpect(status().isNoContent());
    }

    @Test
    void disconnectingLeavesNoWordOfTheLastGmailCheckBehind() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        connect(sales, values("id", "secret", "token")).andExpect(status().isOk());
        // Reading the mailbox failed while the connection stood: the error is kept and shown.
        String failed = "Google no longer accepts this Gmail connection (invalid_grant: Token has been"
                + " expired or revoked.). Reconnect Gmail.";
        mailTransport.hold(RecordingMailTransport.state(sales.getId(), "SAM.SALES", ConnectionStatus.NEEDS_RECONNECT,
                "sam@gmail.com", failed, Instant.parse("2026-09-20T10:05:00Z"), failed));
        assertThat(mine(sales).get("lastSyncError").asText()).isEqualTo(failed);
        assertThat(getOk("/api/emails/delivery", sales).at("/gmail/lastSyncError").asText()).isEqualTo(failed);

        mockMvc.perform(delete("/api/me/gmail").with(as(sales))).andExpect(status().isNoContent());

        // Nothing is connected any more, so nothing is left to have failed: a stale error here would
        // tell someone with no connection that their last Gmail check failed — and this one would
        // even ask them to reconnect what they just removed.
        for (JsonNode answer : List.of(mine(sales), getOk("/api/emails/delivery", sales).get("gmail"))) {
            assertThat(answer.get("status").asText()).isEqualTo("NOT_CONNECTED");
            assertThat(answer.get("reason").isNull()).isTrue();
            assertThat(answer.get("lastSyncError").isNull()).isTrue();
            assertThat(answer.get("lastSyncedAt").isNull()).isTrue();
        }
        assertThat(gmailConnectionRepository.findById(sales.getId())).get().satisfies(copy -> {
            assertThat(copy.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
            assertThat(copy.getLastSyncError()).isNull();
            assertThat(copy.getLastSyncedAt()).isNull();
        });

        // The same for a copy left over from an older row: the service has no connection for them,
        // so the app forgets everything it kept of one.
        gmailConnectionRepository.save(GmailConnection.builder().userId(collections.getId())
                .status(ConnectionStatus.DISCONNECTED).gmailAddress("cara@gmail.com")
                .lastSyncedAt(Instant.parse("2026-09-20T10:05:00Z")).lastSyncError(failed).build());
        assertThat(mine(collections).get("lastSyncError").isNull()).isTrue();
        assertThat(gmailConnectionRepository.findById(collections.getId())).get()
                .satisfies(copy -> assertThat(copy.getLastSyncError()).isNull());
    }

    @Test
    void onlyInternalUsersWhoSendEmailConnectGmail() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        User viewer = user("vic.viewer", "VIEWER");

        for (User caller : List.of(acmeLogin, viewer)) {
            mockMvc.perform(get("/api/me/gmail").with(as(caller))).andExpect(status().isForbidden());
            connect(caller, values("id", "secret", "token")).andExpect(status().isForbidden());
        }
        assertThat(mailTransport.connectCalls()).isEmpty();
        // Disconnecting is for every internal user, so one who lost EMAIL_SEND can take their mailbox back.
        mockMvc.perform(delete("/api/me/gmail").with(as(viewer))).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/me/gmail").with(as(acmeLogin))).andExpect(status().isForbidden());
        assertThat(mailTransport.disconnectCalls()).containsExactly(viewer.getId());
    }

    @Test
    void someoneElsesConnectionIsForThoseWhoSeeUsers() throws Exception {
        gmailConnectionRepository.save(GmailConnection.builder().userId(collections.getId())
                .status(ConnectionStatus.NEEDS_RECONNECT).gmailAddress("cara@gmail.com").reason("Reconnect Gmail.")
                .connectedAt(Instant.parse("2026-09-20T10:00:00Z")).build());

        JsonNode cara = getOk("/api/users/" + collections.getId() + "/gmail", admin);
        assertThat(cara.get("status").asText()).isEqualTo("NEEDS_RECONNECT");
        assertThat(cara.get("gmailAddress").asText()).isEqualTo("cara@gmail.com");
        assertThat(cara.get("reason").asText()).isEqualTo("Reconnect Gmail.");
        assertThat(getOk("/api/users/" + acmeLogin.getId() + "/gmail", admin).get("status").asText())
                .isEqualTo("NOT_CONNECTED");

        mockMvc.perform(get("/api/users/999999/gmail").with(as(admin))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/users/" + collections.getId() + "/gmail").with(as(sales)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aFirstConnectSucceedsWhenTheServicesReportOfItIsSavedAtTheSameMoment() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        // The service's connection.status report makes the app's first copy while the answer to the
        // connect is on its way back: it has inserted the row, and not yet committed.
        Thread report = new Thread(() -> {
            try {
                transactions.executeWithoutResult(tx -> {
                    gmailConnectionRepository.saveAndFlush(GmailConnection.builder().userId(sales.getId())
                            .status(ConnectionStatus.CONNECTED).gmailAddress("user" + sales.getId() + "@gmail.com")
                            .build());
                    inserted.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Throwable t) {
                failures.add(t);
            }
        }, "connection-status-report");
        mailTransport.beforeConnect(() -> {
            report.start();
            try {
                assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // The report commits while this request is writing its own copy.
            Thread committer = new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                release.countDown();
            });
            committer.start();
        });

        try {
            connect(sales, values("id", "secret", "token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONNECTED"));
        } finally {
            release.countDown();
            report.join(10_000);
        }

        assertThat(failures).isEmpty();
        assertThat(gmailConnectionRepository.findById(sales.getId())).get().satisfies(c -> {
            assertThat(c.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
            assertThat(c.getGmailAddress()).isEqualTo("user" + sales.getId() + "@gmail.com");
        });
    }

    @Test
    void noDatabaseConnectionIsHeldWhileConnecting() throws Exception {
        mailTransport.mode(Mode.SUCCESS);
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        List<Integer> inUse = new CopyOnWriteArrayList<>();
        mailTransport.beforeConnect(() -> inUse.add(pool.getActiveConnections()));

        connect(sales, values("id", "secret", "token")).andExpect(status().isOk());

        // Connecting waits on Google; a pooled connection held meanwhile would be kept from the rest of the app.
        assertThat(inUse).containsExactly(0);
    }
}
