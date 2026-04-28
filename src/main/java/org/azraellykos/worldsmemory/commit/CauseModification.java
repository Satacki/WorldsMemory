package org.azraellykos.worldsmemory.commit;

import net.minecraft.entity.Entity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public enum CauseModification {
    JOUEUR,
    EXPLOSION_TNT,
    EXPLOSION_CREEPER,
    EXPLOSION_CRYSTAL,
    EXPLOSION_WITHER,
    EXPLOSION_BLOCK,
    EXPLOSION_MOD,
    ENTITE,
    FLUIDE,
    GENERATION,
    /** Snapshot capturé au redémarrage après un crash — état potentiellement incohérent. */
    POST_CRASH,
    INCONNU;

    private static final ConcurrentHashMap<Class<? extends Entity>, CauseModification> EXPLOSION_REGISTRY =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Identifier, CauseModification> IDENTIFIER_REGISTRY =
            new ConcurrentHashMap<>();

    /**
     * Registers a custom entity class as a known explosion source.
     * Called by third-party mods at init time to map their explosive entity to a cause.
     *
     * @deprecated Prefer {@link org.azraellykos.worldsmemory.api.WorldMemory#registerExplosive}
     *             which accepts an {@link Identifier} and a full {@link org.azraellykos.worldsmemory.api.ExplosiveConfig}.
     */
    @Deprecated
    public static void registerExplosion(Class<? extends Entity> entityClass, CauseModification cause) {
        EXPLOSION_REGISTRY.put(entityClass, cause);
    }

    /**
     * Registers an entity type Identifier as a known explosion source.
     * Called internally by {@link org.azraellykos.worldsmemory.api.ExplosiveConfig#register}.
     */
    public static void registerExplosiveById(Identifier entityTypeId, CauseModification cause) {
        IDENTIFIER_REGISTRY.put(entityTypeId, cause);
    }

    /**
     * Resolves the cause from an explosion's source entity.
     * Checks the third-party registry first, then falls back to built-in vanilla detection.
     * null entity → EXPLOSION_BLOCK (Respawn Anchor, Bed, etc.)
     * PlayerEntity → EXPLOSION_BLOCK (player triggered a block explosion)
     */
    public static CauseModification fromExplosionEntity(Entity source) {
        if (source == null) return EXPLOSION_BLOCK;
        // Identifier-based registry (Phase 5 API) — checked first
        Identifier typeId = Registries.ENTITY_TYPE.getId(source.getType());
        CauseModification byId = IDENTIFIER_REGISTRY.get(typeId);
        if (byId != null) return byId;
        // Class-based legacy registry
        for (Map.Entry<Class<? extends Entity>, CauseModification> entry : EXPLOSION_REGISTRY.entrySet()) {
            if (entry.getKey().isInstance(source)) return entry.getValue();
        }
        if (source instanceof TntEntity || source instanceof TntMinecartEntity) return EXPLOSION_TNT;
        if (source instanceof CreeperEntity) return EXPLOSION_CREEPER;
        if (source instanceof EndCrystalEntity) return EXPLOSION_CRYSTAL;
        if (source instanceof WitherEntity || source instanceof WitherSkullEntity) return EXPLOSION_WITHER;
        if (source instanceof PlayerEntity) return EXPLOSION_BLOCK;
        if (!"minecraft".equals(Registries.ENTITY_TYPE.getId(source.getType()).getNamespace())) return EXPLOSION_MOD;
        return INCONNU;
    }

    /** Higher priority cause wins when multiple causes affect the same chunk. */
    public int getPriority() {
        return switch (this) {
            case EXPLOSION_WITHER, EXPLOSION_CRYSTAL                        -> 5;
            case EXPLOSION_TNT, EXPLOSION_CREEPER, EXPLOSION_BLOCK,
                 EXPLOSION_MOD                                              -> 4;
            case JOUEUR                                                     -> 3;
            case ENTITE, FLUIDE, GENERATION, POST_CRASH                    -> 2;
            case INCONNU                                                    -> 1;
        };
    }
}
