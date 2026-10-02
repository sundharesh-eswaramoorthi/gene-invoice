package com.geneinvoice.auth;

import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.common.scope.ViewScope;
import com.geneinvoice.common.scope.ViewScopeHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Runs a unit of work as someone else (D6): a fresh principal, "which regions, as of when", and the provenance of
 * its writes, all three put back as they were in {@code finally}. The origin names the principal as its actor
 * unless it names one already (D4). Used by the visibility probe, the automation owner session and the approval
 * path.
 *
 * <p>The origin is bound inside, so a transaction that starts and commits within {@code work} still sees it at
 * commit; code that runs an outer transaction under another identity binds the origin around it instead (CF-1).
 */
@Component
@RequiredArgsConstructor
public class RunAs {

    private final AppUserDetailsService principals;

    /** Refused for someone who could not sign in now: missing, inactive, or a customer login. */
    public static class InactiveUserException extends RuntimeException {
        public InactiveUserException(String message) {
            super(message);
        }
    }

    public <T> T call(AppUserDetails principal, ViewScope scope, WriteOrigin origin, Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext mine = SecurityContextHolder.createEmptyContext();
        mine.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(mine);
        WriteOrigin bound = origin.actorUserId() != null ? origin : origin.withActor(principal.getUser().getId());
        try {
            return ViewScopeHolder.with(scope, () -> WriteOrigins.callWith(bound, work));
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    public <T> T asUser(long userId, ViewScope scope, WriteOrigin origin, Supplier<T> work) {
        AppUserDetails principal = principals.principalFor(userId)
                .filter(p -> p.getUser().isActive() && p.getUser().getCustomerId() == null)
                .orElseThrow(() -> new InactiveUserException("User #" + userId + " cannot act now"));
        return call(principal, scope, origin, work);
    }
}
