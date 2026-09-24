package com.geneinvoice.email;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Writes the email a rule sends, in the one place that can: RecipientSet, RecipientSet.Entry,
 * EmailAddressing.From/To, EmailTargets.Person.of and all of EmailText are package-private and
 * stay that way, so the class that needs them lives here rather than widening them (A3, A5).
 *
 * <p>Its body is EmailService's private save with exactly two differences: the sender of record is
 * the rule's AUTHOR — a real, accountable person, never null and never synthetic — and the Target
 * came from RoleResolver.snapshot rather than from a signed-in caller's book.
 *
 * <p>EmailService.send is NOT called and NOT touched: it needs currentUser.require() and it
 * dispatches inside its own transaction, which a consumer thread has neither of (A5).
 */
@Component
@RequiredArgsConstructor
public class AutomationEmailWriter {

    private final EmailRepository emailRepository;
    private final EmailRecipientRepository recipientRepository;
    private final EmailAddressing addressing;

    /**
     * @param emailId    the row written, or null when nothing was written
     * @param unresolved the role tokens nobody held on this record; the send proceeds anyway,
     *                   which is the contract EmailAddressing already has (A4)
     * @param problem    why nothing was written, or null. The caller settles the step SKIPPED.
     */
    public record Drafted(Long emailId, List<String> unresolved, String problem) {}

    /**
     * MANDATORY is load-bearing: the email row must commit with the step's settle, or a worker
     * that loses its fence leaves an orphan email behind that nobody sent and nobody can explain.
     * The caller dispatches AFTER the transaction commits (A5).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Drafted draft(EmailTargets.Target target, EmailDtos.EmailToken from,
                         List<EmailDtos.EmailToken> to, String subject, String body,
                         Long actorUserId) {
        if (actorUserId == null) {
            throw new BadRequestException("An automated email needs the rule's author as its actor");
        }
        // fit AFTER rendering and per destination column: a forty-character template can render to
        // forty thousand characters, and a rule must not fail because a customer's name is long
        // (A4). The TEMPLATE's own length is what was checked when the rule was saved.
        String cleanSubject = EmailText.fit(
                EmailText.oneLine(EmailText.storable(subject)), FieldLimits.EMAIL_SUBJECT);
        String cleanBody = EmailText.fit(
                body == null ? "" : EmailText.storable(body), FieldLimits.EMAIL_BODY);
        if (cleanSubject.isEmpty()) {
            // Exactly what EmailService.Content.check would refuse, refused before anything is
            // stored rather than as a 400 nobody is there to read (A4, A5).
            return new Drafted(null, List.of(), "The subject rendered empty");
        }

        EmailAddressing.Resolution resolution = addressing.resolve(
                addressing.planForRule(target.type(), from, to), target);
        List<String> unresolved = resolution.unresolved().stream()
                .map(EmailDtos.Unresolved::token).toList();
        Optional<String> problem = resolution.problem();
        // Nobody resolved at all: no sender, or no recipient of any kind. Nothing is written and
        // the caller settles the step SKIPPED with this sentence (A5).
        if (problem.isPresent()) return new Drafted(null, unresolved, problem.get());

        EmailTargets.Person sender = resolution.from();
        Email email = emailRepository.save(Email.builder()
                .entityType(target.type())
                .entityId(target.id())
                .entityLabel(target.label())
                .direction(EmailDirection.OUTBOUND)
                .status(EmailStatus.QUEUED)
                .subject(cleanSubject)
                .body(cleanBody)
                .fromUserId(sender.userId())
                .fromRole(resolution.fromRole() == null ? null : resolution.fromRole().role())
                .fromRoleLevel(resolution.fromRole() == null ? null : resolution.fromRole().level())
                .fromName(EmailText.fit(sender.name(), Email.NAME_MAX))
                .fromAddress(sender.address())
                .fromCustomerId(sender.customerId())
                .fromInternal(sender.internal())
                // The rule's author and not the sender: who is answerable for this email having
                // been sent at all is a different fact from whose name is on it (A3).
                .sentByUserId(actorUserId)
                .unresolved(EmailText.fit(resolution.unresolvedTokens(), Email.UNRESOLVED_MAX))
                .build());
        recipientRepository.saveAll(resolution.recipients().stream().map(r -> EmailRecipient.builder()
                .email(email)
                .field(r.field())
                .userId(r.userId())
                .customerId(r.customerId())
                .name(EmailText.fit(r.name(), Email.NAME_MAX))
                .address(r.address())
                .internal(r.internal())
                .sources(EmailText.fit(String.join(",", r.sources()), EmailRecipient.SOURCES_MAX))
                // One copy per To recipient with an address, each tracked on its own (M6).
                .deliveryStatus(r.field() == RecipientField.TO && r.address() != null
                        ? RecipientDeliveryStatus.QUEUED : null)
                .build()).toList());
        return new Drafted(email.getId(), unresolved, null);
    }
}
