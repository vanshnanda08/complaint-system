package com.civictrack.user.auth;

/** Registration collided with an existing account. */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(String email) {
        super("An account already exists for " + email);
    }
}
