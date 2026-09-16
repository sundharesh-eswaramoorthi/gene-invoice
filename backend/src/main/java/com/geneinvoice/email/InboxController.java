package com.geneinvoice.email;

import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The signed-in user's own Inbox. It needs no privilege: anyone on staff may read what was sent to
 * them. A customer login has no Inbox — customer addresses are only recorded on the email.
 */
@RestController
@RequestMapping("/api/inbox")
@RequiredArgsConstructor
public class InboxController {

    private final EmailService service;

    @GetMapping
    public PageResponse<EmailDtos.InboxItemDto> list(@RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size,
                                                     @RequestParam(required = false) String sort,
                                                     HttpServletRequest request) {
        return service.inbox(page, size, sort, FilterParams.from(request));
    }

    @GetMapping("/{emailId}")
    public EmailDtos.InboxItemDto get(@PathVariable Long emailId) {
        return service.inboxItem(emailId);
    }

    @PostMapping("/{emailId}/read")
    public EmailDtos.InboxItemDto markRead(@PathVariable Long emailId) {
        return service.markRead(emailId);
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", service.markAllRead());
    }
}
