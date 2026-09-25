package com.deds.morph;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

/**
 * Morph sound resolution (wave-9 item 8) — shared server+client code, no
 * {@code net.fabricmc} imports; the mixin
 * {@code com.deds.morph.fabric.mixin.PlayerHurtSoundMixin} is its only caller.
 *
 * <p><b>Original.</b> {@code O:morph/common/core/EventHandler.java:670-687}
 * swapped the player's {@code "damage.hit"} for the morph's own
 * {@code getHurtSound()}. Two of its decisions are reproduced deliberately:</p>
 * <ul>
 *   <li><b>Which state.</b> The original keyed on {@code info.nextState} — the
 *       form being morphed INTO — so the new voice starts at transition START.
 *       Ours reads {@link MorphState#current()}, which the server flips at
 *       transition start for exactly the same effect. This is NOT gated on
 *       {@code !isMorphing}, on purpose.</li>
 *   <li><b>Hurt only.</b> The death sound was not swapped, and is not here.</li>
 * </ul>
 *
 * <p>A PLAYER morph resolves to no override (its dummy is a client-only
 * {@code RemotePlayer} and its voice is the player one anyway), so it falls
 * through to vanilla — the correct result with no special case. So does an
 * unbuildable/unknown variant: a broken morph must never mute a player.</p>
 *
 * <p>Cost: the sound is resolved ONCE per variant when
 * {@link MorphEntities.Profile} is built and cached with it, so a hit is a map
 * lookup and never constructs a dummy. Consequence, recorded as a deviation
 * (SPEC D9-5): the resolved sound is the one the morph returns for a GENERIC
 * damage source, so a (modded) mob that varies its hurt sound by damage type
 * always speaks with its generic voice. No vanilla mob does; only
 * {@code Player} does, and a player morph never reaches here.</p>
 */
public final class MorphSounds {

    private MorphSounds() {
    }

    /**
     * The hurt sound {@code player} should make right now, or {@code null} to
     * keep vanilla's.
     */
    public static SoundEvent hurtSoundFor(Player player) {
        Optional<MorphVariant> worn = Morph.STATE.get(player).current();
        if (worn.isEmpty() || worn.get().isPlayer()) {
            return null;
        }
        return MorphEntities.profileOf(worn.get(), player.level()).hurtSound();
    }
}
