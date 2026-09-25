package com.deds.morph.mixin;

import com.deds.morph.MorphAbilities;
import com.deds.morph.MorphEntities;
import com.deds.morph.MorphVariant;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

/**
 * Seats a passenger at the MORPH's passenger attachment when the vehicle is a
 * morphed player (spec §6). Vanilla {@code LivingEntity.getPassengerRidingPosition}
 * would place the rider at the player's shoulder point; while morphed we return
 * the morph's {@code PASSENGER} offset (captured in {@link MorphEntities.Profile})
 * rotated by the vehicle's body yaw, so the rider sits on the horse/pig/camel back
 * (on the already-morph-shaped AABB from {@code LivingEntityDimensionsMixin}).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityRidingPositionMixin {

    @Inject(method = "getPassengerRidingPosition", at = @At("HEAD"),
            cancellable = true)
    private void deds_morph$morphRidePosition(Entity passenger,
            CallbackInfoReturnable<Vec3> cir) {
        if (!((Object) this instanceof Player vehicle)) {
            return;
        }
        Optional<MorphVariant> variant = MorphAbilities.committedVariant(vehicle);
        if (variant.isEmpty()) {
            return;
        }
        List<Vec3> seats = MorphEntities
                .profileOf(variant.get(), vehicle.level()).passengerOffsets();
        if (seats.isEmpty()) {
            return; // morph exposes no PASSENGER seat — defer to vanilla
        }
        // Seat rider N at seat N (a happy ghast has up to four); riders beyond the
        // last seat clamp to it. indexOf < 0 (not yet in the list this tick) → seat 0.
        int index = vehicle.getPassengers().indexOf(passenger);
        Vec3 offset = seats.get(Math.min(Math.max(index, 0), seats.size() - 1));
        Vec3 rotated = offset.yRot(-vehicle.yBodyRot * ((float) Math.PI / 180.0f));
        cir.setReturnValue(vehicle.position().add(rotated));
    }
}
