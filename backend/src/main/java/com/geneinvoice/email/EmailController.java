package com.geneinvoice.email;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The stored-Email API: compose options, the caller-owned Inbox, the shared detail view and
 * recipient-scoped read mutations. No mark-unread, search, filter or badge endpoint exists here
 * on purpose (NFR1), and no endpoint accepts a user id — ownership comes from the session.
 */
@RestController
@RequestMapping("/api/emails")
@RequiredArgsConstructor
public class EmailController {

    private final EmailService service;
    private final CurrentUser currentUser;
    private final TableQueryExecutor queryExecutor;

    @GetMapping("/options")
    public EmailDtos.Options options() {
        return service.options();
    }

    @GetMapping("/inbox")
    public PageResponse<EmailDtos.InboxEntry> inbox(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        TableQuery query = TableQuery.parse(TableSchemas.INBOX, page, size, sort,
                FilterParams.from(request));
        var result = queryExecutor.run(EmailRecipientRead.class, TableSchemas.INBOX, query,
                ownedByCaller(), List.of("email"));
        return PageResponse.of(result.content().stream().map(EmailController::toEntry).toList(),
                query, result.total(), List.of());
    }

    private static EmailDtos.InboxEntry toEntry(EmailRecipientRead r) {
        Email e = r.getEmail();
        return new EmailDtos.InboxEntry(r.getId(), e.getId(), e.getSubject(),
                e.getSenderDisplay(), e.getSentAt(), r.isRead());
    }

    /** Caller only ever meets their own rows; no request parameter can widen that (FR14). */
    private List<PredicateFactory> ownedByCaller() {
        Long me = currentUser.require().getId();
        return List.of((root, q, cb) -> cb.equal(root.get("userId"), me));
    }

    /** Opening an Email returns its stored detail and marks the caller's occurrence read. */
    @GetMapping("/{id}")
    public EmailDtos.Detail detail(@PathVariable Long id) {
        return service.open(id);
    }

    @PostMapping("/{id}/read")
    public void markRead(@PathVariable Long id) {
        service.markRead(id);
    }

    @PostMapping("/mark-all-read")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", service.markAllReadForCaller());
    }
}
