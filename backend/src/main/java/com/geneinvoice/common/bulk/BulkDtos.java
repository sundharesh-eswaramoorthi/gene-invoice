package com.geneinvoice.common.bulk;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.query.TableQueryExecutor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public class BulkDtos {

    /**
     * A bulk request either names explicit ids or asks for everything matching the current filter.
     * The filter is re-evaluated server-side, so the selection can never reach rows the caller is
     * not scoped to see (AC-D7, AC-D10).
     */
    public record BulkRequest(
            @NotBlank String action,
            /**
             * Bounded by the same limit a filtered selection is capped at, so one request cannot
             * force a result with an outcome line per id — the dialog renders every one of them
             * (TBL-08). The normal UI never sends more than a page and uses
             * {@code selectAllMatchingFilter} beyond that.
             */
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
     * Per-record result. Nothing is dropped silently: every requested id lands in exactly one of
     * succeeded / failed / skipped (AC-D5, AC-D6).
     */
    public record BulkResult(
            String action,
            int requested,
            List<Long> succeeded,
            List<BulkOutcome> failed,
            List<BulkOutcome> skipped,
            /** True when the filtered set was larger than the server will act on in one call. */
            boolean truncated,
            int limit
    ) {}
}
