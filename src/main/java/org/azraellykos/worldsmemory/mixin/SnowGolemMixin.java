package org.azraellykos.worldsmemory.mixin;

import net.minecraft.entity.passive.SnowGolemEntity;
import org.azraellykos.worldsmemory.commit.CauseContext;
import org.azraellykos.worldsmemory.commit.CauseModification;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tags snow trail block placements from Snow Golems as ENTITE.
 * In 1.20.1, SnowGolemEntity places snow directly in its tickMovement() override,
 * not in mobTick() (which it does not declare). tickMovement() covers both
 * normal AI-driven walking and knockback/physics movement.
 */
@Mixin(SnowGolemEntity.class)
public class SnowGolemMixin {

    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void wm_preTickMovement(CallbackInfo ci) {
        CauseContext.push(CauseModification.ENTITE);
    }

    @Inject(method = "tickMovement", at = @At("RETURN"))
    private void wm_postTickMovement(CallbackInfo ci) {
        CauseContext.pop();
    }
}
