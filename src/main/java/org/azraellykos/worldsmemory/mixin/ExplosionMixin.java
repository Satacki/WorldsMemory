package org.azraellykos.worldsmemory.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.TntEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import org.azraellykos.worldsmemory.commit.CauseContext;
import org.azraellykos.worldsmemory.commit.CauseModification;
import org.azraellykos.worldsmemory.commit.WorldMemoryState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tags all block changes during an explosion with the correct cause, and captures
 * pre-explosion snapshots so we have the state before any blocks are destroyed.
 *
 * For TntEntity explosions: TntChainTracker already took the pre-snapshot at priming time.
 * We still push CauseContext so adjacent chunks (not covered by the chain) get the right
 * cause, and we pre-snapshot any chunks the chain missed.
 *
 * For all other explosion sources: pre-snapshot and tag as usual.
 */
@Mixin(Explosion.class)
public class ExplosionMixin {

    @Shadow private World world;

    /** Tracks whether this invocation pushed a CauseContext so the RETURN inject can pop correctly. */
    @Unique private boolean wm_causePushed = false;

    @Inject(method = "affectWorld", at = @At("HEAD"))
    private void wm_beforeExplosionAffect(boolean createFire, CallbackInfo ci) {
        wm_causePushed = false;
        if (!(world instanceof ServerWorld serverWorld)) return;

        Explosion self = (Explosion)(Object)this;
        List<BlockPos> affectedBlocks = self.getAffectedBlocks();
        if (affectedBlocks.isEmpty()) return;

        WorldMemoryState wms = WorldMemoryState.get(serverWorld.getRegistryKey());
        if (wms == null) return;

        CauseModification cause = CauseModification.fromExplosionEntity(self.getEntity());

        // Push cause so all setBlockState calls in this explosion get the right tag,
        // including in chunks not covered by TntChainTracker's pre-snapshot.
        CauseContext.push(cause);
        wm_causePushed = true;

        Entity sourceEntity = self.getEntity();
        if (sourceEntity instanceof TntEntity tnt && wms.getTntChainTracker().isTracked(tnt.getUuid())) {
            // Pre-snapshot already taken at priming time. Only snapshot chunks the chain missed
            // (e.g. adjacent chunks near a boundary that the radius estimate didn't cover).
            Set<ChunkPos> affectedChunks = new HashSet<>();
            for (BlockPos pos : affectedBlocks) affectedChunks.add(new ChunkPos(pos));
            wms.preSnapshotMissedChunks(serverWorld, tnt.getUuid(), affectedChunks, cause);
            return;
        }

        Set<ChunkPos> affectedChunks = new HashSet<>();
        for (BlockPos pos : affectedBlocks) affectedChunks.add(new ChunkPos(pos));
        wms.preSnapshotForExplosion(serverWorld, affectedChunks, cause);
    }

    @Inject(method = "affectWorld", at = @At("RETURN"))
    private void wm_afterExplosionAffect(boolean createFire, CallbackInfo ci) {
        if (wm_causePushed) {
            CauseContext.pop();
            wm_causePushed = false;
        }
    }
}
