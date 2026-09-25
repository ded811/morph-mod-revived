package com.deds.morph.fabric.mixin;

import com.deds.morph.MorphRideable;
import com.deds.morph.MorphSandbox;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    /**
     * A sandboxed interaction runs the morph's own right-click on a copy that
     * is not in the world. A camel's (or horse's) right-click seats the player
     * on it: the player then rode an invisible entity, and the client desynced.
     * Nothing may start riding while that copy is being clicked.
     */
    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z",
            at = @At("HEAD"), cancellable = true)
    private void deds_morph$noRidingTheSandbox(Entity vehicle, boolean force,
            boolean sendEvent, CallbackInfoReturnable<Boolean> cir) {
        if (MorphSandbox.active()) {
            cir.setReturnValue(false);
        }
    }

    @Redirect(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/EntityType;canSerialize()Z"))
    private boolean deds_morph$allowMorphVehicle(EntityType<?> type) {
        return MorphRideable.isMounting() || type.canSerialize();
    }
}
