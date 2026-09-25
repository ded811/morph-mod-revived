package com.deds.morph.fabric.client;

import com.deds.api.Deds;
import com.deds.morph.fabric.client.mixin.InventoryScreenInvoker;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.LivingEntity;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Shared STATIC entity preview for the selector and radial: renders a
 * {@link LivingEntity} into a GUI box at a FIXED three-quarter FRONT pose that
 * matches iChun's original {@code drawEntityOnScreen} ({@code glRotatef(25)} body
 * yaw + {@code glRotatef(15)} forward tilt, facing the viewer). Every previewed
 * mob AND the own-form player is a frozen posed stance — it must not move when
 * the player walks/attacks/looks/swims (Part 1).
 *
 * <p>Built via {@link InventoryScreenInvoker} (not
 * {@code extractEntityInInventoryFollowsMouse}, whose camera coupling distorts
 * the pose): we set {@code bodyRot}/{@code yRot}/{@code xRot} explicitly, ZERO
 * every animation-driving render-state field (so a LIVE entity like {@code
 * mc.player} renders static), seat the entity purely from the caller-supplied
 * display size (never the local player's morph-changed dimensions), and pass an
 * identity camera orientation.</p>
 *
 * <p><b>Facing (1c):</b> the render state's {@code bodyRot} is the body yaw and
 * {@code yRot} is the head yaw; vanilla's inventory uses {@code bodyRot=180+a},
 * {@code yRot=a} (head-relative {@code = a-(180+a) = -180}, which renders as the
 * head ALIGNED facing the viewer). Setting {@code yRot=bodyRot} instead
 * (head-relative 0) renders the head BACKWARDS — the bug this fixes.</p>
 *
 * <p>Recreation of iChun's Morph. {@link #BODY_YAW}/{@link #FORWARD_TILT} are
 * playtest-tunable; {@code bodyRot=180} faces the viewer (vanilla-inventory
 * fact), so a front three-quarter cannot face away.</p>
 */
@Environment(EnvType.CLIENT)
final class MorphPreview {

    /** Vanilla player collision size — the own-form preview always seats + scales
     *  as a FULL player, independent of the current morph (1b). */
    static final float PLAYER_WIDTH = 0.6f;
    static final float PLAYER_HEIGHT = 1.8f;

    /** Body yaw: three-quarter FRONT toward the viewer, angled to the RIGHT
     *  (negative → the mob's front-right faces the camera). */
    private static final float BODY_YAW = -25.0f;
    /** Head/body look pitch — 0 = level (no forward/down tilt). */
    private static final float FORWARD_TILT = 0.0f;

    private static boolean failLogged;

    private MorphPreview() {
    }

    /**
     * iChun's per-entity fit: small mobs share scale 16 (so their real sizes
     * show — a big slime dwarfs a small one, a baby is smaller than its adult),
     * large mobs shrink ({@code 16*2.5/extent}). No per-group logic.
     */
    static int fitScale(float extent) {
        float e = extent > 0f ? extent : 1.0f;
        return e > 2.5f ? Math.round(16.0f * 2.5f / e) : 16;
    }

    /**
     * Renders {@code entity} statically posed inside {@code (x0,y0)-(x1,y1)}.
     * {@code dispWidth}/{@code dispHeight} are the display size to SEAT + scale
     * from (the previewed entity's own bb, or the player defaults for the own
     * form) — NEVER the local player's current dimensions (1b). {@code scale} is
     * px-per-block; {@code yOffset} seats vertically (0 = centred).
     */
    static void render(GuiGraphicsExtractor graphics, LivingEntity entity,
            int x0, int y0, int x1, int y1, int scale,
            float dispWidth, float dispHeight, float yOffset) {
        try {
            EntityRenderState state =
                    InventoryScreenInvoker.deds_morph$extractRenderState(entity);
            if (state instanceof LivingEntityRenderState living) {
                freezeAnimation(living, entity);
                // Whole-entity three-quarter FRONT, head ALIGNED with the body
                // (1c): bodyRot = 180+yaw (body faces viewer, turned), yRot = yaw
                // (head-relative -180 = aligned), slight forward/down head tilt.
                living.bodyRot = 180.0f + BODY_YAW;
                living.yRot = BODY_YAW;
                living.xRot = FORWARD_TILT;
                // Seat purely from the caller's display size (1b): overwrite the
                // extracted bb (which for mc.player reflects the current MORPH),
                // so a tiny current morph never shifts/shrinks the previews.
                living.boundingBoxWidth = dispWidth;
                living.boundingBoxHeight = dispHeight;
                living.scale = 1.0f;
            }
            Vector3f translation = new Vector3f(0.0f,
                    dispHeight / 2.0f + yOffset, 0.0f);
            // GUI flip only — NO camera tilt (identity), so the pose is not
            // distorted (unlike the FollowsMouse wrapper).
            Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
            Quaternionf cameraOrient = new Quaternionf();
            graphics.entity(state, scale, translation, rotation, cameraOrient,
                    x0, y0, x1, y1);
        } catch (Exception e) {
            if (!failLogged) {
                failLogged = true;
                Deds.LOGGER.warn("[deds_morph] preview render failed for {}",
                        entity.getType(), e);
            }
        }
    }

    /**
     * Zeroes ONLY the MOTION-driving render-state fields so a LIVE entity (the
     * own-form {@code mc.player}) renders a fixed neutral IDLE stance — no walk
     * cycle, arm swing, swim amount, item-use or death flop (1a) — while the full
     * body (arms + legs) still renders.
     *
     * <p><b>Do NOT touch {@code speedValue}:</b> it is the walk-swing
     * normaliser/DIVISOR in {@code HumanoidModel.setupAnim}
     * ({@code arm.xRot = ... * walkAnimationSpeed / speedValue}). Zeroing it with
     * {@code walkAnimationSpeed = 0} yields {@code 0/0 = NaN}, which collapses the
     * arms/legs (the missing-limbs regression). Zeroing {@code walkAnimationSpeed}
     * alone gives {@code 0/speedValue = 0} — straight, visible limbs.</p>
     *
     * <p>Pose flags (crouch/fall-fly/swim/passenger) are set to the neutral
     * standing values so the idle stance is consistent regardless of what the
     * player is doing; none of them removes a limb. Aquatic morphs are marked
     * in-water so their preview stands upright rather than flopped.</p>
     */
    private static void freezeAnimation(LivingEntityRenderState living,
            LivingEntity entity) {
        living.walkAnimationPos = 0.0f;
        living.walkAnimationSpeed = 0.0f;
        living.wornHeadAnimationPos = 0.0f;
        living.deathTime = 0.0f;
        // A preview must never flash red just because the previewed morph's dummy
        // mirrors a hurt/dying player (hasRedOverlay = hurtTime>0 || deathTime>0).
        living.hasRedOverlay = false;
        living.isAutoSpinAttack = false;
        living.isInWater = entity.getType().builtInRegistryHolder()
                .is(EntityTypeTags.AQUATIC);
        if (living instanceof ArmedEntityRenderState armed) {
            McCompat.clearSwing(armed);
        }
        if (living instanceof AvatarRenderState avatar) {
            // A PLAYER-morph preview dummy is never ticked and carries no cloak
            // history, so extractCapeState computed capeFlap/Lean/Lean2 from
            // (cloak 0,0,0 - the dummy's world position) — a wildly flared cape.
            // A static preview wants a still, hanging cape: zero the three terms.
            avatar.capeFlap = 0.0f;
            avatar.capeLean = 0.0f;
            avatar.capeLean2 = 0.0f;
        }
        if (living instanceof HumanoidRenderState humanoid) {
            // NOTE: speedValue is deliberately left untouched (divisor — see above).
            humanoid.swimAmount = 0.0f;
            humanoid.ticksUsingItem = 0.0f;
            humanoid.isCrouching = false;
            humanoid.isFallFlying = false;
            humanoid.isVisuallySwimming = false;
            humanoid.isPassenger = false;
            humanoid.isUsingItem = false;
        }
    }
}
