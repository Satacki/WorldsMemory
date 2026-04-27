package org.azraellykos.worldsmemory.mixin;

import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;

// Inactive placeholder — spawn tracking is done via tick poll in Worldsmemory (no injection needed)
@Mixin(PlayerEntity.class)
public abstract class PlayerSpawnpointMixin {
}
