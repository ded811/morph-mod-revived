package com.deds.morph.api;

import com.deds.api.id.BId;
import com.deds.morph.Morph;
import com.deds.morph.MorphVariant;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

/**
 * Query + drive another mod's view of Morph (wave-9 item 1) — the counterpart to
 * iChun's {@code morph/api/Api.java:18-153} (backed by
 * {@code morph/common/core/ApiHandler.java:20-141}).
 *
 * <p>Mapping from the original, method by method:</p>
 * <table border="1">
 * <caption>original → ours</caption>
 * <tr><th>Original</th><th>Ours</th></tr>
 * <tr><td>{@code hasMorph(name, isClient)}</td><td>{@link #isMorphed}</td></tr>
 * <tr><td>{@code morphProgress(name, isClient)} (returns
 *     {@code morphProgress/80F})</td><td>{@link #morphProgress}</td></tr>
 * <tr><td>{@code getMorphEntity} / {@code getPrevMorphEntity}</td>
 *     <td>{@link #wornMorph} (the VARIANT, not a live entity — ours never keeps
 *     a server-side morph entity; the previous form is a client-only animation
 *     detail and is not exposed)</td></tr>
 * <tr><td>{@code forceMorph(EntityPlayerMP, EntityLivingBase)}</td>
 *     <td>{@link #forceMorph}</td></tr>
 * <tr><td>{@code forceDemorph(EntityPlayerMP)}</td><td>{@link #forceDemorph}</td></tr>
 * <tr><td>{@code blacklistEntity(Class)}</td><td>not exposed — our blacklist is
 *     the {@code blacklistedMobs} config (ids and entity tags), and a runtime
 *     class-keyed blacklist has no 26.2 analogue</td></tr>
 * <tr><td>{@code isEntityAMorph(living, isClient)} → owner name</td>
 *     <td>not exposed — we never spawn a morph ENTITY (the player IS the morph),
 *     so nothing can be "a morph" in that sense</td></tr>
 * <tr><td>{@code allowNextPlayerRender()} / {@code getMorphSkinTexture()}</td>
 *     <td>not exposed — render-pipeline internals with no stable 26.2 shape</td></tr>
 * </table>
 *
 * <p>Additive-only: this class only ever gains methods.</p>
 */
public final class MorphApi {

    private MorphApi() {
    }

    /** True while {@code player} is wearing any morph (the original's
     *  {@code hasMorph}). Works on both sides — the worn morph is
     *  {@code Sync.ALL}. */
    public static boolean isMorphed(Player player) {
        return wornMorph(player).isPresent();
    }

    /**
     * The morph {@code player} is wearing, or empty for their own form. Present
     * from the moment a change is committed server-side, i.e. from the START of
     * the ~4 s transformation — the same instant the original's
     * {@code nextState} flipped.
     */
    public static Optional<MorphVariant> wornMorph(Player player) {
        return Morph.STATE.get(player).current();
    }

    /** The entity-type id of the worn morph, or empty. Convenience for
     *  consumers that do not want to touch {@link MorphVariant}. */
    public static Optional<BId> wornType(Player player) {
        return wornMorph(player).map(MorphVariant::type);
    }

    /**
     * Transformation progress, 0.0 → 1.0 — the original's
     * {@code morphProgress/80F} ({@code Api.java} / {@code ApiHandler.java}).
     * 1.0 whenever no transformation is running (settled), so a consumer can
     * treat it as "how mob-shaped is this player right now".
     *
     * <p>Server-side only: the transition clock is server state. On a client
     * this always returns 1.0.</p>
     */
    public static float morphProgress(Player player) {
        if (!(player instanceof ServerPlayer server)) {
            return 1.0f;
        }
        return Morph.isMorphing(server) ? Morph.transitionProgress(server) : 1.0f;
    }

    /**
     * Acquires {@code target}'s morph for {@code player} and wears it, without
     * killing the target — the original's {@code forceMorph}, which is exactly
     * what {@code /morph morphtarget} drives. Returns false when a gate
     * refused (already owned, boss, blacklisted, mid-transformation).
     */
    public static boolean forceMorph(ServerPlayer player, LivingEntity target) {
        return Morph.acquireTarget(player, target, /*discard=*/false,
                /*forced=*/true);
    }

    /** Returns {@code player} to their own form. False if they were not morphed
     *  (the original's {@code forceDemorph}). */
    public static boolean forceDemorph(ServerPlayer player) {
        return Morph.demorph(player);
    }
}
