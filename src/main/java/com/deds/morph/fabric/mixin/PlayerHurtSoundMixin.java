package com.deds.morph.fabric.mixin;

import com.deds.morph.MorphSounds;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A morphed player yelps like the mob (wave-9 item 8).
 *
 * <p><b>Original.</b> {@code O:morph/common/core/EventHandler.java:670-687}
 * (a Forge {@code PlaySoundAtEntityEvent}): when the entity is an
 * {@code EntityPlayer}, the sound name is {@code "damage.hit"} and that player
 * has a {@code MorphInfo}, the event's name is REPLACED with
 * {@code ObfHelper.invokeGetHurtSound(...)} — the morph's own protected
 * {@code getHurtSound()}. Applied on both sides ({@code :675} server,
 * {@code :680} client), keyed on {@code info.nextState}, i.e. <b>the form you
 * are becoming</b>, so the new mob's voice is used from the moment the
 * transition starts rather than at tick 80. Only the HURT sound is swapped —
 * the death sound is not, and this port keeps that.</p>
 *
 * <p><b>26.2 shape.</b> {@code Player} overrides
 * {@code protected SoundEvent getHurtSound(DamageSource)} (it now returns
 * {@code source.type().effects().sound()}), so a HEAD inject on that single
 * override is the whole feature — it is reached from
 * {@code LivingEntity.playHurtSound}, so one COMMON mixin covers every side with
 * no client work. A morph whose sound cannot be resolved (unbuildable variant,
 * player morph) returns null from {@link MorphSounds} and falls through to
 * vanilla rather than silencing the player.</p>
 */
@Mixin(Player.class)
public abstract class PlayerHurtSoundMixin {

    @Inject(method = "getHurtSound(Lnet/minecraft/world/damagesource/DamageSource;)"
            + "Lnet/minecraft/sounds/SoundEvent;",
            at = @At("HEAD"), cancellable = true)
    private void deds_morph$morphHurtSound(DamageSource source,
            CallbackInfoReturnable<SoundEvent> cir) {
        SoundEvent morphed = MorphSounds.hurtSoundFor((Player) (Object) this);
        if (morphed != null) {
            cir.setReturnValue(morphed);
        }
    }
}
