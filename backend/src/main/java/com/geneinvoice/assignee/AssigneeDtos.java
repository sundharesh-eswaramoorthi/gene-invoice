package com.geneinvoice.assignee;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public class AssigneeDtos {

    /**
     * One person an assignee reaches, as the caller may see them. The same shape the email form
     * uses for a role's holders, so the picker renders both from one widget.
     */
    public record PersonDto(Long userId, String name, String address) {}

    /**
     * An assignee as it reads back: the pick itself ({@code kind} plus either {@code userId} or
     * {@code role} + {@code level}), what to call it, and who it reaches right now. {@code people}
     * is read when the record is read, never stored, so a role follows the POC book (A2); it is
     * empty for a seat nobody holds, which is how an unheld role shows as unassigned rather than
     * silently dropping out of the list.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssigneeDto(AssigneeKind kind, Long userId, String role, String level,
                              String label, boolean resolved, List<PersonDto> people) {}
}
