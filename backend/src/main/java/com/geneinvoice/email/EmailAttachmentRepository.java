package com.geneinvoice.email;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface EmailAttachmentRepository extends JpaRepository<EmailAttachment, Long> {

    List<EmailAttachment> findByEmailIdOrderByIdAsc(Long emailId);

    /** A page of emails in one read, so the Email tab costs the same query whatever it lists. */
    List<EmailAttachment> findByEmailIdInOrderByIdAsc(Collection<Long> emailIds);
}
