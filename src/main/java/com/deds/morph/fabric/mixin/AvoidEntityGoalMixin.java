package com.deds.morph.fabric.mixin;

import com.deds.morph.MorphView;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Mixin;
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
 * path unchanged. Gives creeper→cat/ocelot, rabbit/fox→wolf, skeleton→wolf,
 * wolf→llama, generically (vanilla + modded), from the class match alone.
 */
@Mixin(AvoidEntityGoal.class)
public abstract class AvoidEntityGoalMixin {

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
                        && predicate.test((T) player)) {
                    result.add((T) player); // erased list — no CCE (spec §A.1)
                }
            }
        }
        return result;
    }
}
