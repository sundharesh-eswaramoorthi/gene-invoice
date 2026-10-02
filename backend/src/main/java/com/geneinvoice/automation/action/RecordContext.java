package com.geneinvoice.automation.action;

import com.geneinvoice.automation.placeholder.PlaceholderValues;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.email.EmailTargets;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * One record an action is about, read as whoever the rule runs as (its owner; the caller or owner in a preview): the
 * entity itself, its customer and region, who holds each role on it, and the values its placeholders stand for.
 * {@code ruleId} is null for a rule not yet saved.
 */
public record RecordContext(TriggerEntity type, Long id, Object entity, Long customerId, Long regionId,
                            String label, String link, EmailTargets.Target target, PlaceholderValues values,
                            Long ruleId, ZoneId zone, LocalDate today) {}
