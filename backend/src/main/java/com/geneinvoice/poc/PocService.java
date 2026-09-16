package com.geneinvoice.poc;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
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
        String pattern = (query == null || query.isBlank())
                ? null
                : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
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
        return customerPocRepository.findByCustomerIdAndPocTypeAndPrimaryTrue(customerId, type)
                .map(CustomerPoc::getUser);
    }

    /**
     * Who a new record defaults to: the primary seat holder or, once they have been deactivated,
     * the next active holder of that kind of seat. Empty when the customer has no active one.
     */
    @Transactional(readOnly = true)
    public Optional<User> defaultAssignee(Long customerId, PocType type) {
        return customerPocRepository.findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(customerId).stream()
                .filter(seat -> seat.getPocType() == type && seat.getUser().isActive())
                .map(CustomerPoc::getUser)
                .findFirst();
    }

    @Transactional
    public CustomerPoc add(Long customerId, PocType type, Long userId, boolean makePrimary) {
        if (type == PocType.SALES) {
            throw new BadRequestException("Sales POC is assigned per invoice, not per customer");
        }
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        User user = requireAssignable(userId, type);

        if (customerPocRepository.findByCustomerIdAndUserIdAndPocType(customerId, userId, type).isPresent()) {
            throw new AlreadyAssignedException(user.getUsername() + " is already a " + type.label()
                    + " for this customer");
        }

        boolean first = customerPocRepository.findByCustomerIdAndPocType(customerId, type).isEmpty();
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
            promoted = customerPocRepository.findByCustomerIdAndPocType(customerId, type).stream()
                    .min((a, b) -> Long.compare(a.getId(), b.getId()))
                    .orElse(null);
            if (promoted != null) {
                promoted.setPrimary(true);
                customerPocRepository.save(promoted);
            }
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
        CustomerPoc poc = customerPocRepository.findById(pocId)
                .orElseThrow(() -> new NotFoundException("POC assignment not found"));
        if (!poc.getCustomer().getId().equals(customerId)) {
            throw new BadRequestException("POC assignment does not belong to this customer");
        }
        CustomerPoc previous = customerPocRepository
                .findByCustomerIdAndPocTypeAndPrimaryTrue(customerId, poc.getPocType()).orElse(null);
        clearPrimary(customerId, poc.getPocType());
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

    /** Returns the seat that was primary, so the caller can record the change (D-44). */
    private CustomerPoc clearPrimary(Long customerId, PocType type) {
        CustomerPoc was = null;
        for (CustomerPoc existing : customerPocRepository.findByCustomerIdAndPocType(customerId, type)) {
            if (existing.isPrimary()) {
                existing.setPrimary(false);
                was = customerPocRepository.save(existing);
            }
        }
        customerPocRepository.flush();
        return was;
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
