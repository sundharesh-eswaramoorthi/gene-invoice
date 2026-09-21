package com.geneinvoice.mail.connection;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "mail_connections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailConnection {

    public static final int OWNER_REF_MAX = 64;
    public static final int OWNER_NAME_MAX = 200;
    public static final int ADDRESS_MAX = 320;
    public static final int CLIENT_ID_MAX = 300;
    public static final int REASON_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_ref", nullable = false, unique = true, length = OWNER_REF_MAX)
    private String ownerRef;

    @Column(name = "owner_name", nullable = false, length = OWNER_NAME_MAX)
    private String ownerName;

    @Column(name = "gmail_address", length = ADDRESS_MAX)
    private String gmailAddress;

    @Column(name = "client_id", nullable = false, length = CLIENT_ID_MAX)
    private String clientId;

    @Column(name = "client_secret_enc", length = 1000)
    private String clientSecretEnc;

    @Column(name = "refresh_token_enc", length = 4000)
    private String refreshTokenEnc;

    @Column(nullable = false, length = 1000)
    private String scopes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConnectionStatus status;

    @Column(name = "status_reason", length = REASON_MAX)
    private String statusReason;

    @Column(name = "history_id", length = 40)
    private String historyId;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_sync_error", length = REASON_MAX)
    private String lastSyncError;

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
