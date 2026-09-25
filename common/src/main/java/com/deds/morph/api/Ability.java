package com.deds.morph.api;

import com.deds.api.id.BId;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * A morph ability contributed by ANOTHER mod (wave-9 item 1).
 *
 * <p><b>Original.</b> iChun shipped {@code morph/api/Ability.java:18-117}, an
 * abstract class other mods copied into their own jar and drove through a
 * reflection trampoline ({@code :125-255}, every static a
 * {@code Class.forName("morph.common.ability.AbilityHandler")…invoke} inside a
 * swallowed try/catch) so a consumer needed no hard dependency. Its javadoc
 * states the lifecycle contract precisely, and THAT is the load-bearing part
 * this interface mirrors verbatim:</p>
 * <ul>
 *   <li>the registered object is a template with a null parent, and is
 *       <b>cloned per user</b> ({@code Ability.java:20-23, 77-81});</li>
 *   <li>{@code tick()} runs only while it has a parent ({@code :65-69});</li>
 *   <li>{@code kill()} runs when the ability LEAVES the set — <b>not</b> when
 *       morphing between two forms that both have it ({@code :71-75});</li>
 *   <li>{@code entityHasAbility(EntityLivingBase)} decides whether a given morph
 *       carries it, and the selector's icon pass consults it
 *       ({@code :113-117}).</li>
 * </ul>
 *
 * <p><b>What we deliberately did NOT copy.</b> The reflection trampoline: both
 * loaders have a real optional-dependency story, so a consumer declares
 * {@code deds_morph} as a dependency ({@code suggests}/{@code depends} in
 * fabric.mod.json on Fabric; a {@code [[dependencies.<yourmod>]]} entry with
 * {@code type = "optional"} or {@code "required"} in neoforge.mods.toml on
 * NeoForge), checks that it is loaded when it is optional, and calls
 * {@link AbilityRegistry} directly. For the registration order between two
 * addons on NeoForge, see {@link AbilityRegistry}. And {@code postRender()},
 * which has no
 * analogue in our render architecture — omitted rather than faked. The
 * original's {@code save}/{@code load} pair existed only to ship unknown
 * abilities to the client for icon display; ours needs no sync at all, because
 * {@link #appliesTo} is a PURE function of the morph's dummy and both sides
 * build the same dummy from the same variant (SPEC deviation D9-6).</p>
 *
 * <p><b>Additive-only contract.</b> Every method here except {@link #id()} has a
 * default, so this interface can gain members without breaking an existing
 * implementor.</p>
 */
public interface Ability {

    /**
     * This ability's stable id — the original's {@code getType()}. Also the
     * default icon path ({@code <namespace>:textures/icon/<path>.png}), matching
     * how the built-in abilities name theirs.
     */
    BId id();

    /**
     * Whether a morph of {@code dummy} carries this ability — the original's
     * {@code entityHasAbility}, but promoted from a client-only icon filter to
     * the actual derivation hook, because ours derives abilities generically
     * instead of mapping them per mob class.
     *
     * <p>MUST be pure and side-effect free: it is called with a never-spawned
     * probe entity, its result is cached per morph variant, and both sides rely
     * on it agreeing. Default {@code false} — an ability nobody claims applies
     * to nothing.</p>
     */
    default boolean appliesTo(LivingEntity dummy) {
        return false;
    }

    /**
     * The 12×12 icon drawn in the selector, or null for none. Defaults to
     * {@code <id.namespace>:textures/icon/<id.path>.png} — the built-ins'
     * convention. (The original's icons were 32×32 and so are ours.)
     */
    default BId icon() {
        return BId.of(id().namespace(),
                "textures/icon/" + id().path() + ".png");
    }

    /**
     * Creates this ability's per-player instance — the original's
     * {@code clone()} + {@code setParent()}. Called once when the ability ENTERS
     * a player's set; the returned object is discarded when it leaves.
     * Default: a stateless instance that does nothing, for a pure marker/icon
     * ability.
     */
    default Instance createInstance(Player parent) {
        return new Instance() {
        };
    }

    /**
     * One player's live copy of an {@link Ability}. Both methods default to
     * no-ops so an implementor overrides only what it needs.
     *
     * <p>Morph wraps every dispatch in a try/catch and DISABLES an instance
     * after repeated throws (see {@code MorphAbilities}) — a place the original
     * had no guard at all and we chose to be better rather than equal.</p>
     */
    interface Instance {

        /** Per server tick, while the owning player wears a morph that has it. */
        default void tick() {
        }

        /**
         * The ability has left the player's set (demorph, or a morph change to a
         * form without it). NOT called when morphing between two forms that both
         * carry it — the original's rule, {@code Ability.java:71-75}.
         */
        default void kill() {
        }
    }
}
