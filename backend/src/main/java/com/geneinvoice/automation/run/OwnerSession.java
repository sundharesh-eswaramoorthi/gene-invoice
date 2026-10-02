package com.geneinvoice.automation.run;

import com.geneinvoice.auth.AppUserDetails;
import com.geneinvoice.auth.AppUserDetailsService;
import com.geneinvoice.auth.RunAs;
import com.geneinvoice.automation.event.AutomationEvent;
import com.geneinvoice.automation.rule.RequiredPrivileges;
import com.geneinvoice.automation.rule.RuleDefinition;
import com.geneinvoice.automation.rule.SuspendReason;
import com.geneinvoice.common.origin.WriteOrigin;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.common.scope.ViewScope;
import com.geneinvoice.email.EmailText;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A rule runs as its owner (P3, §5.10.7): their principal, read afresh with its grants, "which regions" narrowed to
 * the rule's own regions (and to those a "Run now" asked about), and writes attributed to the owner and stamped
 * with the rule. An owner who can no longer act (gone, inactive, a customer login, or lacking what the rule needs)
 * is an {@link OwnerUnavailableException}, and the rule is suspended. Everything is put back in {@code finally}.
 */
@Component
@RequiredArgsConstructor
public class OwnerSession {

    private final AppUserDetailsService principals;
    private final RunAs runAs;

    /** {@code work} as the rule's owner, for this event; {@code runId} when the run is already known. */
    public <T> T call(RuleDefinition rule, AutomationEvent ev, Long runId, Supplier<T> work) {
        AppUserDetails owner = require(rule);
        ViewScope scope = new ViewScope(regions(rule, ev), null);
        WriteOrigin origin = WriteOrigin.automation(rule.id(), runId, ev.getId(), ev.getDepth() + 1);
        return runAs.call(owner, scope, origin, work);
    }

    /** Within a session, the writes of {@code work} are stamped with the run too, once it exists. */
    public static <T> T forRun(Long runId, Supplier<T> work) {
        WriteOrigin o = WriteOrigins.current(null);
        return WriteOrigins.callWith(new WriteOrigin(o.kind(), o.ruleId(), runId, o.eventId(), o.pendingChangeId(),
                o.checkerUserId(), o.actorUserId(), o.depth()), work);
    }

    /** The owner as they can act now; why not, otherwise. */
    public AppUserDetails require(RuleDefinition rule) {
        AppUserDetails owner = principals.principalFor(rule.ownerUserId()).orElse(null);
        if (owner == null || !owner.getUser().isActive()) {
            throw new OwnerUnavailableException(SuspendReason.OWNER_INACTIVE, (owner == null
                    ? "The rule's owner no longer exists" : EmailText.nameOf(owner.getUser()) + " is no longer active")
                    + ", so the rule cannot run as them");
        }
        if (owner.getUser().getCustomerId() != null) {
            throw new OwnerUnavailableException(SuspendReason.OWNER_NOT_STAFF,
                    "The rule's owner is a customer login, so the rule cannot run as them");
        }
        Set<String> held = owner.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        List<String> missing = RequiredPrivileges.of(rule).stream().filter(p -> !held.contains(p)).toList();
        if (!missing.isEmpty()) {
            throw new OwnerUnavailableException(SuspendReason.OWNER_LACKS_RIGHTS, EmailText.nameOf(owner.getUser())
                    + " no longer holds " + String.join(", ", missing) + ", which the rule needs");
        }
        return owner;
    }

    /**
     * The regions the rule acts in: its own restriction (none means every region the owner holds), within the
     * regions a "Run now" was asked for.
     */
    static Set<Long> regions(RuleDefinition rule, AutomationEvent ev) {
        Set<Long> regions = rule.restricted() ? new TreeSet<>(rule.regionIds()) : null;
        Set<Long> asked = ids(ev.getRegionIds());
        if (asked == null) return regions;
        if (regions == null) return asked;
        regions.retainAll(asked);
        return regions;
    }

    /** A comma-joined list of ids as stored in the outbox; null for none. */
    public static Set<Long> ids(String joined) {
        if (joined == null || joined.isBlank()) return null;
        return Arrays.stream(joined.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Long::valueOf)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
