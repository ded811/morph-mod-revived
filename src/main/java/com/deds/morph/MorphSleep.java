package com.deds.morph;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * The {@code canSleepMorphed} decision, in shared (no {@code net.fabricmc})
 * code so the mixin stays three lines and a gametest can drive the rule
 * directly.
 *
 * <p><b>Original.</b> {@code canSleepMorphed} defaults to <b>0</b>
 * ({@code O:morph/common/Morph.java:159}: "Can you sleep while morphed? 0 = No,
 * 1 = Yes") and is enforced by refusing the bed with the chat line
 * {@code "You may not rest now, you are in morph"}
 * ({@code O:morph/common/core/EventHandler.java:651-668}).</p>
 *
 * <p><b>Our default is 1 — ALLOW — and that is deliberate (deviation D10-3).</b>
 * The user asked for morphed sleeping to work and signed off the wave-8
 * lie-down; shipping the original's default would immediately un-ship the
 * feature he had just approved. The option is wired, so a pack that wants the
 * original behaviour sets {@code "canSleepMorphed": false} and gets the exact
 * original refusal, message included.</p>
 */
public final class MorphSleep {

    /**
     * The refusal, carrying the original's exact string. 26.2's
     * {@code BedSleepingProblem} is a record whose {@code message()} the bed
     * shows on the action bar, so the text travels with the refusal.
     */
    public static final Player.BedSleepingProblem PROBLEM =
            new Player.BedSleepingProblem(Component.literal(
                    "You may not rest now, you are in morph"));

    private MorphSleep() {
    }

    /**
     * True when this player must be refused the bed: the config says no and the
     * player is morphed.
     *
     * <p>Keyed on {@code MorphState.current()}, i.e. the form being worn from
     * the moment a transition STARTS — the original tested
     * {@code playerMorphInfo.containsKey(username)}, and that map holds an entry
     * for the whole 80-tick transition as well, so mid-morph is refused in both.
     * A player in their own form always sleeps.</p>
     */
    public static boolean refuses(Player player) {
        return !Morph.config().canSleepMorphed()
                && Morph.STATE.get(player).current().isPresent();
    }
}
