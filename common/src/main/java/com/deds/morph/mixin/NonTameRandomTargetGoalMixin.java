package com.deds.morph.mixin;

import com.deds.morph.MorphView;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.NonTameRandomTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps a hunt the AI-TARGET bridge started. {@link NearestAttackableTargetGoalMixin}
 * gives a wild wolf a sheep-shaped player as its target, having run the wolf's
 * species selector on the morph copy. But {@code NonTameRandomTargetGoal}
 * (wolves, cats) re-runs its full targeting test every tick in
 * {@code canContinueToUse}, selector included, on the REAL player - which is
 * not a sheep - so the prey goal dropped the target within two ticks
 * (measured 2026-09-24; a vanilla wolf's anger goal happens to pick the same
 * player straight back up, so the hunt did not visibly stop). A modded
 * tamable whose selector casts its argument would have thrown there - a
 * server crash - which is the real reason for this hook.
 *
 * <p>For a morphed player targeted by a goal that does not hunt players, the
 * re-test is done the way the bridge chose the target: the vanilla checks
 * without the selector on the player, the selector on the morph copy, and the
 * class match - so the mob lets go once the player changes form.</p>
 */
@Mixin(NonTameRandomTargetGoal.class)
public abstract class NonTameRandomTargetGoalMixin {

    @Redirect(method = "canContinueToUse", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"
                    + "test(Lnet/minecraft/server/level/ServerLevel;"
                    + "Lnet/minecraft/world/entity/LivingEntity;"
                    + "Lnet/minecraft/world/entity/LivingEntity;)Z"))
    private boolean deds_morph$retestMorphTarget(TargetingConditions conditions,
            ServerLevel level, LivingEntity mob, LivingEntity target) {
        if (target instanceof Player player) {
            Class<? extends LivingEntity> type =
                    ((NearestAttackableTargetGoalAccessor) this).deds_morph$targetType();
            // A player held by a goal that does not hunt players can only have
            // come from the bridge. Never hand it to the real selector - even
            // after a demorph or with the bridge switched off, when the class
            // match simply fails and the goal lets go.
            if (type != Player.class && type != ServerPlayer.class) {
                if (!MorphView.aiRelationships() || !MorphView.aiRelationshipsHunt()) {
                    return false;
                }
                TargetingConditions.Selector selector =
                        ((TargetingConditionsAccessor) (Object) conditions)
                                .deds_morph$selector();
                return MorphView.matchesClass(type, player)
                        && MorphView.matchesSelector(selector, player, level)
                        && conditions.copy().selector(null).test(level, mob, player);
            }
        }
        return conditions.test(level, mob, target);
    }
}
