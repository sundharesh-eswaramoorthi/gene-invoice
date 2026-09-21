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

@Component
@RequiredArgsConstructor
public class EmailAddressing {

    private final UserRepository userRepository;
    private final EmailTargets targets;
    private final CurrentUser currentUser;

    private enum Kind { USER, ROLE, CUSTOMER }

    record From(Person person, RoleRef role) {}

    record To(Person person, RoleRef role, boolean customer) {}

    public record Plan(From from, List<To> to) {}

    public record Resolution(Person from, RoleRef fromRole, List<RecipientSet.Entry> recipients,
                             List<EmailDtos.Unresolved> unresolved, List<String> problems) {

        public Optional<String> problem() {
            return problems.stream().findFirst();
        }

        String unresolvedTokens() {
            return unresolved.isEmpty() ? null
                    : unresolved.stream().map(EmailDtos.Unresolved::token).collect(Collectors.joining(","));
        }
    }

    public Plan plan(EmailEntityType type, EmailToken from, List<EmailToken> to) {
        return plan(type, targets.rolesOffered(type), from, to);
    }

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

    private static String inWords(List<String> parts) {
        if (parts.size() <= 1) return String.join("", parts);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

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
