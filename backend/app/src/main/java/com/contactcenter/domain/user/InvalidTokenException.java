package com.contactcenter.domain.user;

/** Rzucany gdy refresh token jest nieprawidłowy, wygasł lub został unieważniony. */
public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
        super(message);
    }
}
