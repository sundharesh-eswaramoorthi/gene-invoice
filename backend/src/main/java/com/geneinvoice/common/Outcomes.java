package com.geneinvoice.common;

import com.geneinvoice.approval.Outcome;
import com.geneinvoice.approval.PendingChange;
import com.geneinvoice.approval.PendingChangeViews;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.function.Function;

/**
 * The answer to a write that may be held (§5.11.4): applied, exactly the 200 and body it always was; held, 202
 * with {@code Location: /api/approvals/{id}} and the pending change, and no top-level {@code id}.
 */
public final class Outcomes {

    private Outcomes() {}

    public static <T> ResponseEntity<Object> respond(Outcome<T> outcome, Function<T, ?> body,
                                                     PendingChangeViews views) {
        Optional<PendingChange> held = outcome.heldChange();
        if (held.isEmpty()) return ResponseEntity.ok(body.apply(outcome.appliedOrThrow()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, "/api/approvals/" + held.get().getId())
                .body(views.heldReply(held.get()));
    }
}
