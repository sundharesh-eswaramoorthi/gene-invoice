package com.geneinvoice.assignee;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.email.RoleLevel;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one place that reads, keeps and answers assignees, for every kind of record that has them
 * (A1). Tasks, promises and disputes all take the same several-assignee list, so they all come
 * through here rather than each growing its own copy of the rules.
 *
 * <p>An assignee is picked exactly as an email recipient is — a person, or a role at a level — and
 * is checked against the same table of what each kind of record offers, so a role the record could
 * never have is refused when it is picked rather than quietly reaching nobody (A4). What a role
 * reaches is never stored: it is read from the customer's POC book, or the record's own POC field,
 * at the moment it is asked for (A2).
 */
@Service
@RequiredArgsConstructor
public class AssigneeService {

    /** More than this on one record is a mistake, not a use; the picker never offers that many. */
    public static final int MAX_ASSIGNEES = 20;

    private final AssigneeRepository repository;
    private final EmailTargets targets;
    private final UserRepository userRepository;

    // ---- reading what was picked -------------------------------------------------------

    /**
     * Reads the picked tokens into rows, refusing anything the kind of record does not offer, and
     * dropping a pick repeated in one list rather than writing it twice (A4). Nothing is written
     * here: the caller saves the parent first, then hands the rows back with its id.
     */
    public List<Assignee> parse(EmailEntityType type, Long customerId, List<EmailToken> tokens) {
        if (tokens == null || tokens.isEmpty()) return List.of();
        if (tokens.size() > MAX_ASSIGNEES) {
            throw new BadRequestException("A record takes at most " + MAX_ASSIGNEES + " assignees");
        }
        List<RoleRef> offered = targets.rolesOffered(type);
        Set<String> seen = new LinkedHashSet<>();
        List<Assignee> rows = new ArrayList<>();
        for (EmailToken token : tokens) {
            Assignee row = read(type, customerId, offered, token);
            if (seen.add(key(row))) rows.add(row);
        }
        return rows;
    }

    private Assignee read(EmailEntityType type, Long customerId, List<RoleRef> offered, EmailToken token) {
        String wanted = token == null || token.type() == null ? "" : token.type().trim();
        if (AssigneeKind.USER.name().equalsIgnoreCase(wanted)) {
            return Assignee.builder()
                    .kind(AssigneeKind.USER)
                    .userId(activeInternalUser(token.userId()).getId())
                    .customerId(customerId)
                    .build();
        }
        if (AssigneeKind.ROLE.name().equalsIgnoreCase(wanted)) {
            RoleRef ref = offeredRole(type, offered, token);
            return Assignee.builder()
                    .kind(AssigneeKind.ROLE)
                    .role(ref.role())
                    .level(ref.level())
                    .customerId(customerId)
                    .build();
        }
        throw new BadRequestException("An assignee type must be USER or ROLE");
    }

    /**
     * The role and the level it is meant at, refused when the record does not offer that pair. A
     * token without a level is read at the kind's default level, exactly as the email form reads
     * one (L7), so one picker can send the same token to either.
     */
    private static RoleRef offeredRole(EmailEntityType type, List<RoleRef> offered, EmailToken token) {
        EmailRole role = EmailRole.parse(token.role());
        RoleLevel level = token.level() == null || token.level().isBlank()
                ? EmailTargets.defaultLevel(type, role)
                : RoleLevel.parse(token.level());
        RoleRef ref = new RoleRef(role, level);
        if (!offered.contains(ref)) {
            throw new BadRequestException(ref.label(type) + " is not a role on " + type.noun() + "s");
        }
        return ref;
    }

    /**
     * Only an active internal user may be assigned work. A customer login is refused by the same
     * rule the email form applies to a named recipient, and for the same reason: the work is ours.
     */
    private User activeInternalUser(Long userId) {
        if (userId == null) throw new BadRequestException("A USER assignee needs a userId");
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BadRequestException("User #" + userId + " does not exist"));
        if (!u.isActive() || u.getCustomerId() != null) {
            throw new BadRequestException(u.getUsername() + " is not an active internal user");
        }
        return u;
    }

    /** What makes two picks the same pick, for dropping a repeat within one list. */
    private static String key(Assignee a) {
        return a.getKind() == AssigneeKind.USER
                ? "USER:" + a.getUserId()
                : new RoleRef(a.getRole(), a.getLevel()).token();
    }

    // ---- keeping them ------------------------------------------------------------------

    /**
     * Makes the record's assignees exactly these. Called inside the caller's own transaction, so
     * the assignees and the record they belong to commit or roll back together.
     */
    @Transactional
    public List<Assignee> replace(AssigneeOwnerType ownerType, Long ownerId, List<Assignee> rows) {
        repository.deleteForOwner(ownerType, ownerId);
        if (rows.isEmpty()) return List.of();
        for (Assignee row : rows) {
            row.setId(null);
            row.setOwnerType(ownerType);
            row.setOwnerId(ownerId);
        }
        return repository.saveAll(rows);
    }

    @Transactional(readOnly = true)
    public List<Assignee> of(AssigneeOwnerType ownerType, Long ownerId) {
        return repository.findByOwnerTypeAndOwnerIdOrderByIdAsc(ownerType, ownerId);
    }

    /** Every listed record's assignees in one read, so a page of rows is one query, not one per row. */
    @Transactional(readOnly = true)
    public Map<Long, List<Assignee>> byOwner(AssigneeOwnerType ownerType, Collection<Long> ownerIds) {
        if (ownerIds.isEmpty()) return Map.of();
        return repository.findByOwnerTypeAndOwnerIdInOrderByIdAsc(ownerType, ownerIds).stream()
                .collect(Collectors.groupingBy(Assignee::getOwnerId));
    }

    // ---- answering who they reach ------------------------------------------------------

    /**
     * The assignees as they read now, against the record they are on: a person is themselves, a
     * role is everyone holding that seat at that level this moment, the primary first (A2). A seat
     * nobody holds reads as unresolved with no people, rather than disappearing — an unassigned
     * role is a thing somebody needs to see and fix.
     *
     * <p>{@code showPeople} is whether the caller may be told who those people are — POC identity,
     * which is {@code POC_VIEW}'s to give and which a customer login never has whatever privileges
     * its role carries (AC-A8, AC-A6). False leaves every row its seat and whether that seat is
     * held, and names nobody: a caller who may not see POC identity still sees the work is assigned
     * to the Collection POC, and still sees that nobody is sitting in it.
     */
    public List<AssigneeDtos.AssigneeDto> describe(List<Assignee> rows, EmailTargets.Target target,
                                                   boolean showPeople) {
        List<AssigneeDtos.AssigneeDto> out = new ArrayList<>();
        for (Assignee a : rows) {
            if (a.getKind() == AssigneeKind.USER) {
                List<AssigneeDtos.PersonDto> people = userRepository.findById(a.getUserId())
                        .map(u -> List.of(new AssigneeDtos.PersonDto(u.getId(), nameOf(u), u.getEmail())))
                        .orElse(List.of());
                out.add(new AssigneeDtos.AssigneeDto(AssigneeKind.USER, a.getUserId(), null, null,
                        people.isEmpty() ? "Unknown user" : people.get(0).name(),
                        !people.isEmpty(), showPeople ? people : List.of()));
            } else {
                RoleRef ref = new RoleRef(a.getRole(), a.getLevel());
                List<AssigneeDtos.PersonDto> people = target == null ? List.of()
                        : target.holders(ref).stream()
                                .map(p -> new AssigneeDtos.PersonDto(p.userId(), p.name(), p.address()))
                                .toList();
                out.add(new AssigneeDtos.AssigneeDto(AssigneeKind.ROLE, null,
                        a.getRole().name(), a.getLevel() == null ? null : a.getLevel().name(),
                        ref.label(target == null ? EmailEntityType.CUSTOMER : target.type()),
                        !people.isEmpty(), showPeople ? people : List.of()));
            }
        }
        return out;
    }

    private static String nameOf(User u) {
        String full = u.getFullName();
        return full == null || full.isBlank() ? u.getUsername() : full;
    }

    /** The tokens that would produce these rows again, for an audit snapshot or a copy. */
    public static List<String> tokens(List<Assignee> rows) {
        return rows.stream()
                .map(a -> a.getKind() == AssigneeKind.USER
                        ? "USER:" + a.getUserId()
                        : new RoleRef(a.getRole(), a.getLevel()).token())
                .toList();
    }
}
