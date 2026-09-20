package com.geneinvoice.email;

import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.privilege.Privileges;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/emails")
@RequiredArgsConstructor
public class EmailController {

    private final EmailService service;

    @GetMapping("/context")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_SEND + "')")
    public EmailDtos.ContextDto context(@RequestParam String entityType,
                                        @RequestParam(required = false) Long entityId,
                                        @RequestParam(required = false) String event,
                                        @RequestParam(required = false) Integer utcOffsetMinutes) {
        return service.context(entityType, entityId, event, utcOffsetMinutes);
    }

    @GetMapping("/people")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_SEND + "')")
    public List<EmailDtos.PeopleDto> people(@RequestParam(required = false) String q) {
        return service.people(q);
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_SEND + "')")
    public EmailDtos.PreviewDto preview(@RequestBody EmailDtos.SendEmailRequest req) {
        return service.preview(req);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_SEND + "')")
    public EmailDtos.EmailDto send(@RequestBody EmailDtos.SendEmailRequest req) {
        return service.send(req);
    }

    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_SEND + "')")
    public BulkDtos.BulkResult bulk(@Valid @RequestBody BulkDtos.BulkRequest req) {
        return service.bulk(req);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_VIEW + "')")
    public PageResponse<EmailDtos.EmailDto> list(@RequestParam String entityType,
                                                 @RequestParam Long entityId,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        return service.list(entityType, entityId, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_VIEW + "')")
    public EmailDtos.EmailDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_SEND + "')")
    public EmailDtos.EmailDto retry(@PathVariable Long id) {
        return service.retry(id);
    }

    @GetMapping("/delivery")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_VIEW + "')")
    public EmailDtos.DeliveryStatusDto delivery() {
        return service.delivery();
    }

    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_VIEW + "')")
    public EmailDtos.SyncResultDto sync() {
        return service.sync();
    }
}
