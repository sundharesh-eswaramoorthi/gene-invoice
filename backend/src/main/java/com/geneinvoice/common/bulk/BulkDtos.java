package com.geneinvoice.common.bulk;

import jakarta.validation.constraints.NotBlank;

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
            return v instanceof Number n ? n.longValue() : Long.valueOf(v.toString());
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
