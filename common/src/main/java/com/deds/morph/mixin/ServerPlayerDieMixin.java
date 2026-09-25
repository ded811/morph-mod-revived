package com.deds.morph.mixin;

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
 * acquires from Ded's API's {@code CombatEvents.PLAYER_KILLED_LIVING}, which
 * for a PLAYER victim fires INSIDE {@code ServerPlayer.die}, at its call of
 * {@code getKillCredit()}: on Fabric from Fabric API's
 * {@code ServerPlayerMixin} ({@code AFTER_KILLED_OTHER_ENTITY}), on NeoForge
 * from Ded's own mirror of it at the same call. (Not from
 * {@code LivingEntity.die}: {@code ServerPlayer.die} never calls it.) That
 * call comes before the end of the method on both loaders, so the event has
 * already fired by the time this TAIL runs. Same ordering as the original: a
 * killer still gets the victim's worn morph, then the victim loses it.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDieMixin {

    // TAIL, not RETURN: RETURN matches EVERY return opcode, and NeoForge adds
    // an early one to ServerPlayer.die, taken when another mod cancels the
    // death through LivingDeathEvent. The player does not die, so they must
    // not lose their morphs either; on Fabric a prevented death never enters
    // die() at all. TAIL binds the last return only, the normal end of the
    // method. Vanilla die() has exactly one return (javap, 26.2 and 26.3), so
    // on Fabric TAIL and RETURN are the same instruction and nothing changes;
    // should vanilla ever grow an early return, the lose-on-death gametests
    // are what would notice.
    @Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At("TAIL"))
    private void deds_morph$loseMorphsOnDeath(DamageSource source,
            CallbackInfo ci) {
        Morph.loseMorphsOnDeath((ServerPlayer) (Object) this);
    }
}
