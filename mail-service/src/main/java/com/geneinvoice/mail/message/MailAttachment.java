package com.geneinvoice.mail.message;

import jakarta.persistence.*;
import lombok.*;

/**
 * One file in a {@link MailAttachmentSet}, bytes and all. The bytes live here rather than on a
 * disk beside the service so that a restart, or a second instance, loses nothing: a copy queued
 * before the restart is retried afterwards and still has its files. An email whose files were kept
 * somewhere the retry could not reach would go out empty, which is the failure this table exists
 * to prevent.
 *
 * <p>{@code content} is a plain {@code byte[]} of unbounded length, which Hibernate maps to
 * {@code bytea} on Postgres and to a binary large object on H2 — not {@code @Lob}, which on
 * Postgres would make it an {@code oid} and move the bytes into the large-object table, out of
 * reach of an ordinary dump.
 */
@Entity
@Table(name = "mail_attachments", indexes = {
        @Index(name = "idx_mail_attachment_set", columnList = "set_id,ordinal")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailAttachment {

    public static final int FILENAME_MAX = 255;
    public static final int CONTENT_TYPE_MAX = 120;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "set_id", nullable = false)
    private Long setId;

    /** Where the file goes in the message, counted from zero. */
    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, length = FILENAME_MAX)
    private String filename;

    @Column(name = "content_type", nullable = false, length = CONTENT_TYPE_MAX)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = Integer.MAX_VALUE)
    private byte[] content;
}
