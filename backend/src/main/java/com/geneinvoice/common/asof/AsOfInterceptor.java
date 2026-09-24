package com.geneinvoice.common.asof;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.invoice.InvoiceDates;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.time.LocalDate;

/**
 * The one place ?asOf becomes a state this thread is answering in — or a 400.
 *
 * <p>Registered in OpenEntityManagerInViewConfig beside the open-entity-manager interceptor, so it
 * covers every mapping in the application including the POST exports, which is why the write guard
 * is expressed as the allowlist itself rather than as a blanket "GET only" test (B3).
 *
 * <p>DEVIATION FROM B3'S CODE SKETCH, STATED. The sketch rejects every non-GET unconditionally
 * before consulting the allowlist. All eight CSV exports in this repo are POST, and the contract
 * requires an as-of export, so the rule here is: a method+pattern pair that is not in
 * AS_OF_CAPABLE is 400, and a non-GET pair is refused with "The past is read only". Every mutating
 * mapping is still 400 by construction — nothing will ever add one to the allowlist — and the two
 * messages are the ones the error contract fixes (B3).
 */
@Component
public class AsOfInterceptor implements HandlerInterceptor {

    public static final String PARAM = "asOf";

    /** A read that has no history to read from says so, rather than quietly answering today (B3). */
    static final String NOT_CAPABLE = "This endpoint cannot be asked as of a date";

    /** A write can never be asked as of a date; the error contract fixes this text (B3). */
    static final String READ_ONLY = "The past is read only";

    // Absent until B3-UPGRADES installs the floor, and an ObjectProvider rather than the interface
    // because Spring cannot inject a bean that does not exist yet — the same seam
    // RegionCustodyService uses for its CustomerMoved listeners (B3).
    private final ObjectProvider<AsOfFloor> floor;

    private final String preFloorPolicy;

    public AsOfInterceptor(ObjectProvider<AsOfFloor> floor,
                           @Value("${" + AsOfDates.PRE_FLOOR_PROPERTY + ":seeded}") String preFloorPolicy) {
        this.floor = floor;
        // Normalised at construction, so a misspelled setting fails the boot rather than every
        // pre-floor request (B3).
        this.preFloorPolicy = AsOfDates.policy(preFloorPolicy);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String raw = request.getParameter(PARAM);
        // The live path, which is every request the shipped client sends: nothing is opened, no
        // context is consulted and not one statement of SQL is added (B3).
        if (raw == null || raw.isBlank()) return true;

        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        // A handler that owns the parameter is left alone entirely: not refused, and — the part
        // that matters — no context opened, because the one endpoint in this set guards itself by
        // comparing asOf against today() (B3).
        if (AsOfEndpoints.handlerOwned(request.getMethod(), pattern)) return true;
        if (!AsOfEndpoints.capable(request.getMethod(), pattern)) {
            throw new BadRequestException("GET".equals(request.getMethod()) ? NOT_CAPABLE : READ_ONLY);
        }

        // Nothing is open yet, so today() here is the wall clock — which is what "is the date they
        // asked for in the past?" has to be measured against (B3).
        AsOfContext.State state =
                AsOfDates.parse(raw, InvoiceDates.today(), floorOrNull(), preFloorPolicy);
        // Null means the date is today or later: serve live and echo asOf: null.
        if (state != null) AsOfContext.open(state);
        return true;
    }

    /**
     * Cleared and not restored: this IS the outermost boundary, and Tomcat hands the thread to the
     * next request. afterCompletion runs for every interceptor whose preHandle returned true, so
     * the only paths that skip it are the two refusals above — neither of which has opened
     * anything (B3).
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AsOfContext.clear();
    }

    /** What GET /api/as-of publishes, so the controller and the parser cannot disagree. */
    public String preFloorPolicy() {
        return preFloorPolicy;
    }

    public LocalDate floorOrNull() {
        AsOfFloor available = floor.getIfAvailable();
        return available == null ? null : available.floorOrNull();
    }
}
