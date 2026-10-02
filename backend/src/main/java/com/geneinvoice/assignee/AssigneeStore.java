package com.geneinvoice.assignee;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.email.EmailAddressing;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.RoleLevel;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where assignees are kept (§5.9.1): checking a list of tokens, replacing an owner's current assignees, and reading
 * them back, now or as of a date. Repositories only, so the domain services can use it without ever reaching
 * {@code EmailTargets}, which depends on them (CF-3).
 */
@Component
@RequiredArgsConstructor
public class AssigneeStore {

    public static final int MAX = 10;
    public static final String FIELD = "assignees";

    private final RecordAssigneeRepository repository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final CurrentUser currentUser;

    /**
     * The tokens as they will be stored: people who are active staff, roles offered on this kind of record (with
     * the role's usual level when none is given), each once, at most {@value #MAX}. Anything else is a 400 against
     * {@code assignees}.
     */
    public List<EmailToken> validate(EmailEntityType context, List<EmailToken> tokens, List<RoleRef> allowedRoles) {
        if (tokens == null) return List.of();
        Map<String, EmailToken> kept = new LinkedHashMap<>();
        try {
            for (EmailToken token : tokens) {
                EmailToken clean = clean(context, token, allowedRoles);
                kept.putIfAbsent(key(clean), clean);
            }
        } catch (BadRequestException e) {
            throw invalid(e.getMessage());
        }
        if (kept.size() > MAX) throw invalid("At most " + MAX + " assignees");
        return List.copyOf(kept.values());
    }

    private EmailToken clean(EmailEntityType context, EmailToken token, List<RoleRef> allowedRoles) {
        String type = token == null || token.type() == null ? "" : token.type().trim();
        if (type.equalsIgnoreCase(RecordAssignee.USER)) {
            return EmailToken.user(EmailAddressing.requireActiveInternalUser(userRepository, token.userId()).getId());
        }
        if (type.equalsIgnoreCase(RecordAssignee.ROLE)) {
            return EmailToken.role(EmailAddressing.requireOfferedRole(context, allowedRoles, token));
        }
        throw new BadRequestException("Assignees are people or roles");
    }

    public static GlobalExceptionHandler.InvalidFieldsException invalid(String message) {
        return new GlobalExceptionHandler.InvalidFieldsException(Map.of(FIELD, message));
    }

    /**
     * Makes the owner's current assignees exactly {@code tokens} (already validated): rows no longer wanted are
     * closed, new ones added; rows are never deleted. Run under the owner row's lock, which keeps the current rows
     * unique. Returns the tokens added.
     */
    public List<EmailToken> replace(String ownerType, Long ownerId, List<EmailToken> tokens) {
        List<RecordAssignee> current =
                repository.findByOwnerTypeAndOwnerIdAndRemovedAtIsNullOrderBySortOrderAscIdAsc(ownerType, ownerId);
        Set<String> wanted = new LinkedHashSet<>();
        tokens.forEach(t -> wanted.add(key(t)));
        Set<String> held = new LinkedHashSet<>();
        Instant now = clock.instant();
        Long actor = WriteOrigins.actor(currentUser);
        for (RecordAssignee row : current) {
            String key = key(token(row));
            if (wanted.contains(key) && held.add(key)) continue;
            row.setRemovedAt(now);
            row.setRemovedByUserId(actor);
            repository.save(row);
        }
        List<EmailToken> added = new ArrayList<>();
        int order = 0;
        for (EmailToken t : tokens) {
            order++;
            if (held.contains(key(t))) continue;
            RecordAssignee row = RecordAssignee.builder()
                    .ownerType(ownerType).ownerId(ownerId)
                    .kind(t.type()).userId(t.userId()).role(t.role()).roleLevel(t.level())
                    .sortOrder(order).addedAt(now).addedByUserId(actor)
                    .build();
            repository.save(row);
            held.add(key(t));
            added.add(t);
        }
        return added;
    }

    /** The owner's current assignees, in their order. */
    public List<EmailToken> list(String ownerType, Long ownerId) {
        return listAll(ownerType, List.of(ownerId)).getOrDefault(ownerId, List.of());
    }

    /** The assignees of each owner, in their order: current ones, or while a point is bound those of that day (D8). */
    public Map<Long, List<EmailToken>> listAll(String ownerType, Collection<Long> ownerIds) {
        Map<Long, List<EmailToken>> byOwner = new LinkedHashMap<>();
        if (ownerIds.isEmpty()) return byOwner;
        AsOfPoint point = AsOf.current();
        List<RecordAssignee> rows = point == null
                ? repository.findActive(ownerType, ownerIds)
                : repository.findActiveAt(ownerType, ownerIds, point.instant());
        for (RecordAssignee row : rows) {
            byOwner.computeIfAbsent(row.getOwnerId(), id -> new ArrayList<>()).add(token(row));
        }
        return byOwner;
    }

    public static EmailToken token(RecordAssignee row) {
        if (RecordAssignee.USER.equals(row.getKind())) return EmailToken.user(row.getUserId());
        return EmailToken.role(new RoleRef(EmailRole.valueOf(row.getRole()), RoleLevel.valueOf(row.getRoleLevel())));
    }

    /** One key per assignee: {@code USER:<id>}, or the role's token ({@code ROLE:<LEVEL>:<ROLE>}). */
    public static String key(EmailToken t) {
        if (RecordAssignee.USER.equals(t.type())) return "USER:" + t.userId();
        return roleOf(t).token();
    }

    /** The role of a stored (so validated) role token. */
    public static RoleRef roleOf(EmailToken t) {
        return new RoleRef(EmailRole.valueOf(t.role()), RoleLevel.valueOf(t.level()));
    }
}
