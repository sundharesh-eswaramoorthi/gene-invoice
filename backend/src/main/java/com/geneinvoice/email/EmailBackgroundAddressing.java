package com.geneinvoice.email;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The From and To tokens of an email nobody asked for by hand — an automation rule firing on a
 * background thread — turned into the same {@link EmailAddressing.Plan} a request builds (R1).
 *
 * <p>It exists because {@link EmailAddressing#plan} starts from the caller: the caller is the
 * sender when no From is given, the caller's customer decides what may be addressed, and a customer
 * login may not write to staff by name (E13). A background send has no caller at all, so none of
 * those apply and none of them can be checked; what is left is the part that is about the record
 * rather than the person, and that is checked here exactly as a request's tokens are — a role must
 * be one the record offers, a named person must be an active internal user, and a customer token
 * needs a record that has a customer.
 *
 * <p>The sender must be named. A request may leave From out and mean "me"; a rule has no "me", so a
 * rule that says nothing about its sender is a rule that cannot be sent and says so.
 *
 * <p>Once a plan is built, {@link EmailAddressing#resolve} does the rest, so an automated email
 * reaches exactly the people the same tokens would reach from the compose form.
 */
@Component
@RequiredArgsConstructor
class EmailBackgroundAddressing {

    static final String NO_SENDER = "An automated email needs a sender";

    private final UserRepository userRepository;
    private final EmailTargets targets;

    @Transactional(readOnly = true)
    EmailAddressing.Plan plan(EmailTargets.Target target, EmailToken from, List<EmailToken> to) {
        List<RoleRef> offered = targets.rolesOffered(target);
        List<EmailAddressing.To> recipients = new ArrayList<>();
        for (EmailToken token : to == null ? List.<EmailToken>of() : to) {
            recipients.add(recipient(target, offered, token));
        }
        return new EmailAddressing.Plan(sender(target, offered, from), recipients);
    }

    private EmailAddressing.From sender(EmailTargets.Target target, List<RoleRef> offered, EmailToken token) {
        if (token == null) throw new BadRequestException(NO_SENDER);
        return switch (kind(token, "from")) {
            case USER -> new EmailAddressing.From(activeInternalUser(token.userId()), null);
            case ROLE -> new EmailAddressing.From(null, offeredRole(target, offered, token));
            case CUSTOMER -> throw new BadRequestException("The sender must be a person or a role");
        };
    }

    private EmailAddressing.To recipient(EmailTargets.Target target, List<RoleRef> offered, EmailToken token) {
        if (token == null) throw new BadRequestException("A recipient is empty");
        return switch (kind(token, "to")) {
            case USER -> new EmailAddressing.To(activeInternalUser(token.userId()), null, false);
            case ROLE -> new EmailAddressing.To(null, offeredRole(target, offered, token), false);
            case CUSTOMER -> {
                if (!targets.mayHaveCustomer(target.type())) {
                    throw new BadRequestException("Customer emails do not apply to " + target.type().noun() + "s");
                }
                yield new EmailAddressing.To(null, null, true);
            }
        };
    }

    /** The kinds of token, named as the compose form writes them. */
    private enum Kind { USER, ROLE, CUSTOMER }

    private static Kind kind(EmailToken token, String field) {
        String wanted = token.type() == null ? "" : token.type().trim();
        for (Kind k : Kind.values()) {
            if (k.name().equalsIgnoreCase(wanted)) return k;
        }
        throw new BadRequestException(field + " type must be one of " + Arrays.toString(Kind.values()));
    }

    /** A role at the level it is meant at, refused when this record does not offer that pair (L7). */
    private static RoleRef offeredRole(EmailTargets.Target target, List<RoleRef> offered, EmailToken token) {
        EmailRole role = EmailRole.parse(token.role());
        RoleLevel level = token.level() == null || token.level().isBlank()
                ? EmailTargets.defaultLevel(target.type(), role)
                : RoleLevel.parse(token.level());
        RoleRef ref = new RoleRef(role, level);
        if (!offered.contains(ref)) {
            throw new BadRequestException(ref.label(target.type()) + " is not a role on " + target.type().noun() + "s");
        }
        return ref;
    }

    private EmailTargets.Person activeInternalUser(Long userId) {
        if (userId == null) throw new BadRequestException("A USER entry needs a userId");
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("User #" + userId + " does not exist"));
        if (!u.isActive() || u.getCustomerId() != null) {
            throw new BadRequestException(u.getUsername() + " is not an active internal user");
        }
        return EmailTargets.Person.of(u);
    }
}
