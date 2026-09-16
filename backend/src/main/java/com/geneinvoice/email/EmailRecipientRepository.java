package com.geneinvoice.email;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface EmailRecipientRepository extends JpaRepository<EmailRecipient, Long> {
    List<EmailRecipient> findByEmail_Id(Long emailId);
    List<EmailRecipient> findByEmail_IdIn(Collection<Long> emailIds);
}
