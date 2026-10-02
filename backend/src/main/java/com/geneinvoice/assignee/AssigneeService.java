package com.geneinvoice.assignee;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.Emails;
import com.geneinvoice.common.Strings;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.email.RoleLevel;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.email.connection.GmailConnectionService;
import com.geneinvoice.email.connection.GmailStatus;
import com.geneinvoice.history.HistoryQueries;
import com.geneinvoice.history.Records;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.region.RegionAccessQueries;
import com.geneinvoice.region.UserRegionGrantRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Who assignees are (§5.9.1): the people a list of tokens means on a record now, whether a person may be assigned
 * (they must be able to open the record), who to tell, and whom the pickers offer. For controllers, the task
 * service and the automation handlers; the domain services keep assignees through {@link AssigneeStore} only (CF-3).
 */
@Service
@RequiredArgsConstructor
public class AssigneeService {

    /** At most this many people hear about one change of assignees. */
    static final int NOTIFY_MAX = 20;
    static final int PEOPLE_LIMIT = 20;

    private final EmailTargets targets;
    private final VisibilityProbe visibilityProbe;
    private final RegionAccessQueries regionAccess;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final UserRegionGrantRepository grantRepository;
    private final GmailConnectionService gmailConnections;
    private final HistoryQueries historyQueries;
    private final Records records;
    private final CurrentUser currentUser;

    /** The privilege an assignee needs to open what they are assigned to. */
    public static String viewPrivilege(String ownerType) {
        return switch (ownerType) {
            case AssignedTo.TASK -> Privileges.TASK_VIEW;
            case AssignedTo.DISPUTE -> Privileges.DISPUTE_VIEW;
            case AssignedTo.PROMISE -> Privileges.PROMISE_VIEW;
            default -> throw new IllegalArgumentException("No assignees on " + ownerType);
        };
    }

    /** The tokens kept, and a warning for each person dropped because they cannot see the record. */
    public record Probed(List<EmailToken> kept, List<String> warnings) {}

    /** What an owner type's records are called in a sentence ("tasks"). */
    static String plural(String ownerType) {
        return switch (ownerType) {
            case AssignedTo.TASK -> "tasks";
            case AssignedTo.DISPUTE -> "disputes";
            case AssignedTo.PROMISE -> "promises";
            default -> throw new IllegalArgumentException("No assignees on " + ownerType);
        };
    }

    /**
     * Drops the people named directly who could not open the record (region, VIEW privilege and book), or who could
     * not open what they are assigned to there (the owner type's VIEW privilege in the region, CF-9), with a warning
     * for each: how automation assigns. Read-only and non-throwing, so it runs before any lock (§5.10.6).
     */
    @Transactional(readOnly = true)
    public Probed probe(String ownerType, List<EmailToken> tokens, EmailEntityType type, Long id, Long regionId) {
        String privilege = viewPrivilege(ownerType);
        List<EmailToken> kept = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (EmailToken t : tokens) {
            if (!RecordAssignee.USER.equals(t.type())) {
                kept.add(t);
            } else if (!visibilityProbe.canSee(t.userId(), type, id, regionId)) {
                warnings.add(nameOf(t.userId()) + " cannot see this record, so was not assigned");
            } else if (!regionAccess.holds(t.userId(), privilege, regionId)) {
                warnings.add(nameOf(t.userId()) + " cannot see " + plural(ownerType) + " here, so was not assigned");
            } else {
                kept.add(t);
            }
        }
        return new Probed(kept, warnings);
    }

    /**
     * As {@link #probe}, for a person choosing assignees: someone who cannot see the record, or cannot see the
     * owner type's records in its region (they would be told nothing and get a 403 on it), is a 400.
     */
    @Transactional(readOnly = true)
    public void requireVisible(String ownerType, List<EmailToken> tokens, EmailEntityType type, Long id,
                               Long regionId) {
        String privilege = viewPrivilege(ownerType);
        for (EmailToken t : tokens) {
            if (!RecordAssignee.USER.equals(t.type())) continue;
            if (!visibilityProbe.canSee(t.userId(), type, id, regionId)) {
                throw AssigneeStore.invalid(nameOf(t.userId()) + " cannot see this record");
            }
            if (!regionAccess.holds(t.userId(), privilege, regionId)) {
                throw AssigneeStore.invalid(nameOf(t.userId()) + " cannot see " + plural(ownerType) + " here");
            }
        }
    }

    /**
     * Who each token means on the record now, by {@link AssigneeStore#key}: an active person; or every active
     * holder of the role on the record (already only those with access to its region) who also holds the owner's
     * VIEW privilege there and can open the record itself (its book too), so nobody is pointed at something they
     * would get a 403 or a 404 on (PC-2). Holders come primary first.
     */
    @Transactional(readOnly = true)
    public Map<String, List<User>> resolve(String ownerType, List<EmailToken> tokens, EmailTargets.Target target) {
        Map<String, List<User>> people = new LinkedHashMap<>();
        String privilege = viewPrivilege(ownerType);
        Map<Long, User> users = users(tokens.stream().filter(t -> RecordAssignee.USER.equals(t.type()))
                .map(EmailToken::userId).toList());
        for (EmailToken t : tokens) {
            List<User> found = new ArrayList<>();
            if (RecordAssignee.USER.equals(t.type())) {
                User u = users.get(t.userId());
                if (u != null && u.isActive()) found.add(u);
            } else if (target != null) {
                List<Long> holders = target.holders(AssigneeStore.roleOf(t)).stream()
                        .map(EmailTargets.Person::userId).toList();
                Map<Long, User> byId = users(holders);
                for (Long holder : holders) {
                    User u = byId.get(holder);
                    if (u != null && u.isActive() && regionAccess.holds(holder, privilege, target.regionId())
                            && visibilityProbe.canSee(holder, target.type(), target.id(), target.regionId())) {
                        found.add(u);
                    }
                }
            }
            people.put(AssigneeStore.key(t), found);
        }
        return people;
    }

    /** The assignees as a record's detail shows them: with the people each means now. */
    @Transactional(readOnly = true)
    public List<AssigneeDtos.AssigneeDto> describe(String ownerType, List<EmailToken> tokens, EmailEntityType type,
                                                   EmailTargets.Target target) {
        Map<String, List<User>> people = resolve(ownerType, tokens, target);
        return tokens.stream().map(t -> dto(t, type, people.getOrDefault(AssigneeStore.key(t), List.of()))).toList();
    }

    /**
     * The assignees as a list row shows them: people by name, roles by label. Who holds a role is worked out on the
     * record's own page, not once per row.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<AssigneeDtos.AssigneeDto>> describeBriefly(Map<Long, List<EmailToken>> byOwner,
                                                                      Map<Long, EmailEntityType> types) {
        Map<Long, User> users = users(byOwner.values().stream().flatMap(List::stream)
                .filter(t -> RecordAssignee.USER.equals(t.type())).map(EmailToken::userId).toList());
        Map<Long, List<AssigneeDtos.AssigneeDto>> out = new HashMap<>();
        byOwner.forEach((owner, tokens) -> out.put(owner, tokens.stream().map(t -> {
            if (!RecordAssignee.USER.equals(t.type())) {
                return new AssigneeDtos.AssigneeDto(t, AssigneeStore.roleOf(t).label(types.get(owner)), List.of(),
                        false);
            }
            User u = users.get(t.userId());
            return dto(t, types.get(owner), u == null || !u.isActive() ? List.of() : List.of(u));
        }).toList()));
        return out;
    }

    /**
     * The assignees as they were at a point, and who they meant then: a role's holders are the seats of that day
     * (customer level) or the record's own POC that day (record level). People are as they are now.
     */
    @Transactional(readOnly = true)
    public List<AssigneeDtos.AssigneeDto> describeAt(List<EmailToken> tokens, EmailEntityType type, Long recordId,
                                                     Long customerId, AsOfPoint point) {
        List<CustomerPoc> seats = null;
        Map<Long, User> users = users(tokens.stream().filter(t -> RecordAssignee.USER.equals(t.type()))
                .map(EmailToken::userId).toList());
        List<AssigneeDtos.AssigneeDto> out = new ArrayList<>();
        for (EmailToken t : tokens) {
            List<User> people = new ArrayList<>();
            if (RecordAssignee.USER.equals(t.type())) {
                Optional.ofNullable(users.get(t.userId())).filter(User::isActive).ifPresent(people::add);
            } else {
                RoleRef role = AssigneeStore.roleOf(t);
                if (role.level() == RoleLevel.CUSTOMER) {
                    if (seats == null) seats = historyQueries.seatsAt(List.of(customerId), point);
                    seats.stream().filter(s -> s.getPocType() == role.role().pocType())
                            .map(CustomerPoc::getUser).filter(u -> u != null && u.isActive()).forEach(people::add);
                } else {
                    recordPoc(type, recordId, role).filter(User::isActive).ifPresent(people::add);
                }
            }
            out.add(dto(t, type, people));
        }
        return out;
    }

    /**
     * The record's own POC of the role, as the record is read now (as of the bound point, when one is). A dispute's
     * is its target's, as {@link EmailTargets} reads it live; a dispute on the customer as a whole has none.
     */
    private Optional<User> recordPoc(EmailEntityType type, Long recordId, RoleRef role) {
        return switch (type) {
            case INVOICE -> records.find(Invoice.class, recordId).map(Invoice::getSalesPoc);
            case PAYMENT -> records.find(Payment.class, recordId).map(Payment::getCollectionPoc);
            case PROMISE -> records.find(PaymentPromise.class, recordId).map(PaymentPromise::getCollectionPoc);
            case DISPUTE -> records.find(Dispute.class, recordId).flatMap(d -> switch (d.getTargetType()) {
                case INVOICE -> role.role() == EmailRole.SALES_POC
                        ? recordPoc(EmailEntityType.INVOICE, d.getTargetId(), role) : Optional.empty();
                case PAYMENT -> role.role() == EmailRole.COLLECTION_POC
                        ? recordPoc(EmailEntityType.PAYMENT, d.getTargetId(), role) : Optional.empty();
                case CUSTOMER -> Optional.empty();
            });
            default -> Optional.empty();
        };
    }

    private AssigneeDtos.AssigneeDto dto(EmailToken t, EmailEntityType type, List<User> people) {
        boolean user = RecordAssignee.USER.equals(t.type());
        String label = user
                ? (people.isEmpty() ? nameOf(t.userId()) : name(people.get(0)))
                : AssigneeStore.roleOf(t).label(type);
        List<AssigneeDtos.PersonDto> persons = new ArrayList<>();
        for (int i = 0; i < people.size(); i++) {
            persons.add(new AssigneeDtos.PersonDto(people.get(i).getId(), name(people.get(i)), i == 0));
        }
        return new AssigneeDtos.AssigneeDto(t, label, persons, people.isEmpty());
    }

    /**
     * Whom each token would be told about, by {@link AssigneeStore#key}: the people {@link #resolve} finds, less a
     * person named directly who lacks the owner's VIEW privilege in the record's region (a role holder was checked
     * in resolve). Read-only, so it runs before the lock (§5.9.1 "Order of work"); pass the result to
     * {@link #notifyAssigned}.
     */
    @Transactional(readOnly = true)
    public Map<String, List<User>> recipients(String ownerType, List<EmailToken> tokens, EmailTargets.Target target) {
        String privilege = viewPrivilege(ownerType);
        Map<String, List<User>> people = resolve(ownerType, tokens, target);
        for (EmailToken t : tokens) {
            if (!RecordAssignee.USER.equals(t.type())) continue;
            people.computeIfPresent(AssigneeStore.key(t), (key, found) -> found.stream()
                    .filter(u -> regionAccess.holds(u.getId(), privilege, target.regionId())).toList());
        }
        return people;
    }

    /**
     * Tells the people the tokens just added mean, as {@code recipients} (from {@link #recipients}, worked out
     * before the lock) has them, less {@code exceptUserId}: at most {@value #NOTIFY_MAX}, each once. A token added
     * that was not worked out beforehand is worked out now. Returns how many were told.
     */
    @Transactional
    public int notifyAssigned(String ownerType, List<EmailToken> added, EmailTargets.Target target,
                              Map<String, List<User>> recipients, Long exceptUserId,
                              String type, String title, String message, String link) {
        if (added.isEmpty()) return 0;
        List<EmailToken> unknown = added.stream().filter(t -> !recipients.containsKey(AssigneeStore.key(t))).toList();
        Map<String, List<User>> late = unknown.isEmpty() ? Map.of() : recipients(ownerType, unknown, target);
        Set<Long> told = new LinkedHashSet<>();
        for (EmailToken t : added) {
            String key = AssigneeStore.key(t);
            for (User u : recipients.containsKey(key) ? recipients.get(key) : late.getOrDefault(key, List.of())) {
                if (told.size() >= NOTIFY_MAX) break;
                if (Objects.equals(u.getId(), exceptUserId) || told.contains(u.getId())) continue;
                notificationService.notify(u.getId(), type, title, message, link, target.regionId());
                told.add(u.getId());
            }
        }
        return told.size();
    }

    /**
     * Tells the people just assigned to a dispute or a promise (§5.10.11, §5.10.12), less {@code exceptUserId}. The
     * record is read as it is, whoever is signed in: a rule's owner or the approver of a held promise may not open it
     * themselves, and each person told is checked on their own. Returns how many were told.
     */
    @Transactional
    public int notifyAssignedTo(String ownerType, Long ownerId, List<EmailToken> added, Long exceptUserId) {
        if (added.isEmpty()) return 0;
        Optional<EmailTargets.Target> target = targets.findUnscoped(madeType(ownerType), ownerId);
        if (target.isEmpty()) return 0;
        return tellAssigned(ownerType, added, target.get(), Map.of(), exceptUserId);
    }

    /** Whom a rule's dispute or promise will tell, worked out before its lock for the region it was then going in. */
    public record NewRecordNotice(Long regionId, Map<String, List<User>> recipients) {}

    /**
     * Whom each token will mean on a dispute or a promise a rule is about to make in {@code regionId}, as
     * {@link #recipients} would find once it is made. It is read-only, so the action works it out before its lock
     * (§5.10.6), while the record does not exist yet. A role's holders are those of {@code from}, the record it is
     * made from: a dispute's own POC is its target's, and a promise's is {@code collectionPocUserId}, who will work
     * it. Pass the result to {@link #notifyNew}.
     */
    @Transactional(readOnly = true)
    public NewRecordNotice recipientsForNew(String ownerType, List<EmailToken> tokens, EmailTargets.Target from,
                                            Long regionId, Long collectionPocUserId) {
        EmailEntityType type = madeType(ownerType);
        String privilege = viewPrivilege(ownerType);
        Map<String, List<Long>> wanted = new LinkedHashMap<>();
        for (EmailToken t : tokens) {
            List<Long> ids;
            if (RecordAssignee.USER.equals(t.type())) {
                ids = List.of(t.userId());
            } else {
                RoleRef role = AssigneeStore.roleOf(t);
                ids = type == EmailEntityType.PROMISE && role.level() == RoleLevel.RECORD
                        ? (collectionPocUserId == null ? List.of() : List.of(collectionPocUserId))
                        : from.holders(role).stream().map(EmailTargets.Person::userId).toList();
            }
            wanted.put(AssigneeStore.key(t), ids);
        }
        Map<Long, User> users = users(wanted.values().stream().flatMap(List::stream).distinct().toList());
        Map<String, List<User>> people = new LinkedHashMap<>();
        for (EmailToken t : tokens) {
            boolean named = RecordAssignee.USER.equals(t.type());
            List<User> found = new ArrayList<>();
            for (Long id : wanted.get(AssigneeStore.key(t))) {
                User u = users.get(id);
                if (u != null && u.isActive() && regionAccess.holds(id, privilege, regionId)
                        && (named || visibilityProbe.wouldSee(id, type, regionId, collectionPocUserId))) {
                    found.add(u);
                }
            }
            people.put(AssigneeStore.key(t), found);
        }
        return new NewRecordNotice(regionId, people);
    }

    /**
     * Tells the people just assigned to a dispute or a promise a rule made, as {@code notice} (from
     * {@link #recipientsForNew}) has them, reading nothing more under the caller's lock. If the customer moved region
     * after the notice was worked out, it no longer holds, and the people are worked out again now, as in
     * {@link #notifyAssignedTo}. Returns how many were told.
     */
    @Transactional
    public int notifyNew(String ownerType, Long ownerId, Long customerId, Long regionId, List<EmailToken> added,
                         NewRecordNotice notice) {
        if (added.isEmpty()) return 0;
        if (notice == null || !Objects.equals(notice.regionId(), regionId)) {
            return notifyAssignedTo(ownerType, ownerId, added, null);
        }
        EmailEntityType type = madeType(ownerType);
        String label = (type == EmailEntityType.DISPUTE ? "Dispute #" : "Promise #") + ownerId;
        return tellAssigned(ownerType, added,
                new EmailTargets.Target(type, ownerId, label, customerId, regionId, Map.of(), List.of()),
                notice.recipients(), null);
    }

    private int tellAssigned(String ownerType, List<EmailToken> added, EmailTargets.Target target,
                             Map<String, List<User>> recipients, Long exceptUserId) {
        String label = target.label();
        return notifyAssigned(ownerType, added, target, recipients, exceptUserId, ownerType + "_ASSIGNED",
                "Assigned to you: " + label, "You are now assigned to " + label + ".", target.link());
    }

    private static EmailEntityType madeType(String ownerType) {
        return switch (ownerType) {
            case AssignedTo.DISPUTE -> EmailEntityType.DISPUTE;
            case AssignedTo.PROMISE -> EmailEntityType.PROMISE;
            default -> throw new IllegalArgumentException("No record-level notice for " + ownerType);
        };
    }

    /**
     * Staff to pick as assignees (SC-18): active people holding any grant in the record's region (when a record is
     * named; 404 if the caller cannot see it), or else in the regions the caller holds and is asking about. Never
     * the whole directory (R17).
     */
    @Transactional(readOnly = true)
    public List<EmailDtos.PeopleDto> people(String q, String entityType, Long entityId) {
        if (currentUser.isCustomer()) throw new AccessDeniedException("Not allowed");
        Set<Long> regions;
        if (entityType != null && !entityType.isBlank() && entityId != null) {
            Long regionId = targets.load(EmailEntityType.parse(entityType), entityId).regionId();
            regions = regionId == null ? Set.of() : Set.of(regionId);
        } else {
            regions = ViewScopeHolder.current().narrow(currentUser.rights().grantedRegions());
        }
        if (regions.isEmpty()) return List.of();
        String pattern = q == null || q.isBlank() ? null
                : "%" + Strings.escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
        List<User> found = grantRepository.findStaffInRegions(regions, pattern, PageRequest.of(0, PEOPLE_LIMIT));
        Map<Long, GmailStatus> gmail = gmailConnections.statuses(found.stream().map(User::getId).toList());
        return found.stream()
                .map(u -> new EmailDtos.PeopleDto(u.getId(), name(u), u.getUsername(), Emails.normalize(u.getEmail()),
                        gmail.getOrDefault(u.getId(), GmailStatus.NOT_CONNECTED)))
                .toList();
    }

    private Map<Long, User> users(List<Long> ids) {
        Map<Long, User> byId = new HashMap<>();
        List<Long> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (!wanted.isEmpty()) userRepository.findAllById(wanted).forEach(u -> byId.put(u.getId(), u));
        return byId;
    }

    private String nameOf(Long userId) {
        return userId == null ? "Someone" : userRepository.findById(userId).map(AssigneeService::name)
                .orElse("User #" + userId);
    }

    public static String name(User u) {
        return u.getFullName() == null || u.getFullName().isBlank() ? u.getUsername() : u.getFullName().trim();
    }
}
