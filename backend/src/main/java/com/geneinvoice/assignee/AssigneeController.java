package com.geneinvoice.assignee;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.scope.AsOfIgnored;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.email.EmailService;
import com.geneinvoice.privilege.Privileges;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The assignee pickers (§6.13). Staff only, and live: they offer who can be assigned now. */
@RestController
@AsOfIgnored
@RequestMapping("/api/assignees")
@RequiredArgsConstructor
public class AssigneeController {

    private final EmailService emailService;
    private final AssigneeService assigneeService;
    private final CurrentUser currentUser;

    /** The roles offered on the record, with who holds each now: the same answer as the email context's. */
    @GetMapping("/context")
    @PreAuthorize("hasAnyAuthority('" + Privileges.TASK_MANAGE + "', '" + Privileges.DISPUTE_MANAGE + "', '"
            + Privileges.PROMISE_MANAGE + "', '" + Privileges.AUTOMATION_MANAGE + "')")
    public EmailDtos.ContextDto context(@RequestParam String entityType,
                                        @RequestParam(required = false) Long entityId) {
        if (currentUser.isCustomer()) throw new AccessDeniedException("Not allowed");
        return emailService.context(entityType, entityId, null, null);
    }

    @GetMapping("/people")
    @PreAuthorize("hasAnyAuthority('" + Privileges.TASK_VIEW + "', '" + Privileges.DISPUTE_MANAGE + "', '"
            + Privileges.PROMISE_MANAGE + "', '" + Privileges.AUTOMATION_VIEW + "')")
    public List<EmailDtos.PeopleDto> people(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) String entityType,
                                            @RequestParam(required = false) Long entityId) {
        return assigneeService.people(q, entityType, entityId);
    }
}
