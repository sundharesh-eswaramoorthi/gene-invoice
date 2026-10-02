package com.geneinvoice.automation.rule;

/** Why a rule stopped acting on its own (§5.10.7). Stored by name; new constants are appended last (D12). */
public enum SuspendReason {
    OWNER_INACTIVE, OWNER_NOT_STAFF, OWNER_LACKS_RIGHTS, INVALID_DEFINITION, RATE_LIMITED
}
