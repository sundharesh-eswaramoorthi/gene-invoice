package com.geneinvoice.email;

import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.document.Document;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One document that was attached to one email (E17). The email and the document are referenced by
 * id only, as an email references its record and its people, so deleting the document later never
 * breaks the history; the name, type and size are snapshots taken when the email was sent, which
 * is why they are stored here rather than read back off {@link Document}. An attachment is never
 * updated — it is what went out — so there is no {@code updatedAt} and no {@code @PreUpdate}.
 *
 * <p>These rows are both the record and the source: the Email tab reads them to show what went
 * with the email, and the dispatcher reads them to load each document's bytes for the submission
 * that carries the files out (E18). The filename, type and size are snapshotted here rather than
 * read back through the document, so an email still says truthfully what it carried after the
 * document itself is renamed or deleted.
 */
@Entity
@Table(name = "email_attachments", indexes = {
        @Index(name = "idx_email_attachment_email", columnList = "email_id"),
        @Index(name = "idx_email_attachment_document", columnList = "document_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email_id", nullable = false)
    private Long emailId;

    /** The document it came from, so the Email tab can offer its download, which checks its own visibility (D7). */
    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /** What the file was called when it was attached; the document may be renamed or deleted since. */
    @Column(nullable = false, length = FieldLimits.DOCUMENT_FILENAME)
    private String filename;

    @Column(name = "content_type", nullable = false, length = Document.CONTENT_TYPE_MAX)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
