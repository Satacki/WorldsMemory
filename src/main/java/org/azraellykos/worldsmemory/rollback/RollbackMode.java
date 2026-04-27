package org.azraellykos.worldsmemory.rollback;

public enum RollbackMode {
    /** Remet le chunk exactement à l'état de génération. Les constructions joueurs sont effacées. */
    SEED_ORIGINAL,
    /** Remet le dernier snapshot connu avant l'événement. Les constructions joueurs sont préservées. */
    ETAT_PRECEDENT,
    /** Restaure uniquement ce que l'explosion a détruit. Les constructions joueurs sont intégralement préservées. */
    DELTA_EXPLOSIONS_ONLY
}
