package com.geneinvoice.mail.message;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MailAttachmentRepository extends JpaRepository<MailAttachment, Long> {

    /** One set's files, in the order they go into the message. */
    List<MailAttachment> findBySetIdOrderByOrdinalAsc(Long setId);
}
