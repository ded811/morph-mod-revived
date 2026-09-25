package com.deds.morph;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.dolphin.Dolphin;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.animal.equine.TraderLlama;
import net.minecraft.world.entity.animal.feline.Ocelot;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.squid.GlowSquid;
import net.minecraft.world.entity.animal.turtle.Turtle;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.illager.SpellcasterIllager;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.raid.Raider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-type <b>volatile</b> NBT strip rules — the 26.2 recreation of iChun's
 * {@code NBTStripper} (Morph legacy,
 * {@code morph/common/morph/mod/NBTStripper.java}, whose
 * {@code getNBTTagsToStrip(EntityLivingBase)} at lines 99-109 walks the victim's
 * class chain and unions every registered class's strip list). Consumed by
 * {@link Morph#variantOf} on top of the flat, class-independent
 * {@link Morph#TRANSIENT_KEYS}.
 *
 * <p><b>Why this exists (playtest bug 2, 2026-07-29).</b> {@code variantOf}
 * captures the victim's whole save and keeps every key it does not recognise as
 * transient — that is what makes modded variants distinct for free. But a
 * mob-SPECIFIC key that counts, ticks down or records a world position is
 * per-INSTANCE, not per-variant, so keeping it forks one mob into a new morph on
 * every kill. The user hit the worst case: {@code Endermite.Lifetime} is
 * incremented in {@code Endermite.aiStep} and written unconditionally by
 * {@code addAdditionalSaveData} (javap, 26.2), so every endermite that had lived
 * a different number of ticks — i.e. every endermite — was a brand-new morph.
 * {@code AbstractHorse.Temper} (a 0-99 roll at spawn) and
 * {@code NeutralMob.anger_end_time} (an absolute game-time stamp) are the same
 * failure with different numbers.</p>
 *
 * <p><b>Rule shape.</b> Every rule is {@code (type, keys)} and applies when
 * {@code type.isInstance(victim)} — so it covers subclasses (a modded
 * {@code Bee} subclass inherits the bee rules) AND interfaces
 * ({@link NeutralMob}, {@link InventoryCarrier}), which the original's
 * superclass-only walk could not. Scoping by TYPE rather than by key name is
 * what keeps a modded mob whose variant discriminator happens to be called
 * {@code Owner}/{@code state}/{@code Type} intact.</p>
 *
 * <p><b>What is deliberately NOT here</b> (kept as identity, so the selector
 * still shows one entry per genuinely different-looking mob): every appearance
 * discriminator — {@code Color}, {@code Variant}/{@code variant},
 * {@code Sheared}/{@code sheared}, {@code Pumpkin},
 * {@code powered}, {@code Size}/{@code size}, {@code Type}/{@code type},
 * {@code CollarColor}, {@code RabbitType}, {@code MainGene}/{@code HiddenGene},
 * the goat horn/scream flags, {@code VillagerData}, {@code IsBaby},
 * {@code ChestedHorse}, {@code weather_state}, {@code stew_effects} — and
 * {@code CustomName}/{@code CustomNameVisible}, so a name-tagged mob stays its
 * own morph (wave 5 item D; re-confirmed by the user 2026-07-29:
 * "endermites should only have one version other than if you nametag them").</p>
 *
 * <p>{@code sound_variant} used to be on that list and is not any more: it is
 * the mob's VOICE, not its look, and 26.x rolls it at random for every chicken,
 * cow, cat, pig and wolf it spawns, so identical-looking wolves became one
 * morph per voice (user, 2026-09-24: "every time i kill a wolf i get a new
 * morph, they are identical wolfs"). It is stripped per type below.</p>
 *
 * <p>No {@code net.fabricmc} imports — shared server + client. Recreation of
 * iChun's Morph; all credit for the original design to iChun.</p>
 */
public final class MorphNbtStripper {

    /**
     * The rule table, insertion-ordered for readability. Key = the type the rule
     * applies to (class or interface), value = the volatile keys that type
     * writes. Every entry is justified in the audit table in
     * {@code docs/specs/morph/wave8/playtest-round-4.md}.
     */
    private static final Map<Class<?>, Set<String>> RULES = new LinkedHashMap<>();

    private static void rule(Class<?> type, String... keys) {
        RULES.put(type, Set.copyOf(List.of(keys)));
    }

    static {
        // --- cross-cutting interfaces -----------------------------------
        // anger_end_time is an ABSOLUTE game-time stamp and angry_at the UUID of
        // whoever provoked it: killing an angered wolf/bee/enderman/iron golem/
        // polar bear/zombified piglin (i.e. the usual way you kill one) minted a
        // fresh variant every time. AngerTime is the legacy key the same
        // interface still reads.
        rule(NeutralMob.class, "anger_end_time", "angry_at", "AngerTime");
        // Whatever the mob happens to be carrying (villager/piglin/pillager/
        // allay/wandering trader) — inventory contents, never a variant.
        rule(InventoryCarrier.class, "Inventory");

        // --- Mob / Animal / Tamable base --------------------------------
        // The original stripped InLove explicitly (MorphState.java:194); it is a
        // per-instance breeding countdown, LoveCause the feeder's UUID.
        rule(Animal.class, "InLove", "LoveCause");
        // Owner = who tamed it; Sitting = a transient pose toggle.
        rule(TamableAnimal.class, "Owner", "Sitting");

        // --- passive animals --------------------------------------------
        rule(Allay.class, "DuplicationCooldown", "listener");
        rule(Armadillo.class, "state", "scute_time");
        rule(Bat.class, "BatFlags");
        rule(Bee.class, "hive_pos", "flower_pos", "HasNectar", "HasStung",
                "TicksSincePollination", "CannotEnterHiveTicks",
                "CropsGrownSincePollination");
        rule(Camel.class, "LastPoseTick");
        rule(Chicken.class, "IsChickenJockey", "EggLayTime", "sound_variant");
        rule(Dolphin.class, "GotFish", "Moistness");
        rule(Fox.class, "Trusted", "Sleeping", "Sitting", "Crouching");
        rule(GlowSquid.class, "DarkTicksRemaining");
        rule(Ocelot.class, "Trusting");
        rule(Rabbit.class, "MoreCarrotTicks");
        rule(Turtle.class, "has_egg");
        rule(net.minecraft.world.entity.animal.fish.AbstractFish.class,
                "FromBucket");
        rule(net.minecraft.world.entity.animal.axolotl.Axolotl.class,
                "FromBucket");
        rule(net.minecraft.world.entity.animal.frog.Tadpole.class, "FromBucket");
        rule(net.minecraft.world.entity.animal.fish.Pufferfish.class,
                "PuffState");

        // --- voices -------------------------------------------------------
        // A random voice per spawned mob, not a look (see the class comment).
        // The chicken's is in its rule above; these five are every vanilla mob
        // that saves one, on 26.2 and 26.3 alike.
        rule(net.minecraft.world.entity.animal.cow.Cow.class, "sound_variant");
        rule(net.minecraft.world.entity.animal.feline.Cat.class, "sound_variant");
        rule(net.minecraft.world.entity.animal.pig.Pig.class, "sound_variant");
        rule(net.minecraft.world.entity.animal.wolf.Wolf.class, "sound_variant");

        // --- equines -----------------------------------------------------
        // Temper is a fresh 0-99 roll per spawned horse — on its own that made
        // EVERY horse a distinct morph.
        rule(AbstractHorse.class, "EatingHaystack", "Bred", "Temper", "Tame",
                "Owner");
        rule(AbstractChestedHorse.class, "Items");
        rule(Llama.class, "Strength");
        rule(TraderLlama.class, "DespawnDelay");
        rule(SkeletonHorse.class, "SkeletonTrap", "SkeletonTrapTime");

        // --- golems ------------------------------------------------------
        rule(CopperGolem.class, "next_weather_age");
        rule(IronGolem.class, "PlayerCreated");
        rule(HappyGhast.class, "still_timeout");

        // --- monsters ----------------------------------------------------
        rule(Creeper.class, "Fuse", "ExplosionRadius", "ignited");
        // A carried block is scenery the individual picked up, not a species
        // variant — keeping it forked endermen by every block they held.
        rule(EnderMan.class, "carriedBlockState");
        rule(Endermite.class, "Lifetime");           // the reported bug
        rule(Ghast.class, "ExplosionPower");
        rule(PatrollingMonster.class, "patrol_target", "PatrolLeader",
                "Patrolling");
        rule(Phantom.class, "anchor_pos");
        rule(Raider.class, "Wave", "CanJoinRaid", "RaidId");
        rule(Ravager.class, "AttackTick", "StunTick", "RoarTick");
        rule(Shulker.class, "AttachFace", "Peek");
        rule(SpellcasterIllager.class, "SpellTicks");
        rule(SulfurCube.class, "pickup_timer", "from_bucket", "fuse");
        rule(Vex.class, "bound_pos", "life_ticks", "owner");
        rule(Vindicator.class, "Johnny");
        rule(Warden.class, "anger", "listener");
        rule(Hoglin.class, "IsImmuneToZombification", "TimeInOverworld",
                "CannotBeHunted");
        rule(AbstractPiglin.class, "IsImmuneToZombification", "TimeInOverworld");
        rule(Piglin.class, "CannotHunt");
        // 26.3 also writes FreezingTime (-1 unless in powder snow) for every
        // skeleton; harmless on 26.2, which never writes it.
        rule(Skeleton.class, "StrayConversionTime", "FreezingTime");
        rule(Zombie.class, "CanBreakDoors", "InWaterTime",
                "DrownedConversionTime");
        rule(ZombieVillager.class, "ConversionTime", "ConversionPlayer", "Xp",
                "VillagerDataFinalized", "Offers", "Gossips");

        // --- villagers ----------------------------------------------------
        rule(AbstractVillager.class, "Offers");
        rule(Villager.class, "VillagerDataFinalized", "FoodLevel", "Gossips",
                "Xp", "LastRestock", "LastGossipDecay", "RestocksToday",
                "AssignProfessionWhenSpawned");
        rule(WanderingTrader.class, "DespawnDelay", "wander_target");
    }

    private MorphNbtStripper() {
    }

    /**
     * Every volatile key the given victim's type (or any supertype/interface of
     * it) writes. Union of all matching {@link #RULES} entries; empty for a mob
     * with no rule.
     */
    public static Set<String> volatileKeysFor(LivingEntity victim) {
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        for (Map.Entry<Class<?>, Set<String>> entry : RULES.entrySet()) {
            if (entry.getKey().isInstance(victim)) {
                keys.addAll(entry.getValue());
            }
        }
        return keys;
    }

    /** Exposed for the audit gametest: the rule table is non-empty and every
     *  rule targets a {@link LivingEntity} type or an interface a mob can carry. */
    public static int ruleCount() {
        return RULES.size();
    }

    /** True when {@code type} has a rule registered (audit gametest seam). */
    public static boolean hasRule(Class<? extends Mob> type) {
        return RULES.containsKey(type);
    }
}
