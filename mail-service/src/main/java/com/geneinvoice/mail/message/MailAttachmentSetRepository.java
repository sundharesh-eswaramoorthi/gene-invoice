package com.geneinvoice.mail.message;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MailAttachmentSetRepository extends JpaRepository<MailAttachmentSet, Long> {

    /** The set holding exactly these files, if one was stored before (a retry, or a second copy). */
    Optional<MailAttachmentSet> findByDigest(String digest);
}
