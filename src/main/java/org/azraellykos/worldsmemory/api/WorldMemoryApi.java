package org.azraellykos.worldsmemory.api;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.azraellykos.worldsmemory.commit.CauseModification;

import java.util.Set;

/**
 * Public API Phase 2/3 — kept for backwards compatibility.
 *
 * @deprecated Use {@link WorldMemory} (Phase 5) which provides the full fluent API,
 *             {@link WMEvents} events, and explosive registration via {@link ExplosiveConfig}.
 */
@Deprecated
public final class WorldMemoryApi {

    private WorldMemoryApi() {}

    /**
     * Registers an explosive entity class.
     *
     * @deprecated Prefer {@link WorldMemory#registerExplosive(net.minecraft.util.Identifier, ExplosiveConfig)}
     *             which accepts an {@code Identifier} and a full config.
     */
    @Deprecated
    public static void registerExplosion(Class<? extends Entity> entityClass, CauseModification cause) {
        CauseModification.registerExplosion(entityClass, cause);
    }

    /**
     * Captures a pre-snapshot for a custom destruction.
     *
     * @deprecated Use {@link WorldMemory#snapshot(ServerWorld, BlockPos, int)} or
     *             {@link WorldMemory#snapshotChunks(ServerWorld, Set, CauseModification)}.
     */
    @Deprecated
    public static void preSnapshot(ServerWorld world, BlockPos center, int radius, CauseModification cause) {
        WorldMemory.snapshotChunks(world, chunksAround(center, radius), cause);
    }

    /**
     * Captures a pre-snapshot for an explicit set of chunks.
     *
     * @deprecated Use {@link WorldMemory#snapshotChunks(ServerWorld, Set, CauseModification)}.
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
