package org.azraellykos.worldsmemory.mixin;

import net.minecraft.entity.TntEntity;
import net.minecraft.server.world.ServerWorld;
import org.azraellykos.worldsmemory.commit.WorldMemoryState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects when a TNT entity starts ticking for the first time (i.e., just primed).
 * Notifies TntChainTracker to pre-snapshot affected chunks before any explosion occurs
 * and to group the TNT into a chain with nearby primed TNT.
 */
@Mixin(TntEntity.class)
public class TntEntityMixin {

    @Unique
    private boolean wm_tracked = false;

    @Inject(method = "tick", at = @At("HEAD"))
    private void wm_onFirstTick(CallbackInfo ci) {
        if (wm_tracked) return;
        wm_tracked = true;

        TntEntity self = (TntEntity)(Object)this;
        if (!(self.getWorld() instanceof ServerWorld sw)) return;

        WorldMemoryState wms = WorldMemoryState.get(sw.getRegistryKey());
        if (wms == null) return;

        wms.getTntChainTracker().onTntPrimed(
            sw,
            self.getPos(),
            self.getUuid(),
            self.getFuse(),
            sw.getServer().getTicks()
        );
    }
}
