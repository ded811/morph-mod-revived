package com.deds.morph.mixin;

import com.deds.morph.MorphLoader;
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
 *
 * <p>The fear distance comes from {@link MorphLoader#villagerFearDistance}:
 * the vanilla table on Fabric, and on NeoForge, which moved villager fear to
 * a data map (data map first, table second), the same lookup NeoForge's own
 * patched sensor does for real mobs, so a datapack that changes or adds a
 * fear distance changes it for a morph of that mob too. "Hostile" is "has a
 * fear distance", which on the vanilla table is exactly the old
 * {@code containsKey} (its values are never null).</p>
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
        if (morphType != null
                && MorphLoader.get().villagerFearDistance(morphType) != null) {
            cir.setReturnValue(true);
        }
    }

    /**
     * The other half. Vanilla {@code isMatchingEntity} follows {@code isHostile}
     * with {@code isClose}, which looks the fear distance up by the candidate's
     * OWN type. For a player that is {@code minecraft:player}, which the table
     * does not hold, and unboxing the missing value threw a NullPointerException
     * in the villager's tick: a server crash for any zombie-shaped player within
     * 16 blocks of a villager. A player is measured by the type it looks like.
     */
    @Inject(method = "isClose", at = @At("HEAD"), cancellable = true)
    private void deds_morph$morphIsClose(LivingEntity body, LivingEntity mob,
            CallbackInfoReturnable<Boolean> cir) {
        if (!(mob instanceof Player player)) {
            return;
        }
        EntityType<?> morphType = MorphView.morphType(player);
        Float distance = morphType == null ? null
                : MorphLoader.get().villagerFearDistance(morphType);
        cir.setReturnValue(distance != null
                && mob.distanceToSqr(body) <= distance * distance);
    }
}
