package com.geneinvoice.common;

/**
 * One password rule for every way a password can be set — an admin creating or editing a user, a
 * customer login, or someone changing their own (D-42). Kept deliberately small: the requirement
 * asks only for a sane minimum length.
 */
public final class Passwords {

    public static final int MIN_LENGTH = 6;

    private Passwords() {}

    public static void require(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new BadRequestException("Password must be at least " + MIN_LENGTH + " characters");
        }
    }
}
