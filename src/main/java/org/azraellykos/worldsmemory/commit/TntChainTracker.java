package org.azraellykos.worldsmemory.commit;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import org.azraellykos.worldsmemory.WMConfig;
import org.azraellykos.worldsmemory.Worldsmemory;

import java.util.*;

/**
 * Groups primed TNT entities into chains and manages their pre/post snapshots.
 *
 * When a TntEntity starts ticking (first tick = just primed), this tracker:
 *   1. Pre-snapshots all chunks within explosion radius immediately (before any blocks are destroyed).
 *   2. Merges with a nearby active chain if within CHAIN_MERGE_RADIUS; otherwise starts a new chain.
 *   3. Subsequent TNT in the same chain only pre-snapshot *new* chunks not already covered.
 *
 * After max_fuse_in_chain + POST_BUFFER_TICKS server ticks, the chain expires and all affected
 * chunks are queued for a post-snapshot to capture the final post-explosion state.
 *
 * ExplosionMixin skips its own pre-snapshot for TntEntity whose UUID is tracked here,
 * since the snapshot was already taken at the safer, earlier priming time.
 */
public class TntChainTracker {

    private final WorldMemoryState state;
    private final List<TntChain> activeChains = new ArrayList<>();
    private final Map<UUID, TntChain> entityToChain = new HashMap<>();
    /** TNT ignorées par le rate limiter — tracquées pour que isTracked() retourne false. */
    private final Set<UUID> rateLimitedEntities = new HashSet<>();

    // --- Rate limiter : MAX_EXPLOSIONS_PAR_SECONDE ---
    private int explosionsThisSecond = 0;
    private int lastExplosionSecond  = -1;

    TntChainTracker(WorldMemoryState state) {
        this.state = state;
    }

    /**
     * Called when a new primed TNT entity starts ticking for the first time.
     * Must be called from the server thread.
     */
    public void onTntPrimed(ServerWorld world, Vec3d pos, UUID entityId, int fuseTicks, int currentTick) {
        // Rate limit : ne pas dépasser maxExplosionsParSeconde pré-snapshots par seconde.
        int currentSecond = currentTick / 20;
        if (currentSecond != lastExplosionSecond) {
            lastExplosionSecond = currentSecond;
            explosionsThisSecond = 0;
        }
        if (explosionsThisSecond >= WMConfig.get().maxExplosionsParSeconde) {
            Worldsmemory.LOGGER.debug("[WM] [TNT] Rate limit atteint ({}/s) — TNT {} non pré-snapshotée",
                WMConfig.get().maxExplosionsParSeconde, entityId);
            rateLimitedEntities.add(entityId);
            return;
        }
        explosionsThisSecond++;

        TntChain chain = findNearbyChain(pos);
        Set<ChunkPos> affected = computeAffectedChunks(pos);

        if (chain == null) {
            chain = new TntChain(pos, fuseTicks, currentTick);
            state.preSnapshotForExplosion(world, affected, CauseModification.EXPLOSION_TNT);
            chain.snapshotted.addAll(affected);
            chain.allAffected.addAll(affected);
            activeChains.add(chain);
        } else {
            Set<ChunkPos> newChunks = new HashSet<>(affected);
            newChunks.removeAll(chain.snapshotted);
            if (!newChunks.isEmpty()) {
                state.preSnapshotForExplosion(world, newChunks, CauseModification.EXPLOSION_TNT);
                chain.snapshotted.addAll(newChunks);
            }
            chain.allAffected.addAll(affected);
            chain.extend(pos, fuseTicks, currentTick);
        }
        entityToChain.put(entityId, chain);
    }

    /**
     * Returns true if this TNT entity's chunks were already pre-snapshotted at priming time.
     * ExplosionMixin uses this to avoid a duplicate (too-late) pre-snapshot at explosion time.
     */
    public boolean isTracked(UUID entityId) {
        return entityToChain.containsKey(entityId);
    }

    /**
     * Returns true if this chunk belongs to any active TNT chain.
     * DirtyChunkTracker uses this to suppress threshold commits during a chain explosion —
     * the chain's post-snapshot (after fuse_max+5 ticks) is the single authoritative commit.
     */
    public boolean isChunkInActiveChain(ChunkPos pos) {
        for (TntChain chain : activeChains) {
            if (chain.allAffected.contains(pos)) return true;
        }
        return false;
    }

    /**
     * Returns the set of chunks not yet covered by the chain for this entity.
     * Used by ExplosionMixin to pre-snapshot adjacent chunks missed by the radius estimate.
     * The returned chunks are immediately added to the chain's snapshotted and allAffected sets.
     */
    public Set<ChunkPos> claimUnsnapshottedChunks(UUID entityId, Set<ChunkPos> candidates) {
        if (rateLimitedEntities.contains(entityId)) return candidates; // Fallback : toutes les chunks non couvertes
        TntChain chain = entityToChain.get(entityId);
        if (chain == null) return candidates;
        Set<ChunkPos> missed = new HashSet<>(candidates);
        missed.removeAll(chain.snapshotted);
        if (!missed.isEmpty()) {
            chain.snapshotted.addAll(missed);
            chain.allAffected.addAll(missed);
        }
        return missed;
    }

    /**
     * Called every server tick. Expires chains whose max fuse has elapsed and queues
     * a post-snapshot for all affected chunks.
     */
    public void tick(int currentTick) {
        // Nettoyer les entités rate-limitées qui ont exploé il y a plus de 5 secondes.
        int currentSecond = currentTick / 20;
        if (currentSecond > lastExplosionSecond + 5) {
            rateLimitedEntities.clear();
        }
        activeChains.removeIf(chain -> {
            if (currentTick < chain.expiresAtTick) return false;
            for (ChunkPos pos : chain.allAffected) {
                state.scheduleImmediateCommit(pos, CauseModification.EXPLOSION_TNT);
            }
            entityToChain.values().removeIf(c -> c == chain);
            return true;
        });
    }

    private TntChain findNearbyChain(Vec3d pos) {
        for (TntChain chain : activeChains) {
            if (chain.isNear(pos)) return chain;
        }
        return null;
    }

    private static Set<ChunkPos> computeAffectedChunks(Vec3d pos) {
        int r = WMConfig.get().explosionRadius;
        int bx = (int) Math.floor(pos.x);
        int bz = (int) Math.floor(pos.z);
        int minCX = (bx - r) >> 4;
        int maxCX = (bx + r) >> 4;
        int minCZ = (bz - r) >> 4;
        int maxCZ = (bz + r) >> 4;
        Set<ChunkPos> chunks = new HashSet<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                chunks.add(new ChunkPos(cx, cz));
            }
        }
        return chunks;
    }

    // -------------------------------------------------------------------------

    private static final class TntChain {
        final List<Vec3d> positions = new ArrayList<>();
        final Set<ChunkPos> snapshotted = new HashSet<>();
        final Set<ChunkPos> allAffected = new HashSet<>();
        int expiresAtTick;

        TntChain(Vec3d pos, int fuseTicks, int currentTick) {
            positions.add(pos);
            expiresAtTick = currentTick + fuseTicks + WMConfig.get().postBufferTicks;
        }

        void extend(Vec3d pos, int fuseTicks, int currentTick) {
            positions.add(pos);
            int newExpiry = currentTick + fuseTicks + WMConfig.get().postBufferTicks;
            if (newExpiry > expiresAtTick) expiresAtTick = newExpiry;
        }

        boolean isNear(Vec3d pos) {
            int r = WMConfig.get().chainMergeRadius;
            int rSq = r * r;
            for (Vec3d tnt : positions) {
                if (tnt.squaredDistanceTo(pos) <= rSq) return true;
            }
            return false;
        }
    }
}
