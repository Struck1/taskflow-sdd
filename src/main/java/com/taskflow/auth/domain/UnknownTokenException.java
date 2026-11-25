package com.taskflow.auth.domain;

/**
 * The token cannot belong to any stored link or session. Callers handle it exactly like a
 * token that matches no stored hash (TD-010): {@code 409 link-invalid} for links and
 * {@code 401} for a session cookie. The message never contains the token.
 */
public class UnknownTokenException extends RuntimeException {

    public UnknownTokenException() {
        super("Unknown token");
    }
}
