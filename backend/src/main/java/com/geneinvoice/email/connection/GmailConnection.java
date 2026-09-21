package com.geneinvoice.email.connection;

import com.geneinvoice.email.transport.ConnectionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

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

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConnectionStatus status;

    @Column(name = "gmail_address", length = ADDRESS_MAX)
    private String gmailAddress;

    @Column(length = REASON_MAX)
    private String reason;

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_sync_error", length = REASON_MAX)
    private String lastSyncError;

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
