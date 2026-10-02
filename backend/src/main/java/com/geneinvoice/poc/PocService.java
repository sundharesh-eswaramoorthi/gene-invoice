package com.geneinvoice.poc;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.automation.event.AutomationEvents;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.Strings;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.history.HistoryQueries;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionAccessQueries;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.UserRegionGrantRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PocService {

    public static final String AUDIT_POC_ASSIGNED = "POC_ASSIGNED";
    public static final String AUDIT_POC_REMOVED = "POC_REMOVED";
    public static final String AUDIT_POC_PRIMARY_CHANGED = "POC_PRIMARY_CHANGED";
    private static final String NOTIF_POC_ASSIGNED = "POC_ASSIGNED";
    /** The change label of a customer whose POC seats changed: the seat columns of the customers list. */
    private static final String SEATS_CHANGED = "POC_SEATS";

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final CustomerPocRepository customerPocRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final CurrentUser currentUser;
    private final RegionAccessQueries regionAccess;
    private final UserRegionGrantRepository grantRepository;
    private final RegionRepository regionRepository;
    private final HistoryQueries historyQueries;
    private final AutomationEvents automationEvents;

    /**
     * People who may be picked as this kind of POC in any of these regions: active staff whose role holds the
     * assignability privilege and whose grant there reaches its level. No regions, nobody.
     */
    @Transactional(readOnly = true)
    public List<User> assignable(PocType type, Collection<Long> regionIds, String query, int limit) {
        if (regionIds == null || regionIds.isEmpty()) return List.of();
        // The picker's box is a plain substring search, so the user's own % and _ match only
        // themselves rather than acting as wildcards (CP-11).
        String pattern = (query == null || query.isBlank())
                ? null
                : "%" + Strings.escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%";
        int capped = Math.min(Math.max(limit, 1), 100);
        String privilege = type.assignabilityPrivilege();
        return grantRepository.findAssignableInRegions(privilege, regionIds,
                AccessLevel.atLeast(Privileges.requiredLevel(privilege)), pattern, PageRequest.of(0, capped));
    }

    /**
     * Someone who may be this kind of POC on a record in this region: today's checks, and then a grant in the
     * region that reaches the privilege's level. A person who cannot see the region cannot hold a role in it.
     */
    @Transactional(readOnly = true)
    public User requireAssignable(Long userId, PocType type, Long regionId) {
        User u = requireAssignableByRole(userId, type);
        if (!regionAccess.holds(userId, type.assignabilityPrivilege(), regionId)) {
            String code = regionId == null ? "?" : regionRepository.findById(regionId).map(Region::getCode).orElse("?");
            throw new BadRequestException("User " + u.getUsername() + " is not assignable as " + type.label()
                    + " in region " + code);
        }
        return u;
    }

    /**
     * Today's checks alone, before any region: active, internal, and a role that may hold the POC. For a request
     * that spans regions (bulk ADD_POC), which then holds each record to its own region through {@link #add}.
     */
    @Transactional(readOnly = true)
    public User requireAssignableByRole(Long userId, PocType type) {
        if (userId == null) {
            throw new BadRequestException(type.label() + " is required");
        }
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        if (!u.isActive()) {
            throw new BadRequestException("User " + u.getUsername() + " is inactive and cannot be assigned");
        }
        if (u.getCustomerId() != null) {
            throw new BadRequestException("A customer account cannot be assigned as a POC");
        }
        if (!userRepository.hasPrivilege(userId, type.assignabilityPrivilege())) {
            throw new BadRequestException(
                    "User " + u.getUsername() + " is not assignable as " + type.label());
        }
        return u;
    }

    /** The customer's seats, by type, the primary first; as of a date, the seats it had then (§5.7.17). */
    @Transactional(readOnly = true)
    public List<CustomerPoc> listFor(Long customerId) {
        AsOfPoint point = AsOf.current();
        if (point != null) return historyQueries.seatsAt(List.of(customerId), point);
        return customerPocRepository.findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(customerId);
    }

    @Transactional(readOnly = true)
    public Optional<User> defaultAssignee(Long customerId, PocType type) {
        return activeHolders(customerId, type).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<User> activeHolders(Long customerId, PocType type) {
        return activeHoldersByType(customerId).getOrDefault(type, List.of());
    }

    /**
     * The customer's active seat holders by kind, primary first. Someone with no access to the customer's region
     * holds no role on it (§5.6.7): they are left out, so they are neither emailed as the role nor defaulted to.
     */
    @Transactional(readOnly = true)
    public Map<PocType, List<User>> activeHoldersByType(Long customerId) {
        Map<PocType, List<User>> byType = new EnumMap<>(PocType.class);
        for (CustomerPoc seat : customerPocRepository.findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(customerId)) {
            if (seat.getUser().isActive()) {
                byType.computeIfAbsent(seat.getPocType(), t -> new ArrayList<>()).add(seat.getUser());
            }
        }
        if (byType.isEmpty()) return byType;
        Long regionId = customerRepository.regionIdOf(customerId).orElse(null);
        Set<Long> reach = regionAccess.withAccess(byType.values().stream().flatMap(List::stream)
                .map(User::getId).collect(Collectors.toSet()), regionId);
        Map<PocType, List<User>> holders = new EnumMap<>(PocType.class);
        byType.forEach((type, users) -> {
            List<User> kept = users.stream().filter(u -> reach.contains(u.getId())).toList();
            if (!kept.isEmpty()) holders.put(type, kept);
        });
        return holders;
    }

    /** Those of these people who hold any grant in the region, in their order; one grants query. */
    public List<User> withRegionAccess(List<User> users, Long regionId) {
        if (users.isEmpty()) return users;
        Set<Long> reach = regionAccess.withAccess(users.stream().map(User::getId).collect(Collectors.toSet()),
                regionId);
        return users.stream().filter(u -> reach.contains(u.getId())).toList();
    }

    @Transactional
    public CustomerPoc add(Long customerId, PocType type, Long userId, boolean makePrimary) {
        if (type == PocType.SALES) {
            throw new BadRequestException("Sales POC is assigned per invoice, not per customer");
        }
        // The customer's own row first, so two people seating a POC at the same moment queue
        // rather than both finding an empty group, both calling themselves the first seat and
        // both ending up primary — after which "make primary" could never be answered (CP-02).
        Customer customer = lockCustomer(customerId);
        Long regionId = requireAssignIn(customer);
        User user = requireAssignable(userId, type, regionId);

        if (customerPocRepository.findByCustomerIdAndUserIdAndPocType(customerId, userId, type).isPresent()) {
            throw new AlreadyAssignedException(user.getUsername() + " is already a " + type.label()
                    + " for this customer");
        }

        boolean first = customerPocRepository.findByCustomerIdAndPocTypeForUpdate(customerId, type).isEmpty();
        boolean primary = makePrimary || first;
        CustomerPoc demoted = primary ? clearPrimary(customerId, type) : null;

        CustomerPoc poc = customerPocRepository.save(CustomerPoc.builder()
                .customer(customer)
                .user(user)
                .pocType(type)
                .primary(primary)
                .createdByUserId(currentUser.require().getId())
                .build());

        auditService.record("CUSTOMER", customerId, AUDIT_POC_ASSIGNED,
                null,
                new PocAuditSnapshot(type.name(), user.getId(), user.getUsername(), primary),
                currentUser.require().getId(), null,
                "Added " + type.label() + " " + user.getUsername());

        // The seat that lost "primary" to this one changed too, and its history should say so (D-44).
        if (demoted != null) {
            auditService.record("CUSTOMER", customerId, AUDIT_POC_PRIMARY_CHANGED,
                    new PocAuditSnapshot(type.name(), demoted.getUser().getId(),
                            demoted.getUser().getUsername(), true),
                    new PocAuditSnapshot(type.name(), user.getId(), user.getUsername(), true),
                    currentUser.require().getId(), null,
                    "Primary " + type.label() + " is now " + user.getUsername());
        }
        automationEvents.updated(TriggerEntity.CUSTOMER, customerId, SEATS_CHANGED);

        notifyAssignee(user, type, "customer " + customer.getName(), "/customers/" + customerId, regionId);
        return poc;
    }

    public static class AlreadyAssignedException extends BadRequestException {
        public AlreadyAssignedException(String message) {
            super(message);
        }
    }

    @Transactional
    public void remove(Long customerId, Long pocId) {
        requireAssignIn(lockCustomer(customerId));
        CustomerPoc poc = customerPocRepository.findById(pocId)
                .orElseThrow(() -> new NotFoundException("POC assignment not found"));
        if (!poc.getCustomer().getId().equals(customerId)) {
            throw new BadRequestException("POC assignment does not belong to this customer");
        }
        PocType type = poc.getPocType();
        boolean wasPrimary = poc.isPrimary();
        User removed = poc.getUser();

        customerPocRepository.delete(poc);
        customerPocRepository.flush();

        CustomerPoc promoted = null;
        if (wasPrimary) {
            List<CustomerPoc> remaining =
                    customerPocRepository.findByCustomerIdAndPocTypeForUpdate(customerId, type);
            // Any other seat still marked primary is cleared first. Promoting on top of one was
            // how a customer left with two primaries could never be repaired by removing a
            // seat — the count stayed at two and "make primary" went on failing (CP-02).
            for (CustomerPoc other : remaining) {
                if (other.isPrimary()) {
                    other.setPrimary(false);
                    customerPocRepository.save(other);
                }
            }
            promoted = remaining.stream().min(Comparator.comparing(CustomerPoc::getId)).orElse(null);
            if (promoted != null) {
                promoted.setPrimary(true);
                customerPocRepository.save(promoted);
            }
            customerPocRepository.flush();
        }

        auditService.record("CUSTOMER", customerId, AUDIT_POC_REMOVED,
                new PocAuditSnapshot(type.name(), removed.getId(), removed.getUsername(), wasPrimary),
                null,
                currentUser.require().getId(), null,
                "Removed " + type.label() + " " + removed.getUsername());

        // Someone else became primary without anyone asking for it; that belongs in the history (D-44).
        if (promoted != null) {
            auditService.record("CUSTOMER", customerId, AUDIT_POC_PRIMARY_CHANGED,
                    new PocAuditSnapshot(type.name(), removed.getId(), removed.getUsername(), true),
                    new PocAuditSnapshot(type.name(), promoted.getUser().getId(),
                            promoted.getUser().getUsername(), true),
                    currentUser.require().getId(), null,
                    "Primary " + type.label() + " is now " + promoted.getUser().getUsername());
        }
        automationEvents.updated(TriggerEntity.CUSTOMER, customerId, SEATS_CHANGED);
    }

    @Transactional
    public CustomerPoc setPrimary(Long customerId, Long pocId) {
        // Clearing the old primary and marking the new one is one change, and has to happen
        // inside one serialised transaction: two people tapping different chips at the same
        // moment used to leave both seats primary (CP-02).
        requireAssignIn(lockCustomer(customerId));
        CustomerPoc poc = customerPocRepository.findById(pocId)
                .orElseThrow(() -> new NotFoundException("POC assignment not found"));
        if (!poc.getCustomer().getId().equals(customerId)) {
            throw new BadRequestException("POC assignment does not belong to this customer");
        }
        CustomerPoc previous = clearPrimary(customerId, poc.getPocType());
        poc.setPrimary(true);
        CustomerPoc saved = customerPocRepository.save(poc);

        auditService.record("CUSTOMER", customerId, AUDIT_POC_PRIMARY_CHANGED,
                previous == null ? null : new PocAuditSnapshot(previous.getPocType().name(),
                        previous.getUser().getId(), previous.getUser().getUsername(), true),
                new PocAuditSnapshot(poc.getPocType().name(), poc.getUser().getId(),
                        poc.getUser().getUsername(), true),
                currentUser.require().getId(), null,
                "Primary " + poc.getPocType().label() + " is now " + poc.getUser().getUsername());
        automationEvents.updated(TriggerEntity.CUSTOMER, customerId, SEATS_CHANGED);
        return saved;
    }

    private CustomerPoc clearPrimary(Long customerId, PocType type) {
        CustomerPoc was = null;
        for (CustomerPoc existing : customerPocRepository.findByCustomerIdAndPocTypeForUpdate(customerId, type)) {
            if (existing.isPrimary()) {
                existing.setPrimary(false);
                CustomerPoc demoted = customerPocRepository.save(existing);
                if (was == null) was = demoted;
            }
        }
        customerPocRepository.flush();
        return was;
    }

    private Customer lockCustomer(Long customerId) {
        return customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
    }

    /** Seats are changed only where the caller may assign POCs, read under the customer's lock (§5.6.6). */
    private Long requireAssignIn(Customer customer) {
        ViewScopeHolder.requireLive();
        Long regionId = Region.idOf(customer.getRegion());
        currentUser.requireIn(Privileges.POC_ASSIGN, regionId);
        return regionId;
    }

    /** Tells a new POC; one who cannot reach the record's region is not sent a link they cannot open (SC-17). */
    public void notifyAssignee(User assignee, PocType type, String what, String link, Long regionId) {
        if (assignee == null) return;
        Long actor = currentUser.require().getId();
        if (assignee.getId().equals(actor)) return;
        if (!regionAccess.withAccess(List.of(assignee.getId()), regionId).contains(assignee.getId())) return;
        notificationService.notify(assignee.getId(), NOTIF_POC_ASSIGNED,
                "You are now " + type.label(),
                "You have been assigned as " + type.label() + " for " + what + ".",
                link, regionId);
    }

    public record PocAuditSnapshot(String pocType, Long userId, String username, boolean primary) {}
}
