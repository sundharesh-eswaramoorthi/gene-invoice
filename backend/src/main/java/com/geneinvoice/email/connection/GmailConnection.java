package com.geneinvoice.email.connection;

import com.geneinvoice.email.transport.ConnectionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * The app's copy of one user's Gmail connection at the mail service, so the compose form and the
 * user page can say who is connected without asking the service. Written from connect, disconnect
 * and look-up answers, and from the service's {@code connection.status} reports. It holds no secret.
 */
@Entity
@Table(name = "gmail_connections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GmailConnection {

    public static final int ADDRESS_MAX = 320;
    public static final int REASON_MAX = 1000;

    /** The backend's user id, which is the connection's owner at the service. */
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConnectionStatus status;

    @Column(name = "gmail_address", length = ADDRESS_MAX)
    private String gmailAddress;

    /** Why the connection needs renewing. */
    @Column(length = REASON_MAX)
    private String reason;

    @Column(name = "connected_at")
    private Instant connectedAt;

    /** The last read of the mailbox that finished, as last reported. */
    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_sync_error", length = REASON_MAX)
    private String lastSyncError;

    /**
     * Set when the user was deleted, deactivated or lost EMAIL_SEND, so their connection at the mail
     * service must go: until the service confirms it has, the email sweeper asks again
     * ({@link GmailDisconnects}). Null otherwise.
     */
    @Column(name = "disconnect_requested_at")
    private Instant disconnectRequestedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
