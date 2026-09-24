package com.geneinvoice.poc;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.Strings;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.region.RegionAccess;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.UserRegionGrantRepository;
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
    // The seat mirror, read on ONE path only: "who sat here on the date asked about".
    // A seat that was vacated left no live row behind it, so the live table cannot answer
    // that question at all (B3).
    private final CustomerPocHistoryRepository customerPocHistoryRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final CurrentUser currentUser;
    // Read rather than asked of the principal: the subject of the question is the SEAT HOLDER and
    // not the caller, so it is the grant table that answers it (B1).
    private final UserRegionGrantRepository userRegionGrantRepository;
    // Only ever asked for a code, and only on the way to a refusal message: an operator told
    // "does not work in NORTH" can act on it, one told "does not work in region 7" cannot (B1).
    private final RegionRepository regionRepository;
    // The write-side gate, and a DIFFERENT question from requireAssignable: that one asks whether
    // the PERSON being seated works there, this one asks whether the CALLER may change this
    // account's book at all. Both are needed and neither implies the other (B1).
    private final RegionAccess regionAccess;

    /**
     * Who may be offered this seat in this branch. The picker only offers people who work there,
     * because a name it offers and the gate then refuses is a name that should never have been on
     * the list (B1).
     *
     * @param regionId the branch the seat is in, or null when the caller named none — which only
     *                 a wildcard holder may do, and which then means "assignable anywhere at all"
     */
    @Transactional(readOnly = true)
    public List<User> assignable(PocType type, Long regionId, String query, int limit) {
        // The picker's box is a plain substring search, so the user's own % and _ match only
        // themselves rather than acting as wildcards (CP-11).
        String pattern = (query == null || query.isBlank())
                ? null
                : "%" + Strings.escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%";
        int capped = Math.min(Math.max(limit, 1), 100);
        return userRepository.findAssignableInRegion(type.assignabilityPrivilege(), regionId,
                pattern, PageRequest.of(0, capped));
    }

    /**
     * The gate every seat and every per-record POC field passes through. A seat is only valid if
     * its holder can MANAGE the customer's region: the book axis and the region axis are ANDed by
     * the query funnel, so a POC named in a branch they cannot work in would hold a seat that
     * shows them nothing and routes them mail they cannot act on (B1).
     *
     * <p>A null regionId means the branch is not named AT THIS CALL SITE and the region half of
     * the check is left to the per-record path. There is exactly one such caller — the ADD_POC
     * bulk pre-flight in CustomerController, which spans as many branches as the selection does —
     * and every row it then touches re-enters through {@link #add}, which passes the customer's
     * own region. Nothing else may pass null (B1).
     */
    @Transactional(readOnly = true)
    public User requireAssignable(Long userId, PocType type, Long regionId) {
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
        // The rejection names the branch by its code rather than its id, and it is a 400 and not a
        // 403 because the caller is being told something about the PERSON they named, not about
        // their own reach — refusing with "you have no access" would be a lie (B1, D-46).
        if (regionId != null && !userRegionGrantRepository.covers(userId, regionId, RegionRight.MANAGE)) {
            throw new BadRequestException("User " + u.getUsername() + " does not work in "
                    + regionRepository.codeOf(regionId) + " and cannot be its " + type.label());
        }
        return u;
    }

    @Transactional(readOnly = true)
    public Optional<User> callerIfAssignable(PocType type) {
        User caller = currentUser.require();
        if (caller.getCustomerId() != null) return Optional.empty();
        return userRepository.hasPrivilege(caller.getId(), type.assignabilityPrivilege())
                ? Optional.of(caller)
                : Optional.empty();
    }

    @Transactional(readOnly = true)
    public List<CustomerPoc> listFor(Long customerId) {
        return customerPocRepository.findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(customerId);
    }

    /**
     * WHO SAT ON THIS ACCOUNT, LIVE OR ON THE DATE ASKED ABOUT (B3).
     *
     * <p>"Who was the primary collection POC on 31 January" is a question the live table cannot
     * answer at all — a seat that was vacated left no row behind it — so under an open context
     * this reads the seat mirror instead, with the same interval rule every other as-of read uses.
     *
     * <p>It returns DTOs and not entities because the two roots are different types and only one
     * of them is a {@code CustomerPoc}; {@link #listFor} keeps its entity return for the write
     * paths, which are never asked as of a date. The mapping is
     * {@code PocDtos.CustomerPocDto.from}'s two overloads, so the two answers cannot drift into
     * different shapes.
     */
    @Transactional(readOnly = true)
    public List<PocDtos.CustomerPocDto> seatsFor(Long customerId) {
        if (!AsOfContext.isActive()) {
            return listFor(customerId).stream().map(PocDtos.CustomerPocDto::from).toList();
        }
        return customerPocHistoryRepository.inForce(List.of(customerId), AsOfContext.instant())
                .stream().map(PocDtos.CustomerPocDto::from).toList();
    }

    @Transactional(readOnly = true)
    public Optional<User> primaryFor(Long customerId, PocType type) {
        // The oldest of them, on the off-chance a database from before the invariant was the
        // database's own still holds two: a reader answers, it does not fail (CP-02).
        return customerPocRepository.findByCustomerIdAndPocTypeAndPrimaryTrueOrderByIdAsc(customerId, type)
                .stream().findFirst().map(CustomerPoc::getUser);
    }

    @Transactional(readOnly = true)
    public Optional<User> defaultAssignee(Long customerId, PocType type) {
        return activeHolders(customerId, type).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<User> activeHolders(Long customerId, PocType type) {
        return activeHoldersByType(customerId).getOrDefault(type, List.of());
    }

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
        // .getId() on a lazy Region proxy initialises nothing, so the seat's region costs no
        // extra read on the way to the two checks that use it (B1).
        regionAccess.requireManage(customer.getRegion().getId());
        User user = requireAssignable(userId, type, customer.getRegion().getId());

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

    public static class AlreadyAssignedException extends BadRequestException {
        public AlreadyAssignedException(String message) {
            super(message);
        }
    }

    @Transactional
    public void remove(Long customerId, Long pocId) {
        Customer customer = lockCustomer(customerId);
        // Taking a seat away is as much a change to the account's book as giving one (B1).
        regionAccess.requireManage(customer.getRegion().getId());
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
    }

    /**
     * The seats a move orphans. A POC who cannot MANAGE the region the account has just moved to
     * has no business holding a seat on it, so the seat is given up — through {@link #remove},
     * which already promotes the oldest remaining seat to primary and audits POC_REMOVED and
     * POC_PRIMARY_CHANGED, so a move leaves exactly the trail a hand-made removal would (B1, D-44).
     *
     * <p>Per-record POC fields (Invoice.salesPoc, Payment.collectionPoc and PaymentPromise's, the
     * last of which is optional=false and cannot be nulled) are deliberately left in place: the
     * book axis and the region axis are ANDed by the query funnel, so a stale POC stops granting
     * visibility the moment the account moves and never leaks (B1).
     *
     * @return how many seats were vacated, for the move's own answer
     */
    @Transactional
    public int vacateSeatsWhoseHolderCannotManage(Long customerId, Long toRegionId) {
        int vacated = 0;
        // The whole list is read before any of it is removed: remove() promotes a new primary and
        // flushes, so iterating a live query while deleting from under it would be a wager (B1).
        for (CustomerPoc seat : customerPocRepository
                .findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(customerId)) {
            if (userRegionGrantRepository.covers(seat.getUser().getId(), toRegionId, RegionRight.MANAGE)) {
                continue;
            }
            remove(customerId, seat.getId());
            vacated++;
        }
        return vacated;
    }

    @Transactional
    public CustomerPoc setPrimary(Long customerId, Long pocId) {
        // Clearing the old primary and marking the new one is one change, and has to happen
        // inside one serialised transaction: two people tapping different chips at the same
        // moment used to leave both seats primary (CP-02).
        Customer customer = lockCustomer(customerId);
        // Which of them is primary decides who the account's mail is addressed to, so it is a
        // write in the account's branch like the other two (B1).
        regionAccess.requireManage(customer.getRegion().getId());
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

    public void notifyAssignee(User assignee, PocType type, String what, String link) {
        if (assignee == null) return;
        Long actor = currentUser.require().getId();
        if (assignee.getId().equals(actor)) return;
        notificationService.notify(assignee.getId(), NOTIF_POC_ASSIGNED,
                "You are now " + type.label(),
                "You have been assigned as " + type.label() + " for " + what + ".",
                link);
    }

    public record PocAuditSnapshot(String pocType, Long userId, String username, boolean primary) {}
}
