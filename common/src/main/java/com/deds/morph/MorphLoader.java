package com.deds.morph;

import com.deds.morph.mixin.VillagerHostilesSensorAccessor;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/**
 * The few places where a mod loader changed VANILLA code that Morph leans on,
 * so the shared code cannot simply call vanilla and get the same answer on
 * every loader. Each method's default body is the vanilla answer, which is
 * what Fabric (a loader that does not patch Minecraft) runs; a loader that
 * moved the behaviour installs its own implementation from its entrypoint
 * ({@code MorphNeoForge} does, with {@code NeoForgeMorphLoader}).
 *
 * <p>Not a loader abstraction in general: loader SERVICES (events, networking,
 * player data) go through Ded's API. This is only for vanilla behaviour a
 * loader rewrote, found by auditing every mixin target and every vanilla call
 * Morph makes against the NeoForge-patched Minecraft.</p>
 */
public interface MorphLoader {

    /**
     * How close a villager lets a mob of {@code type} come before it flees,
     * in blocks, or null if villagers do not fear that type at all (then it
     * is not "hostile" to them). Vanilla: the
     * {@code VillagerHostilesSensor.ACCEPTABLE_DISTANCE_FROM_HOSTILES} table.
     * Asked by {@code VillagerHostilesSensorMixin} for the type a morphed
     * player looks like.
     */
    default Float villagerFearDistance(EntityType<?> type) {
        return VillagerHostilesSensorAccessor.deds_morph$hostiles().get(type);
    }

    /**
     * Called by {@code MorphInteractionEffects.run} right after the morph
     * dummy's own {@code mobInteract}, still inside the sandbox, with its
     * result; returns the result to use. Vanilla: unchanged, because vanilla's
     * {@code mobInteract} is where every production interaction (milk, stew,
     * shears) lives.
     */
    default InteractionResult afterMobInteract(Player player, InteractionHand hand,
            Mob mob, InteractionResult result) {
        return result;
    }

    /** The loader behaviour in force: vanilla's unless a loader installed its own. */
    static MorphLoader get() {
        return MorphLoaderSlot.current;
    }

    /**
     * Installs a loader's implementation. Called once, from the loader's mod
     * entrypoint, before {@code Deds.init}.
     */
    static void install(MorphLoader loader) {
        if (loader == null) {
            throw new IllegalArgumentException("MorphLoader must not be null");
        }
        MorphLoaderSlot.current = loader;
    }
}
