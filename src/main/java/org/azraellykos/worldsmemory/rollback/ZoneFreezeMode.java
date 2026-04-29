package org.azraellykos.worldsmemory.rollback;

public enum ZoneFreezeMode {
    /** Freezes the zone during rollback — any external modification is blocked. */
    FREEZE_ZONE,
    /** Allows external modifications without any warning. */
    NO_WARNING,
    /** Aborts the rollback if the zone is modified while it is running. */
    CANCEL_IF_MODIFIED
}
