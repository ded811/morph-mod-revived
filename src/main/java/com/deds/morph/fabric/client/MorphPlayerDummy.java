package com.deds.morph.fabric.client;

import com.deds.morph.MorphEntities;

import com.mojang.authlib.GameProfile;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.function.Supplier;

/**
 * A never-world-added client dummy for a PLAYER morph (wave 4 §1.4) — the
 * player-morph analogue of the mob dummies {@link MorphEntities#create} builds.
 * A {@link RemotePlayer} so vanilla's {@code getRenderer} picks the correct
 * slim/wide {@code AvatarRenderer} from {@link #getSkin()} and the third-person
 * extract yields an {@code AvatarRenderState}; its {@code GameProfile} Name is
 * the target's, so the floating nametag is the target's username automatically
 * (§1.5). Never added to the world, never level-ticked, negative id.
 *
 * <p>The skin is polled from the skin-lookup supplier every frame:
 * {@code SkinManager.createLookup} yields the default skin immediately and swaps
 * to the downloaded skin when ready, so re-reading in {@link #getSkin()} gives
 * async fallback AND lets {@code getRenderer} re-pick slim/wide once the real
 * skin lands.</p>
 *
 * <p>Recreation of iChun's Morph; all credit for the original design to iChun.</p>
 */
@Environment(EnvType.CLIENT)
public final class MorphPlayerDummy extends RemotePlayer {

    private final Supplier<PlayerSkin> skinLookup;

    /** Whether this dummy was built from a LIVE {@code PlayerInfo} (tab-list) entry
     *  — i.e. down the textured fast path. False means it went down the offline
     *  {@code PlayerSkinRenderCache} path; when the target later joins,
     *  {@code MorphDummies} invalidates on the false→true flip so the dummy is
     *  rebuilt down the fast path (wave 5 item E). */
    private final boolean fromPlayerInfo;

    /** The player currently WEARING this morph, re-pointed every frame by
     *  {@code MorphDummies.pose(...)}; null for a static selector/radial preview. */
    private AbstractClientPlayer source;

    MorphPlayerDummy(ClientLevel level, GameProfile profile,
            Supplier<PlayerSkin> skinLookup, boolean fromPlayerInfo) {
        super(level, profile); // RemotePlayer sets noPhysics = true
        this.skinLookup = skinLookup;
        this.fromPlayerInfo = fromPlayerInfo;
        // Distinct negative id from the shared counter: Entity.getId() throws for
        // id 0 and a collision would corrupt render/extraction, and several player
        // dummies are alive at once (world + transition from/to + preview cache).
        setId(MorphEntities.nextDummyId());
    }

    /** Points this dummy at the player wearing the morph (called each frame from
     *  {@code MorphDummies.pose}) so its cape physics track that player. */
    void setSource(AbstractClientPlayer source) {
        this.source = source;
    }

    /** True when this dummy was built from a live tab-list {@code PlayerInfo}
     *  (textured fast path); false = the offline UUID-resolve path (wave 5 item E). */
    boolean isFromPlayerInfo() {
        return fromPlayerInfo;
    }

    /**
     * Borrow the WEARER's cloak history for cape physics (playtest bug 1).
     * {@code AvatarRenderer.extractCapeState} reads
     * {@code ClientAvatarEntity.avatarState()} and computes
     * {@code getInterpolatedCloakX(p) - Mth.lerp(p, xo, getX())} — i.e. the cloak
     * position is in WORLD space, relative to the entity's own position. This dummy
     * is never ticked, so its own {@link ClientAvatarState} keeps cloak (0,0,0)
     * while {@code pose(...)} puts the dummy at the player's world position: the
     * delta became the player's full world coordinate and the cape shot off /
     * hung wrong. Returning the wearer's state makes the cape flow EXACTLY like
     * their real one (and fixes bob/walk-distance interpolation for free). Read-only
     * here — the dummy is never ticked, so it never mutates the borrowed state.
     */
    @Override
    public ClientAvatarState avatarState() {
        return source != null ? source.avatarState() : super.avatarState();
    }

    @Override
    public PlayerSkin getSkin() {
        return skinLookup.get();
    }

    /**
     * Show every skin layer + the cape (wave 4 review fix 1). Without this, the
     * dummy's {@code DATA_PLAYER_MODE_CUSTOMISATION} byte defaults to 0, so
     * {@code AvatarRenderer.extractRenderState} ANDs that 0 with each
     * {@code PlayerModelPart} mask → hat/hair overlay, jacket, sleeves, pants AND
     * cape all invisible. The target's own layer preference isn't available to us
     * and players are ~always fully layered, so show all (the cape TEXTURE comes
     * from the resolved {@link PlayerSkin#cape()} the skin lookup already provides).
     */
    @Override
    public boolean isModelPartShown(PlayerModelPart part) {
        return true;
    }
}
