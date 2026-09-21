package com.geneinvoice.mail.connection;

import com.geneinvoice.mail.MailText;
import com.geneinvoice.mail.config.ApiException;
import com.geneinvoice.mail.config.InvalidFieldsException;
import com.geneinvoice.mail.events.EventRecorder;
import com.geneinvoice.mail.gmail.GmailApiException;
import com.geneinvoice.mail.gmail.GmailClient;
import com.geneinvoice.mail.gmail.GoogleAuthException;
import com.geneinvoice.mail.gmail.GoogleTokens;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class ConnectionService {

    static final int CLIENT_SECRET_MAX = 300;
    static final int REFRESH_TOKEN_MAX = 2000;
    static final String GOOGLE_PREFIX = "https://www.googleapis.com/auth/";
    /** Any of these lets a token send mail (M3). */
    static final Set<String> SEND_SCOPES = Set.of(GOOGLE_PREFIX + "gmail.send", GOOGLE_PREFIX + "gmail.compose",
            GOOGLE_PREFIX + "gmail.modify", "https://mail.google.com/");
    static final Set<String> READ_SCOPES = Set.of(GOOGLE_PREFIX + "gmail.readonly", GOOGLE_PREFIX + "gmail.modify",
            "https://mail.google.com/");

    private final MailConnectionRepository connections;
    private final GoogleTokens tokens;
    private final GmailClient gmail;
    private final SecretBox secrets;
    private final EventRecorder events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ConnectionService(MailConnectionRepository connections, GoogleTokens tokens, GmailClient gmail,
                             SecretBox secrets, EventRecorder events, TransactionTemplate transactions, Clock clock) {
        this.connections = connections;
        this.tokens = tokens;
        this.gmail = gmail;
        this.secrets = secrets;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    public ConnectionDto connect(String ownerRef, ConnectRequest request) {
        String owner = checkedOwnerRef(ownerRef);
        String clientId = trimmed(request == null ? null : request.clientId());
        String clientSecret = trimmed(request == null ? null : request.clientSecret());
        String refreshToken = trimmed(request == null ? null : request.refreshToken());
        Map<String, String> invalid = new LinkedHashMap<>();
        check(invalid, "clientId", clientId, "Enter the client ID", MailConnection.CLIENT_ID_MAX, "The client ID is too long");
        check(invalid, "clientSecret", clientSecret, "Enter the client secret", CLIENT_SECRET_MAX,
                "The client secret is too long");
        check(invalid, "refreshToken", refreshToken, "Enter the refresh token", REFRESH_TOKEN_MAX,
                "The refresh token is too long");
        if (!invalid.isEmpty()) throw new InvalidFieldsException(invalid);
        String ownerName = MailText.fit(trimmed(request.ownerName()), MailConnection.OWNER_NAME_MAX);

        Instant grantedAt = clock.instant();
        GoogleTokens.Grant grant;
        List<String> scopes;
        GmailClient.Profile profile;
        try {
            grant = tokens.refresh(clientId, clientSecret, refreshToken);
            scopes = tokens.scopes(grant);
            String missing = missingAbility(scopes);
            if (missing != null) {
                throw ApiException.badRequest("This refresh token cannot " + missing + ". Make a new one with the"
                        + " scopes https://www.googleapis.com/auth/gmail.send and https://www.googleapis.com/auth/gmail.readonly.");
            }
            profile = gmail.profile(grant.accessToken());
        } catch (GoogleAuthException e) {
            throw ApiException.badRequest(e.connectMessage());
        } catch (GmailApiException e) {
            throw new ApiException(refusal(e) ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        if (profile == null || MailText.blank(profile.emailAddress())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Unexpected response from Gmail: the profile names no account");
        }

        String sealedSecret = secrets.seal(clientSecret);
        String sealedToken = secrets.seal(refreshToken);
        String gmailAddress = MailText.fit(profile.emailAddress().trim().toLowerCase(Locale.ROOT), MailConnection.ADDRESS_MAX);
        MailConnection saved = transactions.execute(status -> {
            Instant now = clock.instant();
            MailConnection c = connections.findByOwnerRef(owner).orElseGet(() -> MailConnection.builder()
                    .ownerRef(owner)
                    .createdAt(now)
                    .build());
            if (c.getHistoryId() == null || !gmailAddress.equals(c.getGmailAddress())) {
                c.setHistoryId(profile.historyId());
            }
            c.setOwnerName(ownerName != null ? ownerName : c.getOwnerName() != null ? c.getOwnerName() : owner);
            c.setGmailAddress(gmailAddress);
            c.setClientId(clientId);
            c.setClientSecretEnc(sealedSecret);
            c.setRefreshTokenEnc(sealedToken);
            c.setScopes(MailText.fit(String.join(" ", scopes), 1000));
            c.setStatus(ConnectionStatus.CONNECTED);
            c.setStatusReason(null);
            c.setLastSyncedAt(null);
            c.setLastSyncError(null);
            c.setConnectedAt(now);
            c.setUpdatedAt(now);
            MailConnection stored = connections.saveAndFlush(c);
            events.record(EventRecorder.CONNECTION_STATUS, owner, null, ConnectionDto.of(stored));
            return stored;
        });
        tokens.forget(saved.getId());
        tokens.remember(saved, grant, grantedAt);
        log.info("Gmail connected for {} as {}", owner, saved.getGmailAddress());
        return ConnectionDto.of(saved);
    }

    public ConnectionDto get(String ownerRef) {
        return transactions.execute(status -> connections.findByOwnerRef(ownerRef).map(ConnectionDto::of))
                .orElseThrow(() -> ApiException.notFound("No Gmail connection for " + ownerRef));
    }

    public List<ConnectionDto> list() {
        return transactions.execute(status -> connections.findAllByOrderByOwnerNameAscIdAsc().stream()
                .map(ConnectionDto::of).toList());
    }

    public Optional<MailConnection> byOwner(String ownerRef) {
        return transactions.execute(status -> connections.findByOwnerRef(ownerRef));
    }

    public void disconnect(String ownerRef) {
        MailConnection current = byOwner(ownerRef).orElse(null);
        if (current == null) return;
        if (current.getRefreshTokenEnc() != null) {
            try {
                tokens.revoke(secrets.open(current.getRefreshTokenEnc()));
            } catch (SecretBox.UnreadableSecretException | GmailApiException e) {
                log.warn("Could not revoke the Gmail refresh token of {} at Google: {}", ownerRef, e.getMessage());
            }
        }
        transactions.executeWithoutResult(status -> {
            MailConnection c = connections.findByOwnerRef(ownerRef).orElse(null);
            if (c == null) return;
            boolean changed = c.getStatus() != ConnectionStatus.DISCONNECTED;
            if (!changed && c.getClientSecretEnc() == null && c.getRefreshTokenEnc() == null
                    && c.getLastSyncedAt() == null && c.getLastSyncError() == null) {
                return;
            }
            c.setClientSecretEnc(null);
            c.setRefreshTokenEnc(null);
            c.setStatus(ConnectionStatus.DISCONNECTED);
            c.setStatusReason(null);
            c.setLastSyncedAt(null);
            c.setLastSyncError(null);
            c.setUpdatedAt(clock.instant());
            connections.save(c);
            if (changed) events.record(EventRecorder.CONNECTION_STATUS, c.getOwnerRef(), null, ConnectionDto.of(c));
        });
        tokens.forget(current.getId());
        log.info("Gmail disconnected for {}", ownerRef);
    }

    public boolean needsReconnect(MailConnection seen, GoogleAuthException refusal) {
        tokens.forget(seen.getId());
        Boolean changed = transactions.execute(status -> {
            MailConnection c = connections.findByIdForUpdate(seen.getId()).orElse(null);
            if (c == null || !Objects.equals(c.getVersion(), seen.getVersion())
                    || c.getStatus() != ConnectionStatus.CONNECTED) {
                return false;
            }
            c.setStatus(ConnectionStatus.NEEDS_RECONNECT);
            c.setStatusReason(MailText.fit(refusal.reconnectReason(), MailConnection.REASON_MAX));
            c.setUpdatedAt(clock.instant());
            connections.save(c);
            events.record(EventRecorder.CONNECTION_STATUS, c.getOwnerRef(), null, ConnectionDto.of(c));
            return true;
        });
        if (Boolean.TRUE.equals(changed)) {
            log.warn("The Gmail connection of {} needs renewing: {}", seen.getOwnerRef(), refusal.reconnectReason());
        }
        return Boolean.TRUE.equals(changed);
    }

    /** "send mail", "read mail", "send or read mail", or null when the scopes allow both (M3). */
    static String missingAbility(Collection<String> scopes) {
        boolean send = scopes.stream().anyMatch(SEND_SCOPES::contains);
        boolean read = scopes.stream().anyMatch(READ_SCOPES::contains);
        if (send && read) return null;
        if (read) return "send mail";
        if (send) return "read mail";
        return "send or read mail";
    }

    private static boolean refusal(GmailApiException e) {
        return !e.isTransientFailure() && e.status() >= 400 && e.status() < 500;
    }

    private static String checkedOwnerRef(String ownerRef) {
        String owner = trimmed(ownerRef);
        if (owner == null) throw new InvalidFieldsException(Map.of("ownerRef", "Enter the owner"));
        if (owner.length() > MailConnection.OWNER_REF_MAX) {
            throw new InvalidFieldsException(Map.of("ownerRef", "The owner reference is too long"));
        }
        return owner;
    }

    private static void check(Map<String, String> invalid, String field, String value, String missing, int max,
                              String tooLong) {
        if (value == null) invalid.put(field, missing);
        else if (value.length() > max) invalid.put(field, tooLong);
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
