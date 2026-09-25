package com.deds.morph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * The PRIVATE half of a player's morph data: everything they own
 * ({@code acquired}) and everything they starred ({@code favourites}) — wave-9
 * item 4, "acquired-list privacy".
 *
 * <p><b>What the original did.</b> iChun's server sent the state LIST to its
 * owner only — {@code PacketDispatcher.sendPacketToPlayer(…, (Player)player)}
 * at {@code O:morph/common/morph/MorphHandler.java:91} and {@code :107}, chunked
 * at 24000 bytes with a {@code "##end"} sentinel — while the WORN morph
 * travelled separately on the {@code MorphInfo} packet broadcast to everybody
 * ({@code O:morph/common/core/EntityHelper.java:130},
 * {@code PacketDispatcher.sendPacketToAllPlayers}). The split is exactly
 * current-to-all / list-to-owner.</p>
 *
 * <p><b>What we did.</b> Ours shipped one {@code Sync.ALL} attachment carrying
 * all three components, so a modified client could enumerate every player's
 * morphs and favourites — real information in a PvP pack. This record is the
 * list half, registered as {@code deds_morph:state} with
 * {@link com.deds.api.attach.PlayerDataSpec.Sync#TARGET_ONLY}; the worn morph
 * moved to {@code deds_morph:worn} with {@code Sync.ALL}.
 * {@link Morph#STATE} composes the two back into a {@link MorphState} so every
 * existing server seam and gametest is unchanged.</p>
 *
 * <p><b>Save migration.</b> This record keeps the existing on-disk attachment
 * key {@code state} and a field shape that is a strict SUBSET of the old
 * {@link MorphState} blob ({@code current} is simply an unread extra field), so
 * a pre-wave-9 world loads every acquisition and every favourite unchanged. The
 * only loss is the worn morph itself, whose new {@code deds_morph:worn}
 * attachment is absent in such a save: an updating player logs in demorphed
 * with their whole collection intact. (SPEC deviation D9-3.)</p>
 *
 * <p>Both lists decode leniently ({@link MorphCodecs#lenientList}) — wave-9
 * item 3.</p>
 *
 * @param acquired   every morph the player has ever acquired, acquisition order
 * @param favourites the starred subset of {@code acquired}, toggle order
 */
public record MorphList(List<MorphVariant> acquired,
        List<MorphVariant> favourites) {

    /** Nothing acquired — the default for fresh players. */
    public static final MorphList EMPTY = new MorphList(List.of(), List.of());

    public MorphList {
        acquired = List.copyOf(acquired);
        favourites = List.copyOf(favourites);
    }

    /**
     * Disk codec. {@code acquired} is {@code optionalFieldOf} (not
     * {@code fieldOf}) so a save whose list was written by an older build — or
     * one whose element decode dropped everything — still yields a usable
     * record rather than failing the attachment outright.
     */
    public static final Codec<MorphList> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    MorphCodecs.lenientList(MorphVariant.CODEC, "acquired")
                            .optionalFieldOf("acquired", List.of())
                            .forGetter(MorphList::acquired),
                    MorphCodecs.lenientList(MorphVariant.CODEC, "favourites")
                            .optionalFieldOf("favourites", List.of())
                            .forGetter(MorphList::favourites)
            ).apply(instance, MorphList::new));

    /** Wire codec — sent to the OWNING player only ({@code Sync.TARGET_ONLY}). */
    public static final StreamCodec<RegistryFriendlyByteBuf, MorphList>
            STREAM_CODEC = StreamCodec.composite(
                    MorphVariant.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    MorphList::acquired,
                    MorphVariant.STREAM_CODEC.apply(ByteBufCodecs.list()),
                    MorphList::favourites,
                    MorphList::new);
}
