package com.streakysmp.log;

import java.util.UUID;

/**
 * One audit record.
 *
 * <p>Built through {@link #builder(AuditAction)} because most fields are
 * optional and which ones apply depends entirely on the action.
 *
 * @param amount   money in minor units where the action moved money, else null
 * @param objectId the id of the claim, listing, shop or plot involved, if any
 */
public record AuditEntry(
        long createdAt,
        AuditAction action,
        UUID actorUuid,
        String actorName,
        UUID targetUuid,
        String targetName,
        Long amount,
        String objectId,
        String details) {

    public static Builder builder(AuditAction action) {
        return new Builder(action);
    }

    public static final class Builder {
        private final AuditAction action;
        private UUID actorUuid;
        private String actorName;
        private UUID targetUuid;
        private String targetName;
        private Long amount;
        private String objectId;
        private String details;

        private Builder(AuditAction action) {
            this.action = action;
        }

        /** The player or console that performed the action. */
        public Builder actor(UUID uuid, String name) {
            this.actorUuid = uuid;
            this.actorName = name;
            return this;
        }

        /** The player the action was performed on or for. */
        public Builder target(UUID uuid, String name) {
            this.targetUuid = uuid;
            this.targetName = name;
            return this;
        }

        /** Money moved, in minor units. */
        public Builder amount(long minorUnits) {
            this.amount = minorUnits;
            return this;
        }

        public Builder object(String id) {
            this.objectId = id;
            return this;
        }

        public Builder details(String details) {
            this.details = details;
            return this;
        }

        public AuditEntry build() {
            return new AuditEntry(System.currentTimeMillis(), action, actorUuid, actorName,
                    targetUuid, targetName, amount, objectId, details);
        }
    }
}
