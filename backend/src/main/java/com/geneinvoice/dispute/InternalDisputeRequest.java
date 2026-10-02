package com.geneinvoice.dispute;

import com.geneinvoice.email.EmailDtos.EmailToken;

import java.util.List;

/**
 * A dispute a rule raises for staff to review (§5.10.11): on the record {@code (targetType, targetId)}, its reason
 * already rendered, proposing {@code none}, {@code cancel} (an invoice) or {@code void} (a payment), and assigned to
 * people already checked and probed by the action handler.
 */
public record InternalDisputeRequest(DisputeTargetType targetType, Long targetId, String reason, String proposal,
                                     Long ruleId, List<EmailToken> assignees) {

    public static final String NONE = "none";
    public static final String CANCEL = "cancel";
    public static final String VOID = "void";

    public InternalDisputeRequest {
        proposal = proposal == null || proposal.isBlank() ? NONE : proposal.trim().toLowerCase();
        assignees = assignees == null ? List.of() : List.copyOf(assignees);
    }
}
