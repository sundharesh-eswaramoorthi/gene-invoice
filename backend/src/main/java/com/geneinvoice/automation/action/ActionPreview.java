package com.geneinvoice.automation.action;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.geneinvoice.assignee.AssigneeDtos;
import com.geneinvoice.email.EmailDtos;

import java.util.List;
import java.util.Map;

/**
 * One action as a preview shows it for one record (§6.14 {@code PreviewDto.record.actions}): what it would write,
 * who it would involve, and why it could not or what is worth knowing.
 */
public record ActionPreview(
        String key,
        String type,
        Map<String, Object> rendered,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<AssigneeDtos.AssigneeDto> assignees,
        @JsonInclude(JsonInclude.Include.NON_NULL) EmailDtos.PreviewDto email,
        List<String> problems,
        List<String> warnings) {}
