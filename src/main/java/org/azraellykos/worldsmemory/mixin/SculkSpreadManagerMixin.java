package org.azraellykos.worldsmemory.mixin;

import net.minecraft.block.entity.SculkSpreadManager;
import org.azraellykos.worldsmemory.commit.CauseContext;
import org.azraellykos.worldsmemory.commit.CauseModification;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tags sculk spread block changes (triggered by mob deaths near a Sculk Catalyst) as ENTITE. */
@Mixin(SculkSpreadManager.class)
public class SculkSpreadManagerMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void wm_pre(CallbackInfo ci) {
        CauseContext.push(CauseModification.ENTITE);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void wm_post(CallbackInfo ci) {
        CauseContext.pop();
    }
}
