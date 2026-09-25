package com.deds.morph.mixin;

import com.deds.morph.MorphView;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.function.Predicate;

/**
 * AI-AVOID bridge (spec §A.3): makes a mob flee a morphed player whose morph
 * type matches the goal's {@code avoidClass}. {@code @Redirect}s the
 * {@code getEntitiesOfClass(avoidClass, box, pred)} call inside
 * {@code AvoidEntityGoal.canUse} to append matching morphed players to the
 * result. The list flows only into {@code ServerLevel.getNearestEntity(...) ->
 * toAvoid:LivingEntity} (erased, javap-verified — no {@code checkcast}), so a
 * {@code Player} is safe in a {@code List<Cat>}; vanilla then computes the flee
 * path unchanged. Gives creeper→cat/ocelot, rabbit→wolf, skeleton→wolf,
 * generically (vanilla + modded), from the class match alone.
 *
 * <p>But {@code getNearestEntity} runs the goal's targeting test on every
 * entry, and that test runs the goal's avoid predicate, which some mobs write
 * as a cast: the fox's wolf predicate is {@code ((Wolf) e).isTame()}, the
 * spider's armadillo predicate {@code ((Armadillo) e).isScared()}. A player
 * there threw a ClassCastException in the mob's tick, crashing the server. So
 * a player is only appended once that same test has passed without throwing;
 * a mob whose predicate cannot take a player simply does not flee the morph.</p>
 */
@Mixin(AvoidEntityGoal.class)
public abstract class AvoidEntityGoalMixin {

    @Shadow
    @Final
    protected PathfinderMob mob;

    @Shadow
    @Final
    private TargetingConditions avoidEntityTargeting;

    @SuppressWarnings("unchecked")
    @Redirect(method = "canUse", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass"
                    + "(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;"
                    + "Ljava/util/function/Predicate;)Ljava/util/List;"))
    private <T extends LivingEntity> List<T> deds_morph$appendMorphs(Level self,
            Class<T> avoidClass, AABB box, Predicate<? super T> predicate) {
        List<T> result = self.getEntitiesOfClass(avoidClass, box, predicate);
        if (self instanceof ServerLevel level && MorphView.aiRelationships()) {
            for (ServerPlayer player : MorphView.morphedPlayersIn(level, box)) {
                if (MorphView.matchesClass(avoidClass, player)
                        && predicate.test((T) player)
                        && this.deds_morph$passesAvoidTest(level, player)) {
                    result.add((T) player); // erased list — no CCE (spec §A.1)
                }
            }
        }
        return result;
    }

    /** The goal's own targeting test on a morphed player, false if its avoid
     *  predicate cannot take a player (see the class comment): a cast, or, in
     *  a modded mob, a lookup by type or a brain memory a player lacks. Vanilla
     *  repeats this exact test in the same tick, so a player that passes here
     *  cannot throw there. */
    @Unique
    private boolean deds_morph$passesAvoidTest(ServerLevel level, ServerPlayer player) {
        try {
            return this.avoidEntityTargeting.test(level, this.mob, player);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
