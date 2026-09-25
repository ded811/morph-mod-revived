package com.deds.morph.fabric.client;

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

import java.lang.reflect.Field;

/**
 * The client calls whose SHAPE differs between Minecraft versions - the
 * 26.3 version of {@code src/main/java/.../McCompat.java}. Same signatures;
 * each body names 26.3's API:
 *
 * <ul>
 * <li>26.3's window layer is SDL: {@code InputConstants.isKeyDown(int)} reads
 *     SDL's keyboard state by scancode, and {@code InputConstants.KEY_*} are
 *     SDL scancodes, so callers passing those constants stay correct.</li>
 * <li>{@code submitModel} / {@code submitModelPart} lost the crumbling-overlay
 *     argument and take a {@code UvMapping} where 26.2 took a sprite.</li>
 * <li>The attack swing moved from {@code LivingEntity.attackAnim}/
 *     {@code oAttackAnim} into a private {@code LivingEntity.SwingState}
 *     ({@code currentSwing, ticks, oldAnimation, animation}), and the render
 *     state's {@code attackTime} became {@code swingAnimation} +
 *     {@code currentSwing}.</li>
 * </ul>
 */
public final class McCompat {

    private McCompat() {
    }

    public static boolean keyDown(Minecraft mc, int key) {
        return InputConstants.isKeyDown(key);
    }

    /** Multiplies the pose by a rotation ({@code PoseStack.rotate} on 26.3). */
    public static void rotate(PoseStack poseStack, Quaternionfc rotation) {
        poseStack.rotate(rotation);
    }

    public static <S> void submitModel(OrderedSubmitNodeCollector collector,
            Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
            int lightCoords, int overlayCoords, int tintedColor, int outlineColor) {
        collector.submitModel(model, state, poseStack, renderType, lightCoords,
                overlayCoords, tintedColor, null, outlineColor);
    }

    public static void submitPartTinted(OrderedSubmitNodeCollector collector,
            ModelPart part, PoseStack poseStack, RenderType renderType,
            int lightCoords, int overlayCoords, int tintedColor) {
        collector.submitModelPart(part, poseStack, renderType, lightCoords,
                overlayCoords, null, tintedColor);
    }

    /*
     * The swing state is private with no setter, so it is copied field by
     * field by reflection. 26.x ships unobfuscated, so these names are the
     * runtime names in development and in a player's game alike. Resolved
     * once; if a future build renames them, the copy is skipped (the morph
     * then simply shows no arm swing) instead of breaking the render.
     */
    private static final Field SWING_STATE = field(LivingEntity.class, "swingState");
    private static final Class<?> SWING_STATE_TYPE =
            SWING_STATE == null ? null : SWING_STATE.getType();
    private static final Field CURRENT_SWING = field(SWING_STATE_TYPE, "currentSwing");
    private static final Field TICKS = field(SWING_STATE_TYPE, "ticks");
    private static final Field OLD_ANIMATION = field(SWING_STATE_TYPE, "oldAnimation");
    private static final Field ANIMATION = field(SWING_STATE_TYPE, "animation");

    private static Field field(Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static void copySwing(LivingEntity to, LivingEntity from) {
        if (SWING_STATE == null || CURRENT_SWING == null || TICKS == null
                || OLD_ANIMATION == null || ANIMATION == null) {
            return;
        }
        try {
            Object dst = SWING_STATE.get(to);
            Object src = SWING_STATE.get(from);
            CURRENT_SWING.set(dst, CURRENT_SWING.get(src));
            TICKS.setInt(dst, TICKS.getInt(src));
            OLD_ANIMATION.setFloat(dst, OLD_ANIMATION.getFloat(src));
            ANIMATION.setFloat(dst, ANIMATION.getFloat(src));
        } catch (ReflectiveOperationException e) {
            // Unreachable with the fields resolved above; a render never fails over a swing.
        }
    }

    public static void clearSwing(ArmedEntityRenderState armed) {
        armed.swingAnimation = 0.0f;
        armed.currentSwing = null;
    }
}
