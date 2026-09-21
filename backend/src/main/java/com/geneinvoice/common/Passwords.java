package com.geneinvoice.common;

public final class Passwords {

    public static final int MIN_LENGTH = 6;

    private Passwords() {}

    public static void require(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new BadRequestException("Password must be at least " + MIN_LENGTH + " characters");
        }
    }
}
