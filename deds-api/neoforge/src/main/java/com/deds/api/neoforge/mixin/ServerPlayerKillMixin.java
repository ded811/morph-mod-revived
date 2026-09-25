package com.deds.api.neoforge.mixin;

import com.deds.api.event.CombatEvents;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link CombatEvents#PLAYER_KILLED_LIVING} for a PLAYER victim, the mirror of
 * Fabric API's {@code ServerPlayerMixin.callOnKillForPlayer}: in
 * {@code ServerPlayer.die}, at the call of {@code getKillCredit()}.
 *
 * <p>Needed because {@code ServerPlayer.die} never calls
 * {@code LivingEntity.die} (javap, all four jars: no {@code invokespecial} of
 * it), so {@link LivingEntityKillMixin} never sees a player die. At this
 * point the death message, drops and death count are done and the kill
 * credit is about to be read; Morph's own {@code ServerPlayerDieMixin} strips
 * the victim's morphs at the TAIL of the same method, later, which keeps "the
 * killer acquires what the victim was wearing, then the victim loses it".
 *
 * <p>Fabric API also calls {@code attacker.killedEntity(...)} here, its own
 * fix of vanilla (it awards the kill statistic); that is Fabric's platform
 * behaviour, not Ded's API's, and is NOT reproduced. NeoForge's early return
 * for a cancelled {@code LivingDeathEvent} sits before this call, so a
 * cancelled death fires nothing. Exactly one match in both NeoForge builds
 * (javap: offset 253).</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerKillMixin {

    @Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;getKillCredit()"
                            + "Lnet/minecraft/world/entity/LivingEntity;"),
            require = 1, allow = 1)
    private void deds_api$playerKilled(DamageSource source, CallbackInfo ci) {
        if (source.getEntity() instanceof ServerPlayer killer) {
            CombatEvents.PLAYER_KILLED_LIVING.invoke(
                    new CombatEvents.Kill(killer, (ServerPlayer) (Object) this));
        }
    }
}
