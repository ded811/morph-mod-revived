package com.deds.morph;

import com.deds.api.id.BId;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Immutable per-player morph data (BINDING CONTRACT — tester codes against
 * this exact shape; change only via docs/specs/morph/SPEC.md).
 *
 * <p><b>Wave-2 "generic variant identity"</b> replaces the wave-1 BId-only
 * identity with {@link MorphVariant} (entity type + normalized NBT) throughout
 * the three components, so every variant of every mob — sheep colours, slime
 * sizes, wolf collars, and any modded discriminator — is a distinct, groupable
 * morph. Backward compatibility is total: the disk {@code CODEC} decodes each
 * legacy bare-id-string element to a default {@link MorphVariant} via
 * {@link MorphVariant#CODEC} (no datafixer code), and default variants round
 * back to bare strings.</p>
 *
 * @param current    the morph the player currently wears; empty = own form.
 *                   Invariant (server-enforced): when present, the variant is
 *                   also in {@link #acquired}.
 * @param acquired   every morph the player has ever acquired, in acquisition
 *                   order, no duplicates. Identity = the victim's
 *                   {@code EntityType} id + normalized variant NBT; re-acquiring
 *                   the exact same variant is a no-op, a NEW variant of an
 *                   already-owned type appends (a horizontal entry in that
 *                   type's selector group).
 * @param favourites the subset of {@link #acquired} the player has starred;
 *                   membership order is toggle order. Every variant here is also
 *                   in {@code acquired} (server-enforced); removing an acquired
 *                   variant drops it from favourites too.
 */
public record MorphState(Optional<MorphVariant> current,
        List<MorphVariant> acquired, List<MorphVariant> favourites) {

    /** No morph worn, nothing acquired — the default for fresh players. */
    public static final MorphState EMPTY =
            new MorphState(Optional.empty(), List.of(), List.of());

    /**
     * Whole-record codec. Element-level migration from wave-1 bare id strings
     * lives in {@link MorphVariant#CODEC}.
     *
     * <p>Since wave-9 item 4 this is NOT the persistence path any more — the
     * record is stored as two attachments ({@code deds_morph:worn} +
     * {@code deds_morph:state}, see {@link MorphList}) and this codec remains
     * as the value-type round-trip used by tooling and the frozen wave-1/2
     * state gametests. The two acquisition lists decode leniently (wave-9 item
     * 3, {@link MorphCodecs#lenientList}), matching {@link MorphList#CODEC}.</p>
     */
    public static final Codec<MorphState> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    MorphVariant.CODEC.optionalFieldOf("current")
                            .forGetter(MorphState::current),
                    MorphCodecs.lenientList(MorphVariant.CODEC, "acquired")
                            .fieldOf("acquired")
                            .forGetter(MorphState::acquired),
                    // optionalFieldOf so wave-1 saves (no favourites) still load
                    MorphCodecs.lenientList(MorphVariant.CODEC, "favourites")
                            .optionalFieldOf("favourites", List.of())
                            .forGetter(MorphState::favourites)
            ).apply(instance, MorphState::new));

    /** Wire codec (attachment sync to the owner and everyone tracking). */
    public static final StreamCodec<RegistryFriendlyByteBuf, MorphState>
            STREAM_CODEC = StreamCodec.composite(
                    ByteBufCodecs.optional(MorphVariant.STREAM_CODEC),
                    MorphState::current,
                    MorphVariant.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    MorphState::acquired,
                    MorphVariant.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    MorphState::favourites,
                    MorphState::new);

    public MorphState {
        if (current == null) {
            throw new IllegalArgumentException("current must not be null");
        }
        acquired = List.copyOf(acquired);
        favourites = List.copyOf(favourites);
    }

    /**
     * Convenience constructor (no favourites) — keeps the common
     * {@code (current, acquired)} shape terse for call sites and gametests;
     * delegates to the canonical constructor with an empty favourites list.
     */
    public MorphState(Optional<MorphVariant> current,
            List<MorphVariant> acquired) {
        this(current, acquired, List.of());
    }

    /** True if this exact variant was ever acquired (structural equality). */
    public boolean owns(MorphVariant variant) {
        return acquired.contains(variant);
    }

    /** True if ANY variant of this entity type was ever acquired. */
    public boolean ownsType(BId type) {
        for (MorphVariant variant : acquired) {
            if (variant.type().equals(type)) {
                return true;
            }
        }
        return false;
    }

    /** The acquired variants of one entity type, in acquisition order (the
     *  selector's horizontal group for that type's vertical column). */
    public List<MorphVariant> variantsOf(BId type) {
        List<MorphVariant> out = new ArrayList<>();
        for (MorphVariant variant : acquired) {
            if (variant.type().equals(type)) {
                out.add(variant);
            }
        }
        return out;
    }

    /**
     * Groups {@code acquired} by {@link MorphVariant#groupKey()} — one vertical
     * selector column per mob type AND per distinct target player (wave 4 §1.6),
     * first-acquisition order preserved. This is the wave-4 selector's
     * per-group-key grouping (supersedes the former by-{@code type} grouping,
     * which would collapse every player morph into a single
     * {@code minecraft:player} column — cross-player bleed).
     */
    public LinkedHashMap<Object, List<MorphVariant>> groupedByKey() {
        LinkedHashMap<Object, List<MorphVariant>> groups = new LinkedHashMap<>();
        for (MorphVariant variant : acquired) {
            groups.computeIfAbsent(variant.groupKey(), k -> new ArrayList<>())
                    .add(variant);
        }
        return groups;
    }

    /** The acquired variants sharing a {@link MorphVariant#groupKey()} (one
     *  selector column), in acquisition order. The key-space analogue of
     *  {@link #variantsOf(BId)} that keeps each target player its own column. */
    public List<MorphVariant> variantsOfKey(Object groupKey) {
        List<MorphVariant> out = new ArrayList<>();
        for (MorphVariant variant : acquired) {
            if (variant.groupKey().equals(groupKey)) {
                out.add(variant);
            }
        }
        return out;
    }

    /** True if this variant is starred as a favourite. */
    public boolean isFavourite(MorphVariant variant) {
        return favourites.contains(variant);
    }

    /** Same acquisitions + favourites, different worn morph (empty = demorph). */
    public MorphState withCurrent(Optional<MorphVariant> target) {
        return new MorphState(target, acquired, favourites);
    }

    /**
     * Appends {@code variant} to the acquisition list (must not be owned yet)
     * and morphs into it — the original's {@code instaMorph=1} acquisition.
     * Favourites carry through unchanged. A NEW variant of an already-owned
     * type appends here (the caller's {@code owns} dedupe only blocks re-adding
     * the exact same variant).
     */
    public MorphState acquireAndMorph(MorphVariant variant) {
        List<MorphVariant> next = new ArrayList<>(acquired);
        next.add(variant);
        return new MorphState(Optional.of(variant), next, favourites);
    }

    /**
     * The acquisition list minus {@code variant} (also dropped from favourites),
     * keeping the worn morph unless it was the removed variant (then demorphed).
     * Degrades safely: {@code removeMorph} blocks removing the currently-worn
     * morph first, so in practice the current variant is never the removed one.
     */
    public MorphState without(MorphVariant variant) {
        List<MorphVariant> next = new ArrayList<>(acquired);
        next.remove(variant);
        List<MorphVariant> favs = new ArrayList<>(favourites);
        favs.remove(variant);
        Optional<MorphVariant> cur = current.equals(Optional.of(variant))
                ? Optional.empty() : current;
        return new MorphState(cur, next, favs);
    }

    /**
     * Toggles {@code variant}'s favourite membership (star ↔ unstar), keeping
     * the worn morph and acquisition list. Pure set-membership flip — the
     * caller ({@code Morph.toggleFavourite}) enforces that {@code variant} is
     * owned.
     */
    public MorphState withFavouriteToggled(MorphVariant variant) {
        List<MorphVariant> favs = new ArrayList<>(favourites);
        if (!favs.remove(variant)) {
            favs.add(variant);
        }
        return new MorphState(current, acquired, favs);
    }
}
