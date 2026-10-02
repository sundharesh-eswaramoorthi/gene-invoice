package com.geneinvoice.common.origin;

import java.util.Objects;

/**
 * Why a write is happening: a person, the system, an automation rule, an approved change, a schema upgrade
 * or a history reconcile. {@code actorUserId} is the user the write is attributed to, when it is not simply
 * the principal of the moment.
 */
public record WriteOrigin(Kind kind, Long ruleId, Long runId, Long eventId, Long pendingChangeId,
                          Long checkerUserId, Long actorUserId, int depth) {

    public enum Kind { USER, SYSTEM, AUTOMATION, APPROVAL, SCHEMA, RECONCILE }

    public WriteOrigin {
        Objects.requireNonNull(kind, "kind");
    }

    public static WriteOrigin user() {
        return new WriteOrigin(Kind.USER, null, null, null, null, null, null, 0);
    }

    public static WriteOrigin automation(long ruleId, Long runId, Long eventId, int depth) {
        return new WriteOrigin(Kind.AUTOMATION, ruleId, runId, eventId, null, null, null, depth);
    }

    public static WriteOrigin approval(long pendingChangeId, long checkerUserId) {
        return new WriteOrigin(Kind.APPROVAL, null, null, null, pendingChangeId, checkerUserId, null, 0);
    }

    public static WriteOrigin schema() {
        return new WriteOrigin(Kind.SCHEMA, null, null, null, null, null, null, 0);
    }

    public static WriteOrigin reconcile() {
        return new WriteOrigin(Kind.RECONCILE, null, null, null, null, null, null, 0);
    }

    public static WriteOrigin system() {
        return new WriteOrigin(Kind.SYSTEM, null, null, null, null, null, null, 0);
    }

    public WriteOrigin withActor(Long userId) {
        return new WriteOrigin(kind, ruleId, runId, eventId, pendingChangeId, checkerUserId, userId, depth);
    }
}
