package com.geneinvoice.mail.tracking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MailInboundRepository extends JpaRepository<MailInbound, Long> {

    boolean existsByConnectionIdAndProviderMessageId(Long connectionId, String providerMessageId);

    List<MailInbound> findByConnectionIdOrderByIdAsc(Long connectionId);
}
