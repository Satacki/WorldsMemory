package org.azraellykos.worldsmemory.purge;

public enum PurgeType {
    /** Aucune purge — opération no-op. */
    AUCUNE,
    /** Purge administrative : entrée dans l'audit log obligatoire. */
    ADMINISTRATIVE,
    /** Purge conditionnelle : pilotée par des policies composables. */
    CONDITIONNELLE
}
