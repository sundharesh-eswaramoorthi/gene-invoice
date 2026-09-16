package com.geneinvoice.email;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.privilege.Privileges;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Emails about customers and invoices. They are stored, never delivered: staff read theirs in the
 * Inbox ({@link InboxController}), and everyone who may see a record reads its Email tab here.
 */
@RestController
@RequestMapping("/api/emails")
@RequiredArgsConstructor
public class EmailController {

    private final EmailService service;

    /** The Email tab: a customer's emails (with its invoices'), or one invoice's, newest first. */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_VIEW + "')")
    public PageResponse<EmailDtos.EmailDto> list(@RequestParam(required = false) Long customerId,
                                                 @RequestParam(required = false) Long invoiceId,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        if ((customerId == null) == (invoiceId == null)) {
            throw new BadRequestException("Give either customerId or invoiceId");
        }
        return customerId != null
                ? service.pageForCustomer(customerId, page, size)
                : service.pageForInvoice(invoiceId, page, size);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_SEND + "')")
    public EmailDtos.EmailDto send(@Valid @RequestBody EmailDtos.SendRequest req) {
        return service.send(req);
    }

    /** One email per selected customer or invoice; rows with no one to receive theirs are skipped. */
    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_SEND + "')")
    public BulkDtos.BulkResult bulk(@Valid @RequestBody EmailDtos.BulkSendRequest req) {
        return service.sendBulk(req);
    }

    /** Internal users to offer as a sender or recipient, searched by name, username or email. */
    @GetMapping("/staff")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_SEND + "')")
    public List<EmailDtos.StaffDto> staff(@RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "25") int limit) {
        return service.staff(q, limit);
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_SEND + "')")
    public List<EmailDtos.RoleOptionDto> roles() {
        return service.roles();
    }

    /** Every address of the customer an email about this customer or invoice can go to. */
    @GetMapping("/addresses")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_SEND + "')")
    public EmailDtos.AddressesDto addresses(@RequestParam(required = false) Long customerId,
                                            @RequestParam(required = false) Long invoiceId) {
        return service.addresses(customerId, invoiceId);
    }
}
