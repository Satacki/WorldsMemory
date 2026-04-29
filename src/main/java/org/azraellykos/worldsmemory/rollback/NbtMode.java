package org.azraellykos.worldsmemory.rollback;

public enum NbtMode {
    /** Restores the full internal NBT of each block/entity. */
    COMPLET,
    /** Restores block structure but not inventories. */
    PARTIEL,
    /** Restores block states only, without any NBT data. */
    AUCUN
}
