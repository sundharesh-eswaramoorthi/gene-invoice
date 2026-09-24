package com.geneinvoice.common.asof;

import com.geneinvoice.invoice.InvoiceDates;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * The contract, published rather than documented: how far back this installation can answer, what
 * it calls today, what it does with a date before the floor, and which tables have a mirror to
 * read. A UI that has to decide whether to offer a date picker at all asks here once (B3).
 *
 * <p>Authentication only, and no privilege: the answer is about the SHAPE of the feature and names
 * no record. It is itself in AsOfEndpoints.NOT_AS_OF — asking what the floor was last March is
 * not a question about a record, and is 400.
 */
@RestController
@RequestMapping("/api/as-of")
@RequiredArgsConstructor
public class AsOfController {

    // The interceptor, not a second copy of the configuration: it already normalises the pre-floor
    // policy at construction and already holds the floor port, and two readings of one setting
    // that could disagree is exactly the drift this endpoint exists to prevent (B3).
    private final AsOfInterceptor interceptor;

    // Absent until B3-SCHEMAS registers the mirrors, so the feature announces an empty list rather
    // than advertising a table nothing can read yet (B3).
    private final ObjectProvider<AsOfSupport> support;

    /**
     * @param floor    the day the mirror started, or null while nothing has installed one
     * @param today    the server's UTC day, so a client in another zone can bound its date picker
     * @param preFloor seeded | reject
     * @param entities the table entity names that can be asked as of a date
     */
    public record AsOfDescription(String floor, String today, String preFloor,
                                  List<String> entities) {
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public AsOfDescription describe() {
        LocalDate floor = interceptor.floorOrNull();
        AsOfSupport available = support.getIfAvailable();
        return new AsOfDescription(
                floor == null ? null : floor.toString(),
                InvoiceDates.today().toString(),
                interceptor.preFloorPolicy(),
                available == null ? List.of() : available.entities());
    }
}
