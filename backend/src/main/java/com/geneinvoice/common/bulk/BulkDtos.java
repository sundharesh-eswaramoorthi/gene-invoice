package com.geneinvoice.common.bulk;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.query.TableQueryExecutor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public class BulkDtos {

    public record BulkRequest(
            @NotBlank String action,
            @Size(max = TableQueryExecutor.BULK_ID_LIMIT,
                    message = "cannot name more than " + TableQueryExecutor.BULK_ID_LIMIT
                            + " records at once; use selectAllMatchingFilter instead")
            List<Long> ids,
            Boolean selectAllMatchingFilter,
            String sort,
            List<String> filters,
            Map<String, Object> params
    ) {
        public boolean allMatching() {
            return Boolean.TRUE.equals(selectAllMatchingFilter);
        }

        public Long longParam(String key) {
            Object v = params == null ? null : params.get(key);
            if (v == null) return null;
            if (v instanceof Number n) return n.longValue();
            try {
                return Long.valueOf(v.toString().trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("params." + key + " must be a whole number");
            }
        }

        public String stringParam(String key) {
            Object v = params == null ? null : params.get(key);
            return v == null ? null : v.toString();
        }
    }

    public record BulkOutcome(Long id, String reason) {}

    /**
     * Four buckets, not three. A row held for approval did not succeed (nothing changed), did not
     * fail (nothing went wrong) and was not skipped (skipped means "did not qualify", which is the
     * one sentence that says the opposite of what happened to it) — so it gets its own bucket, on
     * the ONE shared record every bulk action on every entity already answers with (TBL-05, B2).
     *
     * <p>{@code pendingLimitReached} says the run stopped queueing approvals rather than that it
     * ran out of rows: one click over 5000 selected records must not become 5000 changes somebody
     * now has to decide one at a time, and a caller who is not told would think the rest went
     * through (B2).
     */
    public record BulkResult(
            String action,
            int requested,
            List<Long> succeeded,
            List<BulkOutcome> failed,
            List<BulkOutcome> skipped,
            boolean truncated,
            int limit,
            List<BulkOutcome> pending,
            boolean pendingLimitReached
    ) {}
}
