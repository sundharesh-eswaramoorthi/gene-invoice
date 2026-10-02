package com.geneinvoice.common.scope;

import com.geneinvoice.common.BadRequestException;

/** A 400 whose {@code ApiError.error} is a machine-readable code rather than the reason phrase. */
public class CodedBadRequestException extends BadRequestException {

    public static final String REGION_IDS_INVALID = "REGION_IDS_INVALID";

    public final String code;

    public CodedBadRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static CodedBadRequestException regionIdsInvalid() {
        return new CodedBadRequestException(REGION_IDS_INVALID,
                "regionIds must be a comma-separated list of region ids");
    }
}
