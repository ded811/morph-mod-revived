package com.deds.morph;

import com.deds.api.Deds;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import java.util.ArrayList;
import java.util.List;

/**
 * Codec helpers for Morph's persistence (wave-9 item 3, "save robustness").
 *
 * <p><b>What the original did.</b> iChun's Morph owned its own world file and
 * defended it three ways: a rotating {@code morph.dat} → {@code morph_backup.dat}
 * copy on every save ({@code O:morph/common/core/EventHandler.java:868-890}), an
 * {@code EOFException} recovery path that promoted the backup over the corrupt
 * file ({@code :791-822}, logging "Save data is corrupted! Attempting to read
 * from backup." → "Restoring data from backup." → "Even your backup data is
 * corrupted. What have you been doing?!"), and a <b>per-entry</b> fallback:
 * a stored state whose entity could not be reconstructed was rebuilt as a
 * {@code Pig} with a regenerated identifier so one dead entry never poisoned the
 * list ({@code O:morph/common/morph/MorphState.java:130-150}). The load path
 * never propagated — a corrupt file cost you your morphs, never your world.</p>
 *
 * <p><b>What we do.</b> Only the third mechanism has an analogue worth porting
 * (see the SPEC's deviation D9-2): our data lives in the vanilla player file,
 * which vanilla already writes atomically and backs up, so the rotation is a
 * duplicate of a vanilla guarantee. {@link #lenientList} is the per-entry half —
 * an element-wise list decoder that logs and SKIPS a broken element instead of
 * failing the whole list, so one unreadable acquisition can never cost a player
 * every other morph they own.</p>
 *
 * <p><b>Deliberately narrow.</b> Only the {@code acquired}/{@code favourites}
 * lists are lenient. The worn morph ({@code deds_morph:worn}) stays strict: a
 * corrupt {@code current} must fail loudly rather than leave a player
 * half-morphed — and because wave-9 item 4 split it into its own attachment, a
 * failure there no longer takes the list down with it.</p>
 *
 * <p>Recreation of iChun's Morph; all credit for the original design to iChun.</p>
 */
public final class MorphCodecs {

    private MorphCodecs() {
    }

    /**
     * A list codec that decodes element-wise and drops (with a WARN naming the
     * raw element) any element that fails, instead of failing the whole list.
     * Encoding is exactly {@code element.listOf()} — leniency is a READ-side
     * property only, so a round-trip of valid data is byte-identical.
     *
     * <p>Note what this does <b>not</b> catch, deliberately: a
     * {@link MorphVariant} whose {@code type} id is simply unregistered (a
     * removed mod) decodes <i>successfully</i> here and stays in the list as a
     * DORMANT entry — {@code MorphEntities.create} already returns null for it
     * and the profile degrades to {@code Profile.BROKEN}. That is strictly
     * better than the original's silent Pig-ification: re-installing the mod
     * restores the morph instead of leaving the player with a pig (SPEC D9-1).</p>
     *
     * @param element the element codec
     * @param what    a short name for the list, used in the warning
     */
    public static <E> Codec<List<E>> lenientList(Codec<E> element, String what) {
        Codec<List<E>> strict = element.listOf();
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<List<E>, T>> decode(DynamicOps<T> ops,
                    T input) {
                return ops.getStream(input).map(stream -> {
                    List<E> out = new ArrayList<>();
                    stream.forEach(raw -> {
                        DataResult<E> decoded = element.parse(ops, raw);
                        decoded.result().ifPresentOrElse(out::add, () ->
                                Deds.LOGGER.warn(
                                        "[deds_morph] skipping unreadable {} entry"
                                                + " {} ({})", what, raw,
                                        decoded.error()
                                                .map(e -> e.message())
                                                .orElse("unknown error")));
                    });
                    return Pair.of(List.copyOf(out), ops.empty());
                });
            }

            @Override
            public <T> DataResult<T> encode(List<E> input, DynamicOps<T> ops,
                    T prefix) {
                return strict.encode(input, ops, prefix);
            }

            @Override
            public String toString() {
                return "LenientList[" + what + "]";
            }
        };
    }
}
