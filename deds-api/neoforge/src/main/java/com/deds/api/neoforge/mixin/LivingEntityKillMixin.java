package com.deds.api.neoforge.mixin;

import com.deds.api.event.CombatEvents;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link CombatEvents#PLAYER_KILLED_LIVING} for a non-player victim, the mirror
 * of Fabric API's {@code AFTER_KILLED_OTHER_ENTITY} hook in
 * {@code LivingEntity.die} (fabric-entity-events-v1, the same in 5.0.5 for
 * 26.2 and 6.0.4 for 26.3): wrapped around the one call of
 * {@code killer.killedEntity(level, victim, source)}, the original runs first
 * and the event fires after it, whatever it returned. The killer is that
 * call's receiver ({@code source.getEntity()}: the shooter, not the arrow),
 * and Ded's bridge passes it on only when it is a server player, exactly as
 * {@code DedsApiFabric} does.
 *
 * <p>This is the point Morph's kill acquisition depends on: by now vanilla
 * has set {@code dead}, scored the kill and stopped the victim sleeping or
 * using an item, and has not yet dropped its loot, so Morph may discard the
 * victim here. NeoForge's own {@code LivingDeathEvent} fires at the START of
 * {@code die}, before any of that, and a listener after Morph's could still
 * cancel the death; it is therefore not used. Exactly one match in both
 * NeoForge builds (javap: offset 140 on 26.2.0.75, 133 on 26.3.0.7-beta).</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityKillMixin {

    @WrapOperation(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;killedEntity("
                            + "Lnet/minecraft/server/level/ServerLevel;"
                            + "Lnet/minecraft/world/entity/LivingEntity;"
                            + "Lnet/minecraft/world/damagesource/DamageSource;)Z"),
            require = 1, allow = 1)
    private boolean deds_api$afterKilledOther(Entity killer, ServerLevel level,
            LivingEntity victim, DamageSource source, Operation<Boolean> original) {
        boolean result = original.call(killer, level, victim, source);
        if (killer instanceof ServerPlayer player) {
            CombatEvents.PLAYER_KILLED_LIVING.invoke(
                    new CombatEvents.Kill(player, victim));
        }
        return result;
    }
}
