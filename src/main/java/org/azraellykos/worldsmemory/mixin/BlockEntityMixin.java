package org.azraellykos.worldsmemory.mixin;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.azraellykos.worldsmemory.commit.DirtyChunkTracker;
import org.azraellykos.worldsmemory.commit.WorldMemoryState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks block entity NBT changes (chest inventory, furnace state, signs, etc.)
 * so that a commit is forced before the block entity is removed.
 */
@Mixin(BlockEntity.class)
public class BlockEntityMixin {

    @Inject(method = "markDirty()V", at = @At("HEAD"))
    private void wm_onBlockEntityDirty(CallbackInfo ci) {
        if (DirtyChunkTracker.isRollbackActive()) return;
        BlockEntity self = (BlockEntity) (Object) this;
        World world = self.getWorld();
        if (!(world instanceof ServerWorld serverWorld)) return;
        WorldMemoryState wms = WorldMemoryState.get(serverWorld.getRegistryKey());
        if (wms != null) {
            wms.getDirtyTracker().markBeDirty(new ChunkPos(self.getPos()));
        }
    }
}
