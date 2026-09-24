package com.geneinvoice.approval;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The queue's decision endpoints (B2).
 *
 * <p>All four are authorised at APPROVAL_VIEW and not at APPROVAL_APPROVE, on purpose: seeing the
 * change and deciding it are two different facts, and the second one is per-region. @PreAuthorize
 * answers "may you do this SOMEWHERE" and the region check, the maker check and the 404-for-a
 * -branch-you-hold-nothing-in rule all live in the service, where the change's own region is
 * known. Withdrawing is not an approval at all and is the maker's own right, which is the other
 * reason this annotation cannot be APPROVAL_APPROVE (B2, B1, AUTH-08).
 */
@RestController
@RequestMapping("/api/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService service;
    // A controller holding the bulk runner is the InvoiceController/PaymentController shape. It
    // has to be here and not in the service: BulkExecutor already depends on ApprovalService to
    // park a held row, and the field the other way would be a circular reference (B2).
    private final BulkExecutor bulkExecutor;

    /**
     * The queue. An ordinary table endpoint over ApprovalSchemas.APPROVALS, so the region
     * predicate, the locked chips, sorting, paging and every filter operator come from the same
     * funnel as /api/invoices — including {@code ?filter=targetType:eq:INVOICE&filter=targetId:
     * eq:7&filter=status:eq:PENDING}, which is how a detail screen asks what is waiting on the
     * record it is showing (B2, B1).
     */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.APPROVAL_VIEW + "')")
    public PageResponse<ApprovalDtos.PendingChangeDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        return service.page(TableQuery.parse(ApprovalSchemas.APPROVALS, page, size, sort,
                FilterParams.from(request)));
    }

    /**
     * Declared BEFORE "/{id}" for a reader's benefit only: a literal segment already out-ranks a
     * template one in Spring's pattern comparator, and {id} is a Long so "summary" could not bind
     * to it even if it did not (B2).
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('" + Privileges.APPROVAL_VIEW + "')")
    public ApprovalDtos.ApprovalSummaryTiles summary(HttpServletRequest request) {
        return service.tiles(TableQuery.parseUnpaged(ApprovalSchemas.APPROVALS, null,
                FilterParams.from(request)));
    }

    /**
     * Both privileges, concatenated against the constants: being allowed to see the queue and
     * being allowed to take it out of the building are two different permissions, exactly as
     * every other export in this application reads them (B2).
     */
    @PostMapping("/export")
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('"
            + Privileges.APPROVAL_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        List<ApprovalDtos.PendingChangeDto> rows = service.allMatching(
                        TableQuery.parseUnpaged(ApprovalSchemas.APPROVALS, req.sort(), req.filters()))
                .stream().filter(d -> ids.contains(d.id())).toList();

        String csv = Csv.of(
                List.of("Id", "Action", "Record type", "Record id", "Customer", "Region",
                        "Summary", "Amount", "Limit applied", "Status", "Raised by", "Raised",
                        "Decided by", "Decided", "Notes"),
                rows.stream().map(d -> List.<Object>of(
                        d.id(), d.action(), d.targetType(),
                        d.targetId() == null ? "" : d.targetId(),
                        d.customerName() == null ? "" : d.customerName(),
                        d.regionName() == null ? "" : d.regionName(),
                        d.summary() == null ? "" : d.summary(),
                        d.exposure(), d.thresholdApplied(), d.status(),
                        d.requestedByName() == null ? "" : d.requestedByName(),
                        d.requestedAt(),
                        d.decidedByName() == null ? "" : d.decidedByName(),
                        d.decidedAt() == null ? "" : d.decidedAt(),
                        d.decisionNotes() == null ? "" : d.decisionNotes())).toList());

        // NO AsOfCsv CAVEAT AND NO AS-OF FILENAME, because this export is deliberately NOT in
        // AS_OF_CAPABLE: B3-SLICE-REST's allowlist names the three approval GETs and no fourth
        // pattern, so ?asOf here is a 400 before the handler runs and a file from this endpoint is
        // always today's queue. If a later unit wants the January queue downloadable, the two
        // lines are the ones every other export already carries (B3).
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"approvals.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    // The InvoiceController.resolveIds shape, unchanged: the ids a caller NAMES are intersected
    // with the ids their own scope permits, so an id from a branch they hold nothing in simply is
    // not in the file rather than being refused (AUTH-08, B2).
    private List<Long> resolveIds(BulkDtos.BulkRequest req) {
        TableQuery query = TableQuery.parseUnpaged(ApprovalSchemas.APPROVALS, req.sort(), req.filters());
        List<Long> permitted = service.idsMatching(query, TableQueryExecutor.BULK_ID_LIMIT);
        if (req.allMatching()) return permitted;
        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        return req.ids().stream().filter(permitted::contains).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.APPROVAL_VIEW + "')")
    public ApprovalDtos.PendingChangeDto get(@PathVariable Long id) {
        return service.get(id);
    }

    /**
     * 200 and not 202: by the time this answers, the save the maker was refused has actually
     * happened, and the body carries whatever the replayed call returned (B2).
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('" + Privileges.APPROVAL_VIEW + "')")
    public ApprovalDtos.Decision approve(@PathVariable Long id,
                                         @Valid @RequestBody(required = false)
                                         ApprovalDtos.DecisionRequest req) {
        return service.approve(id, req);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('" + Privileges.APPROVAL_VIEW + "')")
    public ApprovalDtos.Decision reject(@PathVariable Long id,
                                        @Valid @RequestBody ApprovalDtos.RejectRequest req) {
        return service.reject(id, req);
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasAuthority('" + Privileges.APPROVAL_VIEW + "')")
    public ApprovalDtos.Decision withdraw(@PathVariable Long id,
                                          @Valid @RequestBody(required = false)
                                          ApprovalDtos.DecisionRequest req) {
        return service.withdraw(id, req);
    }

    // ------------------------------------------------------------- one run, one decision (B2)

    public static final String APPROVE = "APPROVE";
    public static final String REJECT = "REJECT";
    public static final List<String> BATCH_DECISIONS = List.of(APPROVE, REJECT);

    /** The body of a batch decision. A nested record, the PaymentController.CustomerCreditDto
     *  shape, because it is this endpoint's own request and nothing else on the wire carries
     *  it (B2). */
    public record BatchDecisionRequest(String decision,
                                       @Size(max = FieldLimits.REASON) String decisionNotes) {}

    /**
     * Every change one bulk click raised, decided in one act (B2).
     *
     * <p>One click that holds fifty rows is one decision to make, not fifty: the run stamps one
     * batch_id on every change it parks and this is where that id is spent. APPROVAL_VIEW like
     * every other mapping here — whether this caller may actually decide a given change is
     * per-region, per-maker and per-row, so it is answered inside the loop and reported per row.
     *
     * <p>Through BulkExecutor and not a plain loop, so each change is its own REQUIRES_NEW
     * transaction: a change whose record has moved underneath it fails alone, stays PENDING, and
     * the other forty-nine are still decided. The three refusals a decision can meet — already
     * decided, you raised it, you cannot approve in this branch — are rows that did not qualify
     * for THIS caller, so they are reported as skipped with the reason the guard gave, exactly as
     * BulkExecutor.eligibility reports an already-cancelled invoice (TBL-05, B2).
     */
    @PostMapping("/batches/{batchId}/decide")
    @PreAuthorize("hasAuthority('" + Privileges.APPROVAL_VIEW + "')")
    public BulkDtos.BulkResult decideBatch(@PathVariable String batchId,
                                           @Valid @RequestBody BatchDecisionRequest req) {
        String decision = req == null || req.decision() == null ? "" : req.decision().trim().toUpperCase();
        if (!BATCH_DECISIONS.contains(decision)) {
            throw new BadRequestException("Unknown decision: " + (req == null ? null : req.decision())
                    + " (expected one of " + BATCH_DECISIONS + ")");
        }
        String notes = req.decisionNotes();
        // Asked once for the whole request rather than per row: a rejection with no reason would
        // otherwise refuse every row in turn and report fifty identical skips for one mistake (B2).
        if (REJECT.equals(decision) && (notes == null || notes.isBlank())) {
            throw new BadRequestException("Say why these changes are being rejected");
        }

        List<Long> ids = service.batchIds(batchId);
        BulkDtos.BulkRequest bulk = new BulkDtos.BulkRequest(decision, ids, null, null, null, null);
        return bulkExecutor.run(bulk, ids, false, id -> {
            try {
                if (REJECT.equals(decision)) {
                    service.reject(id, new ApprovalDtos.RejectRequest(notes));
                } else {
                    service.approve(id, new ApprovalDtos.DecisionRequest(notes));
                }
            } catch (BadRequestException | NotFoundException | AccessDeniedException e) {
                // "This change has already been decided", "You cannot approve a change you
                // raised", "You do not hold the approval right in this region", and the mutator's
                // own refusal on live state: every one of them is a row this caller's decision did
                // not qualify for, and every one of them keeps its own sentence rather than being
                // flattened into one. A change refused here is left PENDING by the service, so
                // nothing is lost by reporting it as skipped (TBL-05, B2).
                throw new BulkExecutor.IneligibleException(e.getMessage());
            }
        });
    }
}
