package com.streakysmp.data;

/**
 * Wraps a {@link java.sql.SQLException} so callers are not forced to handle
 * checked exceptions through every service layer.
 *
 * <p>Unchecked on purpose: there is no sensible local recovery from a failed
 * database call in most of this plugin. The command or GUI that started the
 * operation catches it at its boundary, tells the player the action failed, and
 * logs the cause.
 */
public class DataAccessException extends RuntimeException {

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public DataAccessException(String message) {
        super(message);
    }
}
