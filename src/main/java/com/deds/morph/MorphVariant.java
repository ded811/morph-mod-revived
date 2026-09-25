package com.deds.morph;

import com.deds.api.id.BId;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

import java.util.Optional;
import java.util.UUID;

/**
 * A morph identity: entity type ({@link BId}) + normalized variant NBT — the
 * wave-2 replacement for the wave-1 BId-only identity. Empty {@code data} = the
 * mob's default variant; a non-empty {@code data} carries the normalized
 * variant discriminators (sheep {@code Color}, slime {@code Size}, wolf
 * {@code CollarColor}, …) that make every variant of every mob (vanilla and
 * modded) a distinct, groupable morph with zero per-mob code.
 *
 * <p>Equality is {@link BId} equality plus {@link CompoundTag} <b>structural</b>
 * equality (order-independent — javap-confirmed), replacing the original's
 * fragile sorted-string identifier. The record's generated {@code equals}/
 * {@code hashCode} give the correct value identity for free.</p>
 *
 * <p>INVARIANT: {@code data} is a normalized (see {@link Morph#variantOf}) tag
 * and is never mutated after construction. Callers pass a fresh/owned tag; the
 * dummy-build boundary {@code .copy()}s before loading it onto an entity.</p>
 *
 * @param type the victim's {@code EntityType} registry id
 * @param data the normalized variant NBT (empty = default variant); never null
 */
public record MorphVariant(BId type, CompoundTag data) {

    public MorphVariant {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (data == null) {
            data = new CompoundTag();
        }
    }

    /**
     * The default (no-extra-NBT) variant of a type — the migration target for a
     * legacy bare id and the shape gametests use when NBT is irrelevant.
     */
    public static MorphVariant ofType(BId type) {
        return new MorphVariant(type, new CompoundTag());
    }

    /** True when this is the mob's default variant (no variant NBT). */
    public boolean isDefaultVariant() {
        return data.isEmpty();
    }

    // ------------------------------------------------------------------
    // player morphs (wave 4, §1.1). Recreation of iChun's Morph.
    // ------------------------------------------------------------------

    /**
     * The sentinel entity type of a PLAYER morph — the registry id of
     * {@link EntityTypes#PLAYER} ({@code minecraft:player}), resolved once from the
     * registry (never a scattered string literal). A player variant additionally
     * carries {@code Id}+{@code Name} in {@link #data}; the bare type alone (as a
     * legacy decode would produce) is NOT a player variant (see {@link #isPlayer}).
     */
    public static final BId PLAYER_TYPE =
            BId.of(EntityType.getKey(EntityTypes.PLAYER).toString());

    /**
     * The ONLY constructor of a player variant: {@code type = }{@link #PLAYER_TYPE},
     * {@code data = { Id, Name }}. {@code Id} is the target's real UUID (the identity
     * discriminator — one morph per player), {@code Name} the username at
     * acquisition (offline nametag + skin-profile resolution).
     */
    public static MorphVariant ofPlayer(UUID id, String name) {
        CompoundTag data = new CompoundTag();
        data.putString("Id", id.toString());
        data.putString("Name", name == null ? "" : name);
        return new MorphVariant(PLAYER_TYPE, data);
    }

    /**
     * True for a player morph. Requiring {@code Id} present is deliberate: the disk
     * {@link #CODEC} decodes a legacy bare {@code "minecraft:player"} string to a
     * DEFAULT (empty-{@code data}) variant, which would otherwise report
     * {@code isPlayer()==true} with empty {@link #playerId()}/{@link #playerName()}
     * and NPE the dummy build / nametag. This enforces "a player variant always
     * carries Id+Name."
     */
    public boolean isPlayer() {
        return type.equals(PLAYER_TYPE) && data.contains("Id");
    }

    /** The target player's UUID for a player variant, else empty. */
    public Optional<UUID> playerId() {
        if (!isPlayer()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(data.getStringOr("Id", "")));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /**
     * The identity {@link GameProfile} for a player variant: the REAL stored UUID
     * plus the stored username, with NO texture properties (wave 5 item E).
     *
     * <p>Deliberately not {@code UUIDUtil.createOfflineProfile(name)}, which is
     * {@code new GameProfile(nameUUIDFromBytes("OfflinePlayer:" + name), name)} — a
     * FAKE name-hash id that discards the very UUID a skin resolve needs. Textures
     * are never carried here: {@code SkinManager} only ever reads a profile's
     * {@code textures} property (it never fetches by UUID), so the real resolve goes
     * through {@code PlayerSkinRenderCache}/{@code ResolvableProfile} by UUID on the
     * client. Pure and shared so it is gametestable without a client.</p>
     */
    public Optional<GameProfile> playerProfile() {
        return playerId().map(id ->
                new GameProfile(id, playerName().orElse("")));
    }

    /** The target player's username-at-acquisition for a player variant, else empty. */
    public Optional<String> playerName() {
        if (!isPlayer()) {
            return Optional.empty();
        }
        String name = data.getStringOr("Name", "");
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /**
     * Selector-grouping key (§1.6): the {@link #type} {@link BId} for a mob, or
     * {@code "player:<uuid>"} for a player variant — so every mob type is one
     * column and every distinct target player is its own column (no cross-player
     * bleed). Returned as {@code Object} because the two key spaces differ.
     */
    public Object groupKey() {
        return isPlayer()
                ? "player:" + playerId().map(UUID::toString).orElse("")
                : type;
    }

    /**
     * True when both are player variants of the SAME target UUID. Applied ONLY at
     * acquisition dedup (§1.2), where a fresh variant's {@code Name} may legitimately
     * differ from a stored one; every other path (owns/without/favourite/select)
     * stays STRUCTURAL so the client's exact-echo mutations keep working.
     */
    public boolean samePlayer(MorphVariant other) {
        return isPlayer() && other.isPlayer()
                && playerId().equals(other.playerId());
    }

    // TODO(deds-api): lift — BId <-> string (de)serialization currencies
    // belong in the API next to BId itself (API v1.1 candidate).
    static final Codec<BId> BID_CODEC =
            Codec.STRING.xmap(BId::of, BId::toString);

    /** The verbose disk shape: {@code {type, data?}} (data omitted when empty). */
    private static final Codec<MorphVariant> OBJECT_CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    BID_CODEC.fieldOf("type").forGetter(MorphVariant::type),
                    CompoundTag.CODEC.optionalFieldOf("data", new CompoundTag())
                            .forGetter(MorphVariant::data)
            ).apply(instance, MorphVariant::new));

    /**
     * Disk codec — <b>this is the save migration</b>: a legacy element is a
     * bare id string (a wave-1 {@code acquired}/{@code current}/{@code
     * favourites} entry), decoded here to a default variant with no datafixer
     * code. New default variants are written back as bare strings (compact +
     * legacy-shaped); only variant-bearing entries write {@code {type,data}}.
     */
    public static final Codec<MorphVariant> CODEC =
            Codec.either(Codec.STRING, OBJECT_CODEC).xmap(
                    either -> either.map(
                            s -> MorphVariant.ofType(BId.of(s)),
                            variant -> variant),
                    variant -> variant.isDefaultVariant()
                            ? Either.left(variant.type().toString())
                            : Either.right(variant));

    // Wire: compact — bare id + optional NBT (absent when default). Both parts
    // need only ByteBuf, so StreamCodec.composite widens to
    // RegistryFriendlyByteBuf fine (matching the wire buffer the net layer uses).
    static final StreamCodec<ByteBuf, BId> BID_STREAM =
            ByteBufCodecs.STRING_UTF8.map(BId::of, BId::toString);

    public static final StreamCodec<RegistryFriendlyByteBuf, MorphVariant>
            STREAM_CODEC = StreamCodec.composite(
                    BID_STREAM, MorphVariant::type,
                    ByteBufCodecs.OPTIONAL_COMPOUND_TAG,
                    variant -> variant.isDefaultVariant()
                            ? Optional.empty()
                            : Optional.of(variant.data()),
                    (t, opt) -> new MorphVariant(t, opt.orElseGet(CompoundTag::new)));
}
