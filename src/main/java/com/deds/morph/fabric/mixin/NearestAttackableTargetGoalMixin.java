package com.deds.morph.fabric.mixin;

import com.deds.morph.MorphView;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.target.NearestHealableRaiderTargetGoal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AI-TARGET bridge (spec §A.4): makes a mob hunt a morphed player whose morph
 * type matches the goal's {@code targetType} AND passes the goal's own species
 * {@code Selector} (run against a morph probe dummy). {@code @Inject(TAIL)} on
 * {@code findTarget}: only when vanilla found nothing ({@code target == null}),
 * and never on the Player-target path (that is where the hostile-ignore ability
 * lives — spec §A.5, no double-handling). The set field is {@code LivingEntity}
 * (erased, javap-verified — no {@code checkcast}); {@code TargetGoal.canContinueToUse}
 * (canAttack + distance + LOS, no selector) keeps the injected target.
 *
 * <p>Gives wolf→sheep/rabbit/fox (Animal + prey selector), zombie/pillager→villager
 * (AbstractVillager), iron-golem→zombie-morph (Mob + hostile selector) while a
 * cow-morph is NOT hunted by a wolf/golem (selector fails on the cow dummy).</p>
 */
@Mixin(NearestAttackableTargetGoal.class)
public abstract class NearestAttackableTargetGoalMixin {

    @Shadow protected LivingEntity target;
    @Shadow @Final protected Class<? extends LivingEntity> targetType;
    @Shadow @Final protected TargetingConditions targetConditions;

    @Inject(method = "findTarget", at = @At("TAIL"))
    private void deds_morph$targetMorph(CallbackInfo ci) {
        if (this.target != null) {
            return; // this goal already found a target
        }
        if ((Object) this instanceof NearestHealableRaiderTargetGoal) {
            return; // the witch's heal-raiders goal is support, not a hunt: it
                    // would throw harmful potions at a raider-shaped player
        }
        // mob lives on the super TargetGoal; inherited @Shadow doesn't resolve
        // here, so read it via the TargetGoal accessor (this IS a TargetGoal).
        Mob mob = ((TargetGoalAccessor) (Object) this).deds_morph$mob();
        // Respect an already-provoked neutral mob / any other goal's target: only
        // ADD a morph target when the mob is currently targeting nothing (spec §A.5).
        if (mob.getTarget() != null) {
            return;
        }
        if (!MorphView.aiRelationships() || !MorphView.aiRelationshipsHunt()) {
            return;
        }
        Class<? extends LivingEntity> type = this.targetType;
        if (type == Player.class || type == ServerPlayer.class) {
            return; // player path = hostile-ignore territory (no double-handling)
        }
        TargetingConditions.Selector selector =
                ((TargetingConditionsAccessor) (Object) this.targetConditions)
                        .deds_morph$selector();
        if (selector == null && isBroadClass(type)) {
            return; // broad base class + no selector → would hunt EVERY morph
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        // Follow range + search box (equivalent to getFollowDistance()/
        // getTargetSearchArea(), computed directly to avoid an inherited @Shadow).
        double follow = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        AABB searchArea = mob.getBoundingBox().inflate(follow, 4.0, follow);
        double range = Math.min(follow, MorphView.aiRelationshipRange());
        double rangeSq = range * range;
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (ServerPlayer player : MorphView.morphedPlayersIn(level, searchArea)) {
            if (!MorphView.matchesClass(type, player)) {
                continue; // class wall
            }
            if (!MorphView.matchesSelector(selector, player, level)) {
                continue; // selector wall (run against the morph dummy)
            }
            if (!mob.canAttack(player) || !mayHunt(mob, player, level)) {
                continue;
            }
            // Vanilla's own test for this goal, minus the species selector (it
            // ran on the morph copy above): teams, invisibility, the goal's own
            // range. An invisible player, or one on the mob's team, was hunted.
            if (!this.targetConditions.copy().selector(null).test(level, mob, player)) {
                continue;
            }
            double distSq = mob.distanceToSqr(player);
            if (distSq > rangeSq) {
                continue;
            }
            if (!mob.getSensing().hasLineOfSight(player)) {
                continue; // else canContinueToUse would drop it immediately
            }
            if (distSq < bestSq) {
                bestSq = distSq;
                best = player;
            }
        }
        if (best != null) {
            this.target = best;
            MorphView.recordHunter(mob, (Player) best);
        }
    }

    /**
     * A tamed pet or a player-built golem is someone's, so it may only go for
     * a morphed player when vanilla would let it attack that player anyway:
     * the pet's own owner rules, and the server's PvP setting. Without this a
     * pet wolf hunted a skeleton-shaped player and a village-built golem a
     * zombie-shaped one, even with PvP off.
     */
    private static boolean mayHunt(Mob mob, ServerPlayer player, ServerLevel level) {
        if (mob instanceof TamableAnimal tame && tame.isTame()) {
            if (!level.isPvpAllowed()) {
                return false; // someone's pet, owner loaded or not
            }
            LivingEntity owner = tame.getOwner();
            if (owner != null) {
                if (!tame.wantsToAttack(player, owner)) {
                    return false;
                }
                if (owner instanceof Player ownerPlayer
                        && (!ownerPlayer.canHarmPlayer(player) || !level.isPvpAllowed())) {
                    return false; // canHarmPlayer is teams only; PvP is the rule
                }
            }
        }
        if ((mob instanceof IronGolem golem && golem.isPlayerCreated())
                || mob instanceof SnowGolem) {
            return level.isPvpAllowed();
        }
        return true;
    }

    /** Broad base classes that would over-target every morph if a goal keyed on
     *  them with NO species selector (spec Open Questions mitigation). */
    private static boolean isBroadClass(Class<?> type) {
        return type == Mob.class
                || type == PathfinderMob.class
                || type == Animal.class
                || type == Monster.class
                || type == LivingEntity.class;
    }
}
