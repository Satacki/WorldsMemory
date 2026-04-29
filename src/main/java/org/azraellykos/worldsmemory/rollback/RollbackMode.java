package org.azraellykos.worldsmemory.rollback;

public enum RollbackMode {
    /** Restores the chunk to its exact world-generation state. Player-built structures are erased. */
    SEED_ORIGINAL,
    /** Restores the last known snapshot before the event. Player-built structures are preserved. */
    ETAT_PRECEDENT,
    /** Restores only what the explosion destroyed. Player-built structures are fully preserved. */
    DELTA_EXPLOSIONS_ONLY
}
