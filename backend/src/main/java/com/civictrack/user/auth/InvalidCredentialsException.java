package com.civictrack.user.auth;

/**
 * Wrong password, unknown account, disabled account, or an account seeded
 * without a password hash.
 *
 * <p>One exception for all of them, with one message. Distinguishing "no such
 * user" from "wrong password" hands an attacker a free account-enumeration
 * oracle, and the honest error is not worth that.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
