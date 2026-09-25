package com.deds.morph.fabric.mixin;

import com.deds.morph.Morph;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code loseMorphsOnDeath} (wave 10) — the original's
 * {@code LivingDeathEvent} half ({@code O:morph/common/core/EventHandler.java
 * :691-725}).
 *
 * <p><b>Why the TAIL of {@code ServerPlayer.die} and not the head.</b> The
 * original's single {@code onLivingDeath} handler runs the lose-morphs branch
 * ({@code :694}) and then the killer's acquisition branch ({@code :726}). Ours
 * acquires from Fabric's {@code AFTER_KILLED_OTHER_ENTITY}, which is injected
 * into {@code LivingEntity.die} — reached through
 * {@code ServerPlayer.die -> Player.die -> super}, so it has already fired by
 * the time this TAIL runs. Same ordering: a killer still gets the victim's worn
 * morph, then the victim loses it.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDieMixin {

    // RETURN, not TAIL: TAIL binds the LAST return only, so any early exit in
    // the death path would silently skip the whole option. Morph.loseMorphsOnDeath
    // is idempotent (mode 1 re-empties an empty state; mode 2 returns when there
    // is no worn morph left), so firing at every return is free.
    @Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At("RETURN"))
    private void deds_morph$loseMorphsOnDeath(DamageSource source,
            CallbackInfo ci) {
        Morph.loseMorphsOnDeath((ServerPlayer) (Object) this);
    }
}
