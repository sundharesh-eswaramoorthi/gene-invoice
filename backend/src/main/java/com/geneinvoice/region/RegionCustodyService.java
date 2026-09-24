package com.geneinvoice.region;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.poc.PocService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Moving an account between branches: the ONLY writer of customers.region_id.
 *
 * <p>The column and customer_region_history are two halves of one fact — the column is the fast
 * path for "now" and the interval table answers "as of a date" — so they are written here, in one
 * transaction, and {@code Customer.setRegion} is package-private to the customer package so
 * nothing else can move an account and leave the two disagreeing (B1).
 *
 * <p>Everything hanging off the account moves with it instantly and for free: its invoices,
 * payments, promises, disputes, POC seats, documents and emails have no region of their own, so
 * there is no cascade, no chunked child rewrite, no window in which a child disagrees with its
 * parent, and no race with concurrent record creation — an invoice raised in the same instant
 * reads its region through its customer at query time and is correct whichever side of the commit
 * it lands on. That is why InvoiceService.create needs no row lock for B1 (B1).
 */
@Service
@RequiredArgsConstructor
public class RegionCustodyService {

    /** <= 40 characters, AuditLog.action's limit, and the name the blueprint fixes (B1). */
    public static final String ACTION_REGION_CHANGED = "CUSTOMER_REGION_CHANGED";

    private final CustomerRepository customerRepository;
    // The seam Customer.setRegion's package-private level makes necessary: this service lives in
    // com.geneinvoice.region and cannot reach a customer-package setter, and widening that setter
    // would let anything at all move an account without writing the placement row (B1).
    private final CustomerService customerService;
    private final RegionRepository regionRepository;
    private final CustomerRegionHistoryRepository historyRepository;
    private final RegionAccess regionAccess;
    private final PocService pocService;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    // Empty today, and an ObjectProvider rather than a List because Spring refuses to inject a
    // collection with no candidates at all — which is exactly the state this seam ships in (B1).
    private final ObjectProvider<CustomerMoved> movedListeners;

    /** The request body of POST /api/customers/{id}/region. reason is customer_region_history's. */
    public record MoveRegionRequest(
            @NotNull Long toRegionId,
            LocalDate effectiveFrom,
            @Size(max = 300) String reason
    ) {}

    /**
     * What the move did, named by code as well as by id so the answer is readable without a second
     * request. pocSeatsVacated is the count of seats given up because their holder cannot manage
     * the destination (B1).
     */
    public record MoveResult(Long customerId,
                             Long fromRegionId, String fromRegionCode,
                             Long toRegionId, String toRegionCode,
                             LocalDate effectiveFrom, int pocSeatsVacated) {}

    /** The audit blob: the only thing a move changes is where the account is (B1). */
    public record Placement(Long regionId, String regionCode) {}

    @Transactional
    public MoveResult move(Long customerId, Long toRegionId, LocalDate effectiveFrom, String reason) {
        if (toRegionId == null) {
            throw new BadRequestException("A move has to name a region");
        }
        // The customer row lock FIRST, which is the money path's established order (PPD-01): two
        // simultaneous moves serialise on it, so the history can never hold two open rows. On H2
        // that lock is the only thing holding the invariant at all — uk_crh_open is a Postgres
        // partial index — which is the same documented asymmetry PocSchemaUpgrade already has (B1).
        Customer customer = customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        Region from = customer.getRegion();
        if (from == null) {
            throw new IllegalStateException("Customer " + customerId + " has no region to move from");
        }
        Region to = regionRepository.findById(toRegionId)
                .filter(Region::isActive)
                .orElseThrow(() -> new BadRequestException("Unknown or inactive region"));

        // You named BOTH regions, so a refusal is 403 and not 404: no id space is being probed, and
        // pretending the branch does not exist would be a lie the caller can disprove. A record you
        // merely REACHED is the other case and is emptied by the read predicate (B1, D-46, AUTH-08).
        regionAccess.require(from.getId(), RegionRight.MANAGE);
        regionAccess.require(to.getId(), RegionRight.MANAGE);

        // A write is stamped with the wall clock and never with the date a reader asked for, so
        // this is todayForWrite() and not today() (B1, B3).
        LocalDate at = effectiveFrom == null ? InvoiceDates.todayForWrite() : effectiveFrom;
        if (from.getId().equals(to.getId())) {
            // Already there. Not an error and not a placement: nothing is closed, nothing is
            // opened, nothing is audited and no listener is told a move happened (B1).
            return new MoveResult(customerId, from.getId(), from.getCode(),
                    to.getId(), to.getCode(), at, 0);
        }

        CustomerRegionHistory open = historyRepository.findOpen(customerId)
                .orElseThrow(() -> new IllegalStateException(
                        "No open placement for customer " + customerId));
        if (at.isBefore(open.getValidFrom())) {
            // Half-open intervals cannot overlap, so a move dated before the account arrived here
            // would either rewrite a placement that already happened or leave a gap no as-of read
            // can resolve. Refused rather than silently clamped (B1).
            throw new BadRequestException("A move cannot be dated before the account arrived here on "
                    + open.getValidFrom());
        }

        open.setValidTo(at);
        // Flushed BEFORE the new placement is written, because Hibernate's action queue runs every
        // INSERT ahead of every UPDATE: without this, the row opening the destination reaches
        // Postgres while the row it replaces is still open, and uk_crh_open — the partial unique
        // index that makes "exactly one open placement" a database invariant — rejects the whole
        // move with a 409. H2 has no such index, so the one place this could show up is the one
        // place the suite cannot reach; the same reason RegionController.setGrants flushes (B1).
        historyRepository.saveAndFlush(open);
        historyRepository.save(CustomerRegionHistory.builder()
                .customerId(customerId).regionId(to.getId()).validFrom(at)
                // Null when headless: a fabricated actor id would be a lie in the ledger (B1, A5).
                .movedByUserId(currentUser.idOrNull()).reason(reason).build());
        customerService.placeIn(customer, to);

        // A POC who cannot manage the destination keeps no seat on the account (B1, D-44).
        int vacated = pocService.vacateSeatsWhoseHolderCannotManage(customerId, to.getId());

        // customer_region_history IS the per-record history for this dimension, so ONE row on the
        // customer is the whole audit — not one blob per moved invoice (B1).
        auditService.record(CustomerService.ENTITY, customerId, ACTION_REGION_CHANGED,
                new Placement(from.getId(), from.getCode()),
                new Placement(to.getId(), to.getCode()),
                currentUser.idOrNull(), null,
                "Moved from " + from.getCode() + " to " + to.getCode() + " effective " + at
                        + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()));

        // Last, and inside this transaction: everything that hangs off the account followed it for
        // free, and the one thing that cannot follow silently is work somebody raised in the branch
        // the account has just left (B1).
        movedListeners.orderedStream().forEach(l -> l.moved(customerId, from.getId(), to.getId()));

        return new MoveResult(customerId, from.getId(), from.getCode(),
                to.getId(), to.getCode(), at, vacated);
    }
}
