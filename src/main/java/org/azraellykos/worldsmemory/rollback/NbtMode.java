package org.azraellykos.worldsmemory.rollback;

public enum NbtMode {
    /** Restaure le NBT interne complet de chaque bloc/entité. */
    COMPLET,
    /** Restaure la structure mais pas les inventaires. */
    PARTIEL,
    /** Ne restaure que les états de blocs, sans NBT. */
    AUCUN
}
