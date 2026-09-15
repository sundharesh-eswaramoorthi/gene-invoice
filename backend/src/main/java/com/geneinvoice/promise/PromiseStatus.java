package com.geneinvoice.promise;

public enum PromiseStatus {
    /** Made, not yet due, nothing paid against it. */
    OPEN,
    /** Everything it promised has been settled. */
    KEPT,
    /** Some but not all of it has been settled. */
    PARTIALLY_KEPT,
    /** The date passed with nothing paid against it. */
    BROKEN,
    /** Raised in error and withdrawn. */
    CANCELLED
}
