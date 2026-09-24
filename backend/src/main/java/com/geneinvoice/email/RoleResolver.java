package com.geneinvoice.email;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.region.RegionAccess;
import com.geneinvoice.region.RegionRight;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The ONE email type the automation package depends on: everything a rule needs to know about who
 * holds which role on a record, with no signed-in person anywhere in the call (A3, A4).
 *
 * <p>It exists so that {@code com.geneinvoice.automation} imports one class rather than four, and
 * so that the single unscoped read in the email package — {@link EmailTargets#describeUnscoped} —
 * has exactly one caller, which is this one, which always asks the region question first (B1).
 */
@Component
@RequiredArgsConstructor
public class RoleResolver {

    private final EmailTargets targets;
    // The write-side gate, used here on a READ on purpose: see snapshot below (B1, A3).
    private final RegionAccess regionAccess;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;

    /** The roles a subject of this kind offers, at both levels, in the To picker's order (A3). */
    public List<RoleRef> offered(EmailEntityType type) {
        return targets.rolesOffered(type);
    }

    /** The same rule and the same 400 text the To field gives, from the method that owns it (A3). */
    public RoleRef toRoleRef(EmailEntityType type, EmailDtos.EmailToken token) {
        return EmailAddressing.offeredRole(type, targets.rolesOffered(type), token);
    }

    /**
     * Who holds what on one record, read without a principal.
     *
     * <p>The region is asked FIRST and the record is described second, because describeUnscoped
     * has no check of its own: under the narrowing hatch {@code RegionScope.asRegions}, bounded by
     * the rule's own regions, this is exactly that bound; on a request path it is the caller's own
     * grants. (The name is written without its argument list because RegionCoverageTest reads the
     * main sources for hatch CALL SITES textually, and a comment is not one.) There is no
     * configuration in which it is unbounded. It is a refusal and not an empty answer because a
     * headless engine is not probing an id space — it was handed this id by its own event (B1, A3).
     */
    @Transactional(readOnly = true)
    public EmailTargets.Target snapshot(EmailEntityType type, Long id) {
        regionAccess.require(regionOf(type, id), RegionRight.VIEW);
        return targets.describeUnscoped(type, id);
    }

    public List<EmailTargets.Person> holders(EmailTargets.Target target, RoleRef role) {
        return target.holders(role);
    }

    /**
     * "A role held by several people resolves to the primary" (A4): the first of a list the POC
     * book returns primary-first and filtered to active users — the same person Target.sender
     * picks when a role is the From (L5). NO SECOND DEFINITION OF PRIMARY IS INVENTED.
     *
     * <p>The consequence, matched to shipped behaviour rather than to an ideal: a DEACTIVATED
     * primary falls through to the next active holder. PocService.primaryFor implements the other
     * answer, ignores isActive and has no main-code caller; it is deliberately not used (A4).
     */
    public Optional<EmailTargets.Person> primary(EmailTargets.Target target, RoleRef role) {
        return target.sender(role);
    }

    /**
     * The branch the record's account is in, resolved BEFORE anything is described.
     *
     * <p>Only the three kinds of record a rule can have a subject of are resolvable here. Anything
     * else would need a region resolution of its own, and waving it through would turn the one
     * unscoped read in the email package into a way to read any promise, dispute, product or
     * person by id with no check at all (A3, B1).
     */
    private Long regionOf(EmailEntityType type, Long id) {
        if (id == null) throw new BadRequestException("A rule's subject needs an id");
        Long customerId = switch (type) {
            case CUSTOMER -> customerRepository.findById(id).map(Customer::getId).orElse(null);
            case INVOICE -> invoiceRepository.findById(id).map(i -> i.getCustomer().getId()).orElse(null);
            case PAYMENT -> paymentRepository.findById(id).map(p -> p.getCustomer().getId()).orElse(null);
            default -> throw new BadRequestException(
                    "A rule's subject must be a customer, an invoice or a payment");
        };
        // A record that is not there is 404 and not 403, and it says so before the region gate so
        // the two answers agree with describeUnscoped's own (AUTH-08).
        if (customerId == null) throw new NotFoundException(type.title() + " not found");
        // .getId() on the lazy Region proxy initialises nothing (B1). A null here is fail-closed:
        // RegionGrants.may(null, ...) is false for everybody but a wildcard holder.
        return customerRepository.findById(customerId)
                .map(c -> c.getRegion().getId()).orElse(null);
    }
}
