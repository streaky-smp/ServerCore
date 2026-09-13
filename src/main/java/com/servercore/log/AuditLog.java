package com.servercore.log;

import com.servercore.core.Scheduling;
import com.servercore.core.Service;
import com.servercore.data.Database;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Durable, append-only record of everything that moves money or ownership.
 *
 * <p>Writes are queued and flushed in batches on a timer rather than committed
 * inline. A busy server can produce hundreds of shop transactions a minute, and
 * one INSERT with its own transaction per event would put the audit log on the
 * critical path of every purchase.
 *
 * <p>The trade-off is a bounded window (one flush interval) in which a hard
 * crash loses recent audit rows. That is acceptable for an audit trail but would
 * not be for balances themselves, which is why balances are written
 * synchronously inside their own transaction and never through this class.
 */
public final class AuditLog implements Service {

    /**
     * Generous but bounded. If this fills, the flusher is wedged, and the right
     * response is to complain loudly rather than grow until the heap dies.
     */
    private static final int QUEUE_CAPACITY = 20_000;

    private static final int BATCH_SIZE = 500;

    private final Database database;
    private final Scheduling scheduling;
    private final Logger logger;
    private final long flushIntervalTicks;
    private final boolean mirrorToServerLog;

    private final BlockingQueue<AuditEntry> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private org.bukkit.scheduler.BukkitTask flushTask;
    private volatile boolean shuttingDown;

    public AuditLog(Database database,
                    Scheduling scheduling,
                    Logger logger,
                    long flushIntervalTicks,
                    boolean mirrorToServerLog) {
        this.database = database;
        this.scheduling = scheduling;
        this.logger = logger;
        this.flushIntervalTicks = flushIntervalTicks;
        this.mirrorToServerLog = mirrorToServerLog;
    }

    @Override
    public void onEnable() {
        flushTask = scheduling.asyncTimer(this::drainQuietly, flushIntervalTicks, flushIntervalTicks);
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        // Drain synchronously on the shutdown thread. Losing the tail of the
        // audit log on a clean stop would be a silent correctness hole.
        try {
            drain();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to flush audit log during shutdown; "
                    + queue.size() + " entries lost", e);
        }
    }

    /**
     * Records an event. Safe to call from any thread, including the main thread.
     *
     * <p>Never blocks and never throws: auditing must not be able to fail the
     * operation it is describing.
     */
    public void record(AuditEntry entry) {
        if (mirrorToServerLog) {
            logger.info(format(entry));
        }
        if (shuttingDown) {
            // Past the point where the flusher runs; write it out directly so it
            // is not silently discarded.
            logger.info("[audit] " + format(entry));
            return;
        }
        if (!queue.offer(entry)) {
            // Dropping from the database is bad, so make sure the record still
            // exists somewhere an operator can find it.
            logger.severe("Audit queue full (" + QUEUE_CAPACITY + "); writing to server log instead: "
                    + format(entry));
        }
    }

    /** Convenience for the common shape. */
    public void record(AuditAction action, UUID actorUuid, String actorName, String details) {
        record(AuditEntry.builder(action).actor(actorUuid, actorName).details(details).build());
    }

    /**
     * Records a failed validation.
     *
     * <p>Always mirrored to the server log regardless of configuration, because
     * these are the entries an operator goes looking for after an incident.
     */
    public void securityViolation(UUID actorUuid, String actorName, String what) {
        logger.warning("[security] " + actorName + " (" + actorUuid + "): " + what);
        record(AuditEntry.builder(AuditAction.SECURITY_VIOLATION)
                .actor(actorUuid, actorName)
                .details(what)
                .build());
    }

    private void drainQuietly() {
        try {
            drain();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Audit log flush failed; entries remain queued", e);
        }
    }

    /** Writes everything currently queued, in batches. */
    private void drain() {
        List<AuditEntry> batch = new ArrayList<>(BATCH_SIZE);
        while (!queue.isEmpty()) {
            batch.clear();
            queue.drainTo(batch, BATCH_SIZE);
            if (batch.isEmpty()) {
                return;
            }
            writeBatch(batch);
        }
    }

    private void writeBatch(List<AuditEntry> batch) {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sc_audit_log
                        (created_at, action, actor_uuid, actor_name,
                         target_uuid, target_name, amount, object_id, details)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (AuditEntry entry : batch) {
                    ps.setLong(1, entry.createdAt());
                    ps.setString(2, entry.action().name());
                    setNullableString(ps, 3, entry.actorUuid() == null ? null : entry.actorUuid().toString());
                    setNullableString(ps, 4, entry.actorName());
                    setNullableString(ps, 5, entry.targetUuid() == null ? null : entry.targetUuid().toString());
                    setNullableString(ps, 6, entry.targetName());
                    if (entry.amount() == null) {
                        ps.setNull(7, Types.INTEGER);
                    } else {
                        ps.setLong(7, entry.amount());
                    }
                    setNullableString(ps, 8, entry.objectId());
                    setNullableString(ps, 9, entry.details());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    private static void setNullableString(PreparedStatement ps, int index, String value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static String format(AuditEntry entry) {
        StringBuilder sb = new StringBuilder(entry.action().name());
        if (entry.actorName() != null) {
            sb.append(" actor=").append(entry.actorName()).append('(').append(entry.actorUuid()).append(')');
        }
        if (entry.targetName() != null) {
            sb.append(" target=").append(entry.targetName()).append('(').append(entry.targetUuid()).append(')');
        }
        if (entry.amount() != null) {
            sb.append(" amount=").append(entry.amount());
        }
        if (entry.objectId() != null) {
            sb.append(" object=").append(entry.objectId());
        }
        if (entry.details() != null) {
            sb.append(" details=").append(entry.details());
        }
        return sb.toString();
    }

    /** Number of entries waiting to be written, for the admin diagnostics screen. */
    public int pending() {
        return queue.size();
    }
}
