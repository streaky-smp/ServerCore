package com.servercore.config;

/**
 * Thrown when configuration is malformed.
 *
 * <p>Deliberately fatal at startup. A server that boots with a misread claim
 * price or rent period will quietly transact real player money at the wrong
 * rate, and that damage is far harder to undo than a failed startup.
 */
public class ConfigException extends RuntimeException {

    public ConfigException(String path, String problem) {
        super("Invalid config at '" + path + "': " + problem);
    }

    public ConfigException(String message) {
        super(message);
    }
}
