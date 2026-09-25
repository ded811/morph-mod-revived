package com.deds.morph.fabric.mixin;

import com.deds.morph.MorphView;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Brain-based villager fear (spec §A.7 / R3): a morphed player whose morph type
 * is in {@code ACCEPTABLE_DISTANCE_FROM_HOSTILES} counts as hostile, so the
 * villager brain's {@code VillagerHostilesSensor} records it as
 * {@code NEAREST_HOSTILE} and the existing flee behavior runs. Generic over the
 * map (covers modded raiders added to it). Only brain-based case in scope;
 * piglin/hoglin and the long-tail specials are deferred.
 */
@Mixin(VillagerHostilesSensor.class)
public abstract class VillagerHostilesSensorMixin {

    @Inject(method = "isHostile", at = @At("HEAD"), cancellable = true)
    private void deds_morph$morphIsHostile(LivingEntity candidate,
            CallbackInfoReturnable<Boolean> cir) {
        if (!(candidate instanceof Player player) || !MorphView.aiRelationships()) {
            return;
        }
        EntityType<?> morphType = MorphView.morphType(player);
        if (morphType != null && VillagerHostilesSensorAccessor
                .deds_morph$hostiles().containsKey(morphType)) {
            cir.setReturnValue(true);
        }
    }
}
