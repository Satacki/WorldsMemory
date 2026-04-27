package org.azraellykos.worldsmemory.storage;

import org.azraellykos.worldsmemory.commit.CauseModification;

import java.util.UUID;

/**
 * playerUuid is null for non-player causes (explosions, generation, entities, fluids).
 */
public record SnapshotEntry(long timestamp, String hash, CauseModification cause, UUID playerUuid) {

    public SnapshotEntry(long timestamp, String hash, CauseModification cause) {
        this(timestamp, hash, cause, null);
    }

    public SnapshotEntry(long timestamp, String hash) {
        this(timestamp, hash, CauseModification.INCONNU, null);
    }
}
