package com.geneinvoice.assignee;

import com.geneinvoice.email.EmailDtos.EmailToken;

import java.util.List;

public class AssigneeDtos {

    /** Someone an assignee stands for; {@code primary} is who a role's work falls to first (the sender rule). */
    public record PersonDto(Long userId, String name, boolean primary) {}

    /**
     * One assignee as stored, and who it means now: the person, or the role's holders. {@code unresolved} when it
     * means nobody: a role nobody holds, or a person who is no longer active.
     */
    public record AssigneeDto(EmailToken token, String label, List<PersonDto> people, boolean unresolved) {}
}
