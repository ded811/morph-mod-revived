package com.deds.morph.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches the protected {@code scale(S, PoseStack)} hook every
 * {@link LivingEntityRenderer} applies between the model-space flip and the
 * -1.501 lift. This hook — NOT {@link LivingEntityRenderState#scale} — is
 * where per-mob size lives: {@code AbstractCubeMobRenderer.applySizeAndSquish}
 * scales by the slime/magma-cube size, {@code downscaleSlightly} nudges
 * cube mobs, etc. The morph transformation's interim model and its
 * morphskin overlay must run the same hook or they render default-sized and
 * then snap to the real size at tick 70 (variant-scale playtest bug,
 * 2026-07-22).
 *
 * <p>The invoker uses {@code invokevirtual}, so calling it on a concrete
 * renderer dispatches to that renderer's override (via the erased
 * {@code scale(LivingEntityRenderState, PoseStack)} bridge). The state
 * passed must be the renderer's own state subtype — always true here, since
 * we pass the state that same renderer extracted.</p>
 */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererInvoker {

    @Invoker("scale")
    void deds_morph$invokeScale(LivingEntityRenderState state,
            PoseStack poseStack);

    /**
     * Reaches the protected {@code setupRotations(S, PoseStack, float bodyRot,
     * float scale)} hook (javap-verified: vanilla {@code submit} calls it with
     * {@code (state, poseStack, state.bodyRot, state.scale)} right after the
     * entity-scale step). Same erased-bridge dispatch as the scale invoker —
     * calling on a concrete renderer runs that renderer's override (verified:
     * PhantomRenderer carries both the typed override and the
     * {@code LivingEntityRenderState} bridge). This is where per-mob render
     * orientation lives — the squid's pivoted tilt, the phantom's glide tilt —
     * and {@code MorphModels.measure} probes it to recover the full
     * {@code [R|t]} the interim/overlay passes must replicate (wave 7 item 2).
     */
    @Invoker("setupRotations")
    void deds_morph$invokeSetupRotations(LivingEntityRenderState state,
            PoseStack poseStack, float bodyRot, float scale);
}
