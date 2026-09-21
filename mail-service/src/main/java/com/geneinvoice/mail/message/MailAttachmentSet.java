package com.geneinvoice.mail.message;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * The set of files one email carries, as {@link MailMessage#getAttachmentSetId()} points at it. A
 * set is written once and never changed: it is what went out.
 *
 * <p>It is a set rather than a column on the copy because an email is saved as one copy per
 * recipient (M6), and its files are the same for all of them. Storing the bytes on the copy would
 * write a 17 MiB attachment once per recipient — five hundred times over, at the submit limit —
 * for one email. Here the copies of one email share one set, and so does every attempt: a retry
 * re-submits the same files, and {@link #digest} is what makes that the same set rather than
 * another copy of it.
 *
 * <p>{@link #digest} is a SHA-256 over the files' names, types and bytes in order, so two
 * submissions carrying the same files reuse one set. Sets are therefore never edited and never
 * deleted — another email may be pointing at this one. Nothing in this service deletes messages
 * either, so no set is ever orphaned; a future clean-up of old messages would have to leave a set
 * alone until no message points at it.
 */
@Entity
@Table(name = "mail_attachment_sets",
        uniqueConstraints = @UniqueConstraint(name = "uk_mail_attachment_set_digest", columnNames = "digest"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailAttachmentSet {

    /** SHA-256 as hex. */
    public static final int DIGEST_MAX = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = DIGEST_MAX)
    private String digest;

    /** The files' own bytes added up, which is what {@code mail.send.max-attachment-bytes} caps. */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
