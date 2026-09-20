package com.geneinvoice.email;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailTargets.Person;
import com.geneinvoice.email.EmailTargets.Target;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The addressing rules of §5: which From and To a caller may ask for, and who they come to on one
 * record. Checking a request is kept apart from resolving it, so a bulk send checks its tokens once
 * and resolves them again for every row.
 */
@Component
@RequiredArgsConstructor
public class EmailAddressing {

    private final UserRepository userRepository;
    private final EmailTargets targets;
    private final CurrentUser currentUser;

    private enum Kind { USER, ROLE, CUSTOMER }

    /** A checked From: a person (the caller, or someone named), or a role to resolve on the record. */
    record From(Person person, RoleRef role) {}

    /** A checked To entry: a named person, a role at a level, or the customer's emails. */
    record To(Person person, RoleRef role, boolean customer) {}

    /** Tokens that are well formed and allowed for this caller and kind of record. */
    public record Plan(From from, List<To> to) {}

    /**
     * What a plan comes to on one record. {@code from} is null when the sender role resolved to
     * nobody; {@code problems} says what would make sending fail, sender first.
     */
    public record Resolution(Person from, RoleRef fromRole, List<RecipientSet.Entry> recipients,
                             List<EmailDtos.Unresolved> unresolved, List<String> problems) {

        public Optional<String> problem() {
            return problems.stream().findFirst();
        }

        /** The {@code unresolved} column: its tokens, comma-separated. */
        String unresolvedTokens() {
            return unresolved.isEmpty() ? null
                    : unresolved.stream().map(EmailDtos.Unresolved::token).collect(Collectors.joining(","));
        }
    }

    // ---- checking the request --------------------------------------------------------

    /**
     * Checks the tokens without looking at any particular record: a malformed or unknown token is a
     * 400, and what a customer login may not address is a 403 (E13). An empty To passes here; sending
     * refuses it with a field error, while a preview only lists it as a problem. Roles are checked
     * against the kind, as a bulk send has no one record to check them against.
     */
    public Plan plan(EmailEntityType type, EmailToken from, List<EmailToken> to) {
        return plan(type, targets.rolesOffered(type), from, to);
    }

    /**
     * As {@link #plan(EmailEntityType, EmailToken, List)}, with roles checked against the record: a
     * role it can never have (on a user who is not a customer login) is refused like one its kind lacks.
     */
    public Plan plan(Target target, EmailToken from, List<EmailToken> to) {
        return plan(target.type(), targets.rolesOffered(target), from, to);
    }

    private Plan plan(EmailEntityType type, List<RoleRef> offered, EmailToken from, List<EmailToken> to) {
        User caller = currentUser.require();
        boolean restricted = caller.getCustomerId() != null;
        List<To> recipients = new ArrayList<>();
        for (EmailToken token : to == null ? List.<EmailToken>of() : to) {
            recipients.add(checkTo(type, offered, token, restricted));
        }
        return new Plan(checkFrom(type, offered, from, caller, restricted), recipients);
    }

    private From checkFrom(EmailEntityType type, List<RoleRef> offered, EmailToken token, User caller,
                           boolean restricted) {
        if (token == null) return new From(Person.of(caller), null);
        Kind kind = kind(token, "from");
        if (restricted) {
            // A customer login writes as themselves and nobody else.
            if (kind == Kind.USER && caller.getId().equals(token.userId())) {
                return new From(Person.of(caller), null);
            }
            throw new AccessDeniedException("Not allowed");
        }
        return switch (kind) {
            case USER -> new From(activeInternalUser(token.userId()), null);
            case ROLE -> new From(null, offeredRole(type, offered, token));
            case CUSTOMER -> throw new BadRequestException("The sender must be a person or a role");
        };
    }

    private To checkTo(EmailEntityType type, List<RoleRef> offered, EmailToken token, boolean restricted) {
        if (token == null) throw new BadRequestException("A recipient is empty");
        return switch (kind(token, "to")) {
            case USER -> {
                // Customer logins write to roles and their own customer, never to staff by name (E13).
                if (restricted) throw new AccessDeniedException("Not allowed");
                yield new To(activeInternalUser(token.userId()), null, false);
            }
            case ROLE -> new To(null, offeredRole(type, offered, token), false);
            case CUSTOMER -> {
                if (!targets.mayHaveCustomer(type)) {
                    throw new BadRequestException("Customer emails do not apply to " + plural(type));
                }
                yield new To(null, null, true);
            }
        };
    }

    private static Kind kind(EmailToken token, String field) {
        String wanted = token.type() == null ? "" : token.type().trim();
        for (Kind k : Kind.values()) {
            if (k.name().equalsIgnoreCase(wanted)) return k;
        }
        throw new BadRequestException(field + " type must be one of " + Arrays.toString(Kind.values()));
    }

    private Person activeInternalUser(Long userId) {
        if (userId == null) throw new BadRequestException("A USER entry needs a userId");
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("User #" + userId + " does not exist"));
        if (!u.isActive() || u.getCustomerId() != null) {
            throw new BadRequestException(u.getUsername() + " is not an active internal user");
        }
        return Person.of(u);
    }

    /**
     * The role and the level it is meant at, refused when the kind or the record does not offer that
     * pair. A token without a level is read at the kind's default level (L7), which for every role
     * on a record that has a customer is the customer's book — not what the same token meant before
     * levels existed, so a caller that means the record's own POC has to say so.
     */
    private static RoleRef offeredRole(EmailEntityType type, List<RoleRef> offered, EmailToken token) {
        EmailRole role = EmailRole.parse(token.role());
        RoleLevel level = token.level() == null || token.level().isBlank()
                ? EmailTargets.defaultLevel(type, role)
                : RoleLevel.parse(token.level());
        RoleRef ref = new RoleRef(role, level);
        if (!offered.contains(ref)) {
            throw new BadRequestException(ref.label(type) + " is not a role on " + plural(type));
        }
        return ref;
    }

    private static String plural(EmailEntityType type) {
        return type.noun() + "s";
    }

    // ---- resolving on a record --------------------------------------------------------

    /**
     * Who the plan reaches on this record, now: roles are looked up on the record as it stands (a
     * role in To reaches every holder at its level, a role in From only the first, L2, L3, L5), the
     * customer's emails are its current addresses, and one person added several ways — at both
     * levels, or by name as well — is one recipient that keeps each way (L6, E4, E5).
     */
    public Resolution resolve(Plan plan, Target target) {
        List<String> problems = new ArrayList<>();
        Person from = plan.from().person();
        RoleRef fromRole = plan.from().role();
        if (fromRole != null) {
            from = target.sender(fromRole).orElse(null);
            if (from == null) {
                problems.add("Nobody holds " + fromRole.label(target.type()) + " on " + target.label()
                        + ", so it cannot be the sender");
            }
        }

        RecipientSet recipients = new RecipientSet();
        // Keyed by token, so asking twice for a missing role reports it once.
        Map<String, String> missing = new LinkedHashMap<>();
        for (To to : plan.to()) {
            if (to.person() != null) {
                recipients.add(RecipientField.TO, to.person(), "USER");
            } else if (to.role() != null) {
                List<Person> holders = target.holders(to.role());
                if (holders.isEmpty()) {
                    missing.putIfAbsent(to.role().token(),
                            to.role().label(target.type()) + " is not assigned");
                }
                for (Person holder : holders) {
                    recipients.add(RecipientField.TO, holder, to.role().token());
                }
            } else if (target.customerEmails().isEmpty()) {
                missing.putIfAbsent("CUSTOMER", target.customerId() == null
                        ? target.label() + " has no customer"
                        : "the customer has no email address");
            } else {
                for (Person address : target.customerEmails()) {
                    recipients.add(RecipientField.TO, address, "CUSTOMER");
                }
            }
        }

        if (plan.to().isEmpty()) {
            problems.add("Add at least one recipient");
        } else if (recipients.isEmpty()) {
            problems.add("No recipients: " + inWords(new ArrayList<>(missing.values())));
        }
        List<EmailDtos.Unresolved> unresolved = missing.keySet().stream()
                .map(token -> describeUnresolved(target.type(), token, target.label())).toList();
        return new Resolution(from, fromRole, recipients.entries(), unresolved, problems);
    }

    /** "a", "a and b", "a, b and c". */
    private static String inWords(List<String> parts) {
        if (parts.size() <= 1) return String.join("", parts);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /**
     * An unresolved token as the Email tab shows it, from what was stored. The kind of record names
     * the level, so a role reads the same here as on the chip that asked for it.
     */
    static EmailDtos.Unresolved describeUnresolved(EmailEntityType type, String token, String entityLabel) {
        if (token.startsWith("ROLE:")) {
            Optional<RoleRef> role = RoleRef.parseToken(token);
            if (role.isEmpty()) {
                return new EmailDtos.Unresolved(token, token, "Nobody holds it on " + entityLabel);
            }
            String label = role.get().label(type);
            return new EmailDtos.Unresolved(token, label, "Nobody holds " + label + " on " + entityLabel);
        }
        return new EmailDtos.Unresolved(token, "Customer emails", "No customer email address for " + entityLabel);
    }
}
