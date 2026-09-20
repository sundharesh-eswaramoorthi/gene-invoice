package com.geneinvoice.email;

import java.util.Optional;

/**
 * A role at a level (L1) — the pair a sender picks, that is checked against the record, and that is
 * kept on the recipient. It knows how it is written down ({@link #token()}) and how it reads to a
 * person, which needs the kind of record: "Sales POC (customer)", "Sales POC (this invoice)".
 *
 * <p>A role read back from a row written before levels existed has no level at all ({@code null}),
 * because none was stored: it is named without one, exactly as it was named then (L7).
 */
public record RoleRef(EmailRole role, RoleLevel level) {

    private static final String PREFIX = "ROLE:";

    public static RoleRef customer(EmailRole role) {
        return new RoleRef(role, RoleLevel.CUSTOMER);
    }

    public static RoleRef record(EmailRole role) {
        return new RoleRef(role, RoleLevel.RECORD);
    }

    /** A role written down before levels existed: the role alone, with no level to claim (L7). */
    public static RoleRef unlevelled(EmailRole role) {
        return new RoleRef(role, null);
    }

    /** The token a stored source or unresolved entry uses, e.g. {@code ROLE:CUSTOMER:COLLECTION_POC}. */
    public String token() {
        return level == null ? PREFIX + role.name() : PREFIX + level.name() + ":" + role.name();
    }

    /**
     * Reads a stored token. One written before levels existed ({@code ROLE:<ROLE>}) kept no level,
     * so none is invented for it (L7); anything unreadable is empty.
     */
    public static Optional<RoleRef> parseToken(String token) {
        if (token == null || !token.startsWith(PREFIX)) return Optional.empty();
        String rest = token.substring(PREFIX.length());
        int cut = rest.indexOf(':');
        try {
            return Optional.of(cut < 0
                    ? unlevelled(EmailRole.valueOf(rest))
                    : new RoleRef(EmailRole.valueOf(rest.substring(cut + 1)),
                            RoleLevel.valueOf(rest.substring(0, cut))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * "Sales POC (customer)" / "Sales POC (this invoice)": the role and where it is read from —
     * the role alone when no level was stored, which is how it read before levels existed (L7).
     */
    public String label(EmailEntityType type) {
        if (level == null) return role.label();
        return role.label() + " (" + (level == RoleLevel.CUSTOMER ? "customer" : "this " + type.noun()) + ")";
    }

    /** The group the compose form puts the role in: "Customer", or the record's noun ("Invoice"). */
    public String levelLabel(EmailEntityType type) {
        return level == RoleLevel.CUSTOMER ? "Customer" : type.title();
    }

    /** That group's heading: "Customer level", "Invoice level". */
    public String groupLabel(EmailEntityType type) {
        return levelLabel(type) + " level";
    }
}
