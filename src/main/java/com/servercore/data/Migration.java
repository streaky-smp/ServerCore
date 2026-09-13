package com.servercore.data;

import java.util.List;

/**
 * One forward step in the database schema.
 *
 * <p>Migrations are immutable once shipped. Changing the statements of an
 * already-released version would leave servers that ran the old version with a
 * schema that silently disagrees with the code, so a correction is always a new
 * version rather than an edit to an old one.
 *
 * @param version     monotonic, starting at 1
 * @param description shown in logs and stored alongside the applied version
 * @param statements  executed in order, inside a single transaction
 */
public record Migration(int version, String description, List<String> statements) {

    public Migration {
        if (version < 1) {
            throw new IllegalArgumentException("Migration version must be >= 1, got " + version);
        }
        statements = List.copyOf(statements);
    }
}
