package com.deds.morph.fabric.mixin;

import com.deds.morph.MorphRideable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lifts vanilla's player-as-vehicle reject so a rideable morph can carry a
 * passenger (spec §5 / open question). {@code Entity.startRiding(Entity,boolean,
 * boolean)} returns false server-side when {@code !vehicle.getType().canSerialize()}
 * (javap-verified) — and the Player entity type is non-serializable, so a player
 * can never normally be a vehicle. This redirect makes THAT single call report
 * {@code true} only while {@link MorphRideable#isMounting()} (a same-thread guard
 * scoped to our own mount call); every other {@code canSerialize} use is untouched.
 */
@Mixin(Entity.class)
public abstract class EntityStartRidingMixin {

    @Redirect(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/EntityType;canSerialize()Z"))
    private boolean deds_morph$allowMorphVehicle(EntityType<?> type) {
        return MorphRideable.isMounting() || type.canSerialize();
    }
}
