package org.azraellykos.worldsmemory.mixin;

import org.azraellykos.worldsmemory.commit.CauseContext;
import org.azraellykos.worldsmemory.commit.CauseModification;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tags block changes caused by Enderman pickup/placement as ENTITE. */
@Mixin(targets = {
    "net.minecraft.entity.mob.EndermanEntity$PlaceBlockGoal",
    "net.minecraft.entity.mob.EndermanEntity$PickUpBlockGoal"
})
public class EndermanGoalMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void wm_pre(CallbackInfo ci) {
        CauseContext.push(CauseModification.ENTITE);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void wm_post(CallbackInfo ci) {
        CauseContext.pop();
    }
}
