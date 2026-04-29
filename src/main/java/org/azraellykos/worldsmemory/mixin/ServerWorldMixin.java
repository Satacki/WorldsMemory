package org.azraellykos.worldsmemory.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.azraellykos.worldsmemory.commit.CauseContext;
import org.azraellykos.worldsmemory.commit.DirtyChunkTracker;
import org.azraellykos.worldsmemory.commit.WorldMemoryState;
import org.azraellykos.worldsmemory.rollback.FrozenZoneManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts block state changes in World (where the method is actually defined).
 * Filters to server-side only via instanceof check.
 */
@Mixin(World.class)
public abstract class ServerWorldMixin {

    /**
     * Blocks block state changes inside a zone frozen by an active rollback.
     * The rollback engine's own setBlockState() calls are always allowed through.
     */
    @Inject(
        method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void wm_checkFrozenZone(
        BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (DirtyChunkTracker.isRollbackActive()) return; // The rollback engine itself always writes through

        World world = (World) (Object) this;
        if (!(world instanceof ServerWorld serverWorld)) return;

        ChunkPos chunkPos = new ChunkPos(pos);

        // Pre-capture the chunk state BEFORE its very first modification so that
        // SEED_ORIGINAL always has the true world-gen baseline, not the post-change state.
        WorldMemoryState wms = WorldMemoryState.get(serverWorld.getRegistryKey());
        if (wms != null) {
            wms.preCaptureSeedBeforeFirstChange(serverWorld, chunkPos);
            // If a block entity (chest, furnace, etc.) is about to be replaced and its NBT
            // changed since the last commit, force a snapshot now before the data is lost.
            BlockEntity existingBe = serverWorld.getBlockEntity(pos);
            if (existingBe != null) {
                wms.preCommitIfBeDirty(serverWorld, chunkPos);
            }
        }

        if (FrozenZoneManager.isFrozen(serverWorld.getRegistryKey(), chunkPos)) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    @Inject(
        method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
        at = @At("RETURN")
    )
    private void wm_onBlockStateChanged(
        BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValue()) return;

        World world = (World) (Object) this;
        if (!(world instanceof ServerWorld serverWorld)) return;

        WorldMemoryState wms = WorldMemoryState.get(serverWorld.getRegistryKey());
        if (wms != null) {
            wms.getDirtyTracker().markDirty(new ChunkPos(pos), CauseContext.get());
        }
    }
}
