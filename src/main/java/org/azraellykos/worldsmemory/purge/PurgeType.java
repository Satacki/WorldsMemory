package org.azraellykos.worldsmemory.purge;

public enum PurgeType {
    /** No purge — no-op. */
    AUCUNE,
    /** Administrative purge: audit log entry is mandatory. */
    ADMINISTRATIVE,
    /** Conditional purge: driven by composable policies. */
    CONDITIONNELLE
}
