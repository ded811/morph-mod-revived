package com.deds.morph.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.LivingEntity;

import org.joml.Quaternionfc;

/**
 * The client calls whose SHAPE differs between the Minecraft versions this
 * mod builds for, in one place, so the version difference is this one small
 * file instead of copies of the large files that make the calls.
 *
 * <p>This is the canonical (26.2) version. {@code versions/mc26.3/} carries
 * the same class written against 26.3 (see {@code versions/README.md}); keep
 * the two signature-for-signature identical.</p>
 */
public final class McCompat {

    private McCompat() {
    }

    /**
     * Raw physical down-state of a keyboard key, by the version's own key
     * code ({@code InputConstants.KEY_*} - GLFW codes on 26.2).
     */
    public static boolean keyDown(Minecraft mc, int key) {
        return InputConstants.isKeyDown(mc.getWindow(), key);
    }

    /** Multiplies the pose by a rotation ({@code PoseStack.mulPose} on 26.2). */
    public static void rotate(PoseStack poseStack, Quaternionfc rotation) {
        poseStack.mulPose(rotation);
    }

    /** {@code submitModel} with no sprite and no crumbling overlay. */
    public static <S> void submitModel(OrderedSubmitNodeCollector collector,
            Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
            int lightCoords, int overlayCoords, int tintedColor, int outlineColor) {
        collector.submitModel(model, state, poseStack, renderType, lightCoords,
                overlayCoords, tintedColor, null, outlineColor, null);
    }

    /** {@code submitModelPart} with a tint, no sprite and no crumbling overlay. */
    public static void submitPartTinted(OrderedSubmitNodeCollector collector,
            ModelPart part, PoseStack poseStack, RenderType renderType,
            int lightCoords, int overlayCoords, int tintedColor) {
        collector.submitModelPart(part, poseStack, renderType, lightCoords,
                overlayCoords, null, tintedColor, null);
    }

    /** Mirrors one entity's attack-swing animation onto another. */
    public static void copySwing(LivingEntity to, LivingEntity from) {
        to.attackAnim = from.attackAnim;
        to.oAttackAnim = from.oAttackAnim;
        to.swingingArm = from.swingingArm; // an off-hand swing swings the off arm
    }

    /** A render state that shows no attack swing. */
    public static void clearSwing(ArmedEntityRenderState armed) {
        armed.attackTime = 0.0f;
    }
}
