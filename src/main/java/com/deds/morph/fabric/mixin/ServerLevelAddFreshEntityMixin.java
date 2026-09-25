package com.deds.morph.fabric.mixin;

import com.deds.morph.MorphSandbox;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The interaction sandbox choke point (spec §4). While a morph {@code mobInteract}
 * runs ({@link MorphSandbox#active()}), suppress any spawn that is NOT an
 * {@link ItemEntity} or {@link ExperienceOrb} — so drops/XP still land but a
 * mooshroom's {@code convertTo(Cow)}, a slime split, spawned babies, leash knots,
 * etc. never enter the world. {@code addFreshEntity} is declared on
 * {@code ServerLevel} in 26.2 (javap-verified), so target it directly.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelAddFreshEntityMixin {

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void deds_morph$sandboxSuppressSpawn(Entity entity,
            CallbackInfoReturnable<Boolean> cir) {
        if (MorphSandbox.active()
                && !(entity instanceof ItemEntity)
                && !(entity instanceof ExperienceOrb)) {
            cir.setReturnValue(false); // pretend the spawn was rejected
        }
    }
}
