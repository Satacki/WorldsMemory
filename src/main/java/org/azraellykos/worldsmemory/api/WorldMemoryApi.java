package org.azraellykos.worldsmemory.api;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.azraellykos.worldsmemory.commit.CauseModification;

import java.util.Set;

/**
 * API publique Phase 2/3 — conservée pour compatibilité descendante.
 *
 * @deprecated Utiliser {@link WorldMemory} (Phase 5) qui offre l'API fluent complète,
 *             les événements {@link WMEvents} et l'enregistrement d'explosifs via
 *             {@link ExplosiveConfig}.
 */
@Deprecated
public final class WorldMemoryApi {

    private WorldMemoryApi() {}

    /**
     * Enregistre une classe d'entité explosive.
     *
     * @deprecated Préférer {@link WorldMemory#registerExplosive(net.minecraft.util.Identifier, ExplosiveConfig)}
     *             qui accepte un {@code Identifier} et une config complète.
     */
    @Deprecated
    public static void registerExplosion(Class<? extends Entity> entityClass, CauseModification cause) {
        CauseModification.registerExplosion(entityClass, cause);
    }

    /**
     * Capture un pre-snapshot pour une destruction personnalisée.
     *
     * @deprecated Utiliser {@link WorldMemory#snapshot(ServerWorld, BlockPos, int)} ou
     *             {@link WorldMemory#snapshotChunks(ServerWorld, Set, CauseModification)}.
     */
    @Deprecated
    public static void preSnapshot(ServerWorld world, BlockPos center, int radius, CauseModification cause) {
        WorldMemory.snapshotChunks(world, chunksAround(center, radius), cause);
    }

    /**
     * Capture un pre-snapshot sur un ensemble explicite de chunks.
     *
     * @deprecated Utiliser {@link WorldMemory#snapshotChunks(ServerWorld, Set, CauseModification)}.
     */
    @Deprecated
    public static void preSnapshotChunks(ServerWorld world, Set<ChunkPos> chunks, CauseModification cause) {
        WorldMemory.snapshotChunks(world, chunks, cause);
    }

    private static Set<ChunkPos> chunksAround(BlockPos center, int radius) {
        int minCX = (center.getX() - radius) >> 4;
        int maxCX = (center.getX() + radius) >> 4;
        int minCZ = (center.getZ() - radius) >> 4;
        int maxCZ = (center.getZ() + radius) >> 4;
        java.util.Set<ChunkPos> chunks = new java.util.HashSet<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                chunks.add(new ChunkPos(cx, cz));
            }
        }
        return chunks;
    }
}
