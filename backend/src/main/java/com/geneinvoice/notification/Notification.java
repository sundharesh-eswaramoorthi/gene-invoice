package com.geneinvoice.notification;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notif_user", columnList = "user_id"),
        @Index(name = "idx_notif_user_read", columnList = "user_id,is_read")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 40)
    private String type;

    public static final int TITLE_MAX = 200;
    public static final int MESSAGE_MAX = 1000;

    @Column(nullable = false, length = TITLE_MAX)
    private String title;

    @Column(length = MESSAGE_MAX)
    private String message;

    @Column(length = 300)
    private String link;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean read = false;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
