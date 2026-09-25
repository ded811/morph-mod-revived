package com.deds.morph;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Giant;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;

/**
 * "Is this morph a HUMANOID?" — the single shared predicate behind the
 * lie-down-in-bed rule (playtest bug 3, 2026-07-29).
 *
 * <p><b>The user's rule, verbatim:</b> "for all humanoids and they should lay
 * down. others i dont think so because it wouldn't make sense for a chicken to
 * lay on its back so this is one of those humanoids mimicking people things that
 * only they do." So the discriminator is SHAPE — an upright, people-shaped body
 * — not "does the real mob sleep in a bed" (only players and villagers do that
 * in vanilla, which would have excluded the zombie/skeleton family the user
 * named first).</p>
 *
 * <p><b>How the set was decided.</b> The natural client-side test is "the
 * morph's renderer uses a {@code HumanoidModel}" — the mod already uses exactly
 * that for the crouch pose ({@code MorphDummies.isHumanoidModel}). But (a) it is
 * client-only, so no gametest could ever see it, and (b) it MISSES the vanilla
 * mobs whose bodies are unmistakably people-shaped while their models are
 * hand-rolled: villagers, wandering traders, witches and the four illagers all
 * use {@code VillagerModel}/{@code IllagerModel}, and warden/golems/creaking
 * have bespoke models too. So the shared predicate below enumerates the vanilla
 * humanoid FAMILIES by their common superclass — which also covers every modded
 * subclass of them for free (the same style as
 * {@code MorphAbilities.isStrictlyAquatic}'s {@code WaterAnimal}/{@code Squid}
 * test) — and the client ORs it with the live {@code HumanoidModel} probe so a
 * modded biped that shares no vanilla superclass still lies down.</p>
 *
 * <p>Excluded on purpose, with reasons: <b>allay</b> and <b>breeze</b> (no legs,
 * floating), <b>hoglin/zoglin/ravager</b> (quadrupeds), <b>bat/blaze/ghast/
 * phantom/guardian/slime/spider</b> and every animal (not people-shaped), and
 * <b>shulker</b> — which needs an explicit subtraction because
 * {@code Shulker extends AbstractGolem} in 26.2 (found by the whole-matrix
 * gametest, not by inspection). Bosses (<b>wither</b>, <b>ender dragon</b>) are
 * unacquirable anyway.</p>
 *
 * <p>No {@code net.fabricmc} imports — shared server + client, so the rule is
 * gametestable. Recreation of iChun's Morph.</p>
 */
public final class MorphHumanoid {

    private MorphHumanoid() {
    }

    /**
     * Whether this morph body is people-shaped and should therefore adopt the
     * vanilla sleeping pose in a bed.
     *
     * @param body the morph's dummy entity (or a real one); null ⇒ false
     */
    public static boolean isHumanoid(LivingEntity body) {
        // Shulker extends AbstractGolem in 26.2 (javap) — a box with a lid is
        // not people-shaped, so it is subtracted before the golem family is
        // admitted. Caught by the whole-matrix gametest, not by inspection.
        if (body instanceof net.minecraft.world.entity.monster.Shulker) {
            return false;
        }
        return body instanceof Player                 // player morphs
                || body instanceof Zombie             // zombie, husk, drowned,
                                                      // zombie_villager,
                                                      // zombified_piglin
                || body instanceof AbstractSkeleton   // skeleton, stray, bogged,
                                                      // wither_skeleton, parched
                || body instanceof AbstractPiglin     // piglin, piglin_brute
                || body instanceof AbstractVillager   // villager,
                                                      // wandering_trader
                || body instanceof Witch
                || body instanceof AbstractIllager    // pillager, vindicator,
                                                      // evoker, illusioner
                || body instanceof EnderMan
                || body instanceof Warden
                || body instanceof AbstractGolem      // iron, snow, copper
                || body instanceof Vex
                || body instanceof Creaking
                || body instanceof Giant;
    }

    /**
     * Whether the morph {@code variant} worn in {@code level} is humanoid. Builds
     * (and discards) a probe dummy through {@link MorphEntities}; a PLAYER
     * variant is humanoid by definition ({@code MorphEntities.create} returns
     * null for it because {@code minecraft:player} has no entity factory).
     */
    public static boolean isHumanoid(MorphVariant variant,
            net.minecraft.world.level.Level level) {
        if (variant == null) {
            return false;
        }
        if (variant.isPlayer()) {
            return true;
        }
        return isHumanoid(MorphEntities.create(variant, level));
    }
}
