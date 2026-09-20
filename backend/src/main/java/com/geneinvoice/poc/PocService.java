package com.geneinvoice.poc;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.Strings;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Assignment and lookup of the three points of contact. Assignability is driven by privileges
 * rather than role names, so one person's single role can make them assignable as several kinds.
 */
@Service
@RequiredArgsConstructor
public class PocService {

    public static final String AUDIT_POC_ASSIGNED = "POC_ASSIGNED";
    public static final String AUDIT_POC_REMOVED = "POC_REMOVED";
    public static final String AUDIT_POC_PRIMARY_CHANGED = "POC_PRIMARY_CHANGED";
    private static final String NOTIF_POC_ASSIGNED = "POC_ASSIGNED";

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final CustomerPocRepository customerPocRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final CurrentUser currentUser;

    // ---- assignable users ------------------------------------------------------

    @Transactional(readOnly = true)
    public List<User> assignable(PocType type, String query, int limit) {
        // The picker's box is a plain substring search, so the user's own % and _ match only
        // themselves rather than acting as wildcards (CP-11).
        String pattern = (query == null || query.isBlank())
                ? null
                : "%" + Strings.escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%";
        int capped = Math.min(Math.max(limit, 1), 100);
        return userRepository.findAssignable(type.assignabilityPrivilege(), pattern,
                PageRequest.of(0, capped));
    }

    /** Resolves a user id supplied for a POC field, rejecting anyone not assignable as that kind. */
    @Transactional(readOnly = true)
    public User requireAssignable(Long userId, PocType type) {
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

    /** The caller, when they are themselves assignable as this kind — used to pre-select a form. */
    @Transactional(readOnly = true)
    public Optional<User> callerIfAssignable(PocType type) {
        User caller = currentUser.require();
        if (caller.getCustomerId() != null) return Optional.empty();
        return userRepository.hasPrivilege(caller.getId(), type.assignabilityPrivilege())
                ? Optional.of(caller)
                : Optional.empty();
    }

    // ---- customer POC roster ---------------------------------------------------

    @Transactional(readOnly = true)
    public List<CustomerPoc> listFor(Long customerId) {
        return customerPocRepository.findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(customerId);
    }

    @Transactional(readOnly = true)
    public Optional<User> primaryFor(Long customerId, PocType type) {
        // The oldest of them, on the off-chance a database from before the invariant was the
        // database's own still holds two: a reader answers, it does not fail (CP-02).
        return customerPocRepository.findByCustomerIdAndPocTypeAndPrimaryTrueOrderByIdAsc(customerId, type)
                .stream().findFirst().map(CustomerPoc::getUser);
    }

    /**
     * Who a new record defaults to: the primary seat holder or, once they have been deactivated,
     * the next active holder of that kind of seat. Empty when the customer has no active one.
     */
    @Transactional(readOnly = true)
    public Optional<User> defaultAssignee(Long customerId, PocType type) {
        return activeHolders(customerId, type).stream().findFirst();
    }

    /**
     * Everyone active in that kind of seat on the customer, the primary first and then in the order
     * they were seated — so the first is always {@link #defaultAssignee}. Empty when nobody active is.
     */
    @Transactional(readOnly = true)
    public List<User> activeHolders(Long customerId, PocType type) {
        return activeHoldersByType(customerId).getOrDefault(type, List.of());
    }

    /**
     * The whole book in one read: every kind of seat the customer has, each with its active holders
     * in the order {@link #activeHolders} gives them. A caller that wants several kinds — the email
     * form offers all three (L2) — reads the roster once instead of once per kind.
     */
    @Transactional(readOnly = true)
    public Map<PocType, List<User>> activeHoldersByType(Long customerId) {
        Map<PocType, List<User>> byType = new EnumMap<>(PocType.class);
        for (CustomerPoc seat : customerPocRepository.findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(customerId)) {
            if (seat.getUser().isActive()) {
                byType.computeIfAbsent(seat.getPocType(), t -> new ArrayList<>()).add(seat.getUser());
            }
        }
        return byType;
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
        User user = requireAssignable(userId, type);

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

        notifyAssignee(user, type, "customer " + customer.getName(), "/customers/" + customerId);
        return poc;
    }

    /** The customer already has that person in that seat; a bulk caller reports this as skipped. */
    public static class AlreadyAssignedException extends BadRequestException {
        public AlreadyAssignedException(String message) {
            super(message);
        }
    }

    @Transactional
    public void remove(Long customerId, Long pocId) {
        lockCustomer(customerId);
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

        // Never leave a dangling primary: promote the oldest remaining holder of that kind (AC-A4).
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
    }

    @Transactional
    public CustomerPoc setPrimary(Long customerId, Long pocId) {
        // Clearing the old primary and marking the new one is one change, and has to happen
        // inside one serialised transaction: two people tapping different chips at the same
        // moment used to leave both seats primary (CP-02).
        lockCustomer(customerId);
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
        return saved;
    }

    /**
     * Demotes every seat of that kind currently marked primary and returns the oldest of them, so
     * the caller can record the change (D-44). Reading the group under the write lock is what
     * makes clear-then-set one indivisible change; demoting every match rather than one is what
     * lets a customer that already holds two primaries be repaired instead of jamming (CP-02).
     */
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

    /**
     * The customer's own row, locked until the transaction ends. Every change to who is primary
     * takes it first: the seat group it is about to rearrange may be empty, and an empty group
     * has no row to lock (CP-02).
     */
    private Customer lockCustomer(Long customerId) {
        return customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
    }

    /** Tells the newly assigned person, unless they assigned themselves. */
    public void notifyAssignee(User assignee, PocType type, String what, String link) {
        if (assignee == null) return;
        Long actor = currentUser.require().getId();
        if (assignee.getId().equals(actor)) return;
        notificationService.notify(assignee.getId(), NOTIF_POC_ASSIGNED,
                "You are now " + type.label(),
                "You have been assigned as " + type.label() + " for " + what + ".",
                link);
    }

    /** Serialised into the audit trail's before/after columns. */
    public record PocAuditSnapshot(String pocType, Long userId, String username, boolean primary) {}
}
