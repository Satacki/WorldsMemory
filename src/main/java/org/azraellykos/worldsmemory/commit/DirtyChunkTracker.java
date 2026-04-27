package org.azraellykos.worldsmemory.commit;

import net.minecraft.util.math.ChunkPos;
import org.azraellykos.worldsmemory.WMConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks which chunks have been modified since the last commit.
 * Also implements the threshold trigger: if a chunk accumulates
 * dirtyThreshold block changes it is flagged for immediate commit.
 *
 * Per-chunk, the dominant CauseModification is tracked with a priority system:
 * the highest-priority cause wins. JOUEUR chunks also track the responsible
 * player UUID for inclusion in SnapshotEntry.
 *
 * JOUEUR chunks use a threshold of 1 — any player block change triggers an
 * immediate commit rather than waiting for the 10-minute temporal trigger.
 */
public class DirtyChunkTracker {

    /**
     * Positionné sur le thread serveur pendant l'exécution du RollbackEngine.
     * Empêche les appels world.setBlockState() du rollback de relancer des commits.
     */
    private static final ThreadLocal<Boolean> ROLLBACK_ACTIVE = ThreadLocal.withInitial(() -> false);

    /**
     * Mode CANCEL_IF_MODIFIED : quand actif, les modifications hors de la zone rollback
     * lèvent le flag ROLLBACK_CANCELLED.
     */
    private static final ThreadLocal<Set<ChunkPos>> CANCEL_WATCH_CHUNKS =
        ThreadLocal.withInitial(Collections::emptySet);
    private static final AtomicBoolean ROLLBACK_CANCELLED = new AtomicBoolean(false);

    public static void beginRollback() { ROLLBACK_ACTIVE.set(true); }
    public static void endRollback()   { ROLLBACK_ACTIVE.set(false); }
    public static boolean isRollbackActive() { return ROLLBACK_ACTIVE.get(); }

    /** Active le cancel watch pour les chunks hors de {@code expectedChunks}. */
    public static void beginCancelWatch(Set<ChunkPos> expectedChunks) {
        ROLLBACK_ACTIVE.set(true);
        CANCEL_WATCH_CHUNKS.set(expectedChunks);
        ROLLBACK_CANCELLED.set(false);
    }

    public static void endCancelWatch() {
        ROLLBACK_ACTIVE.set(false);
        CANCEL_WATCH_CHUNKS.set(Collections.emptySet());
    }

    public static boolean isRollbackCancelled() { return ROLLBACK_CANCELLED.get(); }

    private final ConcurrentHashMap<ChunkPos, AtomicInteger> changeCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, CauseModification> chunkCauses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, UUID> chunkUuids = new ConcurrentHashMap<>();
    /** Chunks où un block entity a changé (markDirty) depuis le dernier commit. */
    private final Set<ChunkPos> beDirtyChunks = ConcurrentHashMap.newKeySet();
    private final WorldMemoryState owner;

    DirtyChunkTracker(WorldMemoryState owner) {
        this.owner = owner;
    }

    /**
     * Called by the setBlockState mixin for every block change.
     * contextCause comes from CauseContext (entity/fluid mixin), defaults to INCONNU.
     * Never downgrades an existing higher-priority cause.
     */
    public void markDirty(ChunkPos pos, CauseModification contextCause) {
        if (ROLLBACK_ACTIVE.get()) {
            // En mode CANCEL_IF_MODIFIED : si le chunk n'est pas dans la zone rollback, lever le flag.
            Set<ChunkPos> expected = CANCEL_WATCH_CHUNKS.get();
            if (!expected.isEmpty() && !expected.contains(pos)) {
                ROLLBACK_CANCELLED.set(true);
            }
            return;
        }
        chunkCauses.merge(pos, contextCause, (existing, incoming) ->
            incoming.getPriority() > existing.getPriority() ? incoming : existing
        );

        AtomicInteger counter = changeCounts.computeIfAbsent(pos, p -> new AtomicInteger(0));
        int count = counter.incrementAndGet();

        CauseModification dominant = chunkCauses.get(pos);
        // Suppress threshold commits for chunks in an active TNT chain — TntChainTracker
        // will issue the single authoritative post-snapshot after fuse_max+5 ticks.
        if (dominant != null && dominant.getPriority() >= CauseModification.EXPLOSION_TNT.getPriority()
                && owner.getTntChainTracker().isChunkInActiveChain(pos)) {
            return;
        }
        // FLUIDE/ENTITE: never threshold-triggered — temporal trigger (10 min) is sufficient.
        // Threshold commits are intended for meaningful/urgent changes, not fluid spread.
        int threshold;
        if (dominant == CauseModification.JOUEUR) {
            threshold = 1;
        } else if (dominant == CauseModification.FLUIDE || dominant == CauseModification.ENTITE) {
            threshold = Integer.MAX_VALUE;
        } else {
            threshold = WMConfig.get().dirtyThreshold;
        }
        if (count >= threshold) {
            changeCounts.remove(pos);
            CauseModification cause = chunkCauses.getOrDefault(pos, CauseModification.INCONNU);
            // UUID is only meaningful for JOUEUR cause — don't inherit it for explosion causes.
            UUID uuid = cause == CauseModification.JOUEUR ? chunkUuids.get(pos) : null;
            owner.scheduleImmediateCommit(pos, cause, uuid);
        }
    }

    /**
     * Upgrades the tracked cause and optionally records the responsible player UUID.
     * If upgrading to JOUEUR and the chunk already has changes, schedules an immediate
     * commit so player actions are saved without delay.
     */
    public void upgradeCause(ChunkPos pos, CauseModification cause, UUID playerUuid) {
        chunkCauses.merge(pos, cause, (existing, incoming) ->
            incoming.getPriority() > existing.getPriority() ? incoming : existing
        );
        if (cause == CauseModification.JOUEUR && playerUuid != null) {
            chunkUuids.put(pos, playerUuid);
        }
        if (cause == CauseModification.JOUEUR) {
            AtomicInteger counter = changeCounts.get(pos);
            if (counter != null && counter.get() > 0) {
                changeCounts.remove(pos);
                owner.scheduleImmediateCommit(pos, CauseModification.JOUEUR, playerUuid);
            }
        }
    }

    /** Convenience overload for non-player causes (no UUID). */
    public void upgradeCause(ChunkPos pos, CauseModification cause) {
        upgradeCause(pos, cause, null);
    }

    /** Marque le chunk comme ayant un block entity modifié (ex: contenu d'un coffre). */
    public void markBeDirty(ChunkPos pos) {
        if (ROLLBACK_ACTIVE.get()) return;
        beDirtyChunks.add(pos);
    }

    /** Retire le chunk de la liste BE-dirty et retourne true si présent. */
    public boolean consumeBeDirty(ChunkPos pos) {
        return beDirtyChunks.remove(pos);
    }

    /**
     * Atomically returns all dirty chunks with their dominant cause, then clears state.
     * Call consumeUuids() immediately after to retrieve associated UUIDs.
     */
    public Map<ChunkPos, CauseModification> consumeDirtyWithCauses() {
        Map<ChunkPos, CauseModification> snapshot = new HashMap<>(chunkCauses);
        chunkCauses.clear();
        changeCounts.clear();
        return snapshot;
    }

    /**
     * Returns all tracked player UUIDs and clears them.
     * Must be called right after consumeDirtyWithCauses().
     */
    public Map<ChunkPos, UUID> consumeUuids() {
        Map<ChunkPos, UUID> snapshot = new HashMap<>(chunkUuids);
        chunkUuids.clear();
        return snapshot;
    }
}
