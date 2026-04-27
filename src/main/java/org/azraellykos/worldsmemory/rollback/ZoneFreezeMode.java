package org.azraellykos.worldsmemory.rollback;

public enum ZoneFreezeMode {
    /** Gèle la zone pendant le rollback — toute modification extérieure est annulée. */
    FREEZE_ZONE,
    /** Autorise les modifications extérieures sans avertissement. */
    NO_WARNING,
    /** Annule le rollback si la zone est modifiée pendant son exécution. */
    CANCEL_IF_MODIFIED
}
