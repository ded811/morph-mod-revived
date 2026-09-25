package com.deds.morph.fabric.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.ARGB;


/**
 * The transformation's custom submit passes (called from the dispatcher
 * mixin's submit hook; the pose stack is at the camera-relative origin
 * there, so both passes translate by the state's x/y/z themselves and then
 * replicate the vanilla living-entity orientation).
 */
@Environment(EnvType.CLIENT)
public final class MorphRenderPasses {

    private MorphRenderPasses() {
    }

    /**
     * Ticks 0-10 / 70-80: one extra submit of the form's OWN shared model
     * over the vanilla render, in translucent morphskin with the given
     * alpha (the original's second blended render with the texture swapped
     * to morphskin). setupAnim runs at draw with the same state, so the
     * overlay lands exactly on the base model's pose.
     *
     * <p>Runs the renderer's own {@code scale()} hook (slime size, etc.)
     * and picks the correct baby/adult model for the state, so the overlay
     * lands exactly on the base render even for variant-scaled forms.
     * Special non-standing poses (sleeping) still get a slightly mis-fitted
     * overlay — those setupRotations are not replicated here.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void submitMorphskinOverlay(EntityRenderDispatcher
            dispatcher, EntityRenderState state, double x, double y,
            double z, PoseStack poseStack, SubmitNodeCollector collector,
            float alpha) {
        if (!(state instanceof LivingEntityRenderState living)
                || alpha <= 0.0f) {
            return;
        }
        if (!(dispatcher.getRenderer(state)
                instanceof LivingEntityRenderer renderer)) {
            return;
        }
        Model model = (Model) MorphModels.modelForState(renderer, living);
        // Wave-7 review fix 1 (supersedes the spec's "overlay keeps the generic
        // transform except squid" clause — a design gap): the morphskin overlay
        // is fully OPAQUE at exactly ticks 10 and 70, so any renderer whose
        // setupRotations / scale-hook / renderOffset differs from the generic
        // chain popped its ghost off the base render right at the boundaries the
        // measured interim fixed — a phantom ghost 1.3125 blocks ABOVE the real
        // phantom, a land-cod ghost standing upright over the flopped base.
        // measure() is provably identical to the old generic path for standard
        // renderers (the humanoid identity invariant) and boundary-exact for the
        // rest; its try/catch identity fallback degrades any probe failure to
        // the old behavior. The renderOffset is added here because the x,y,z
        // this pass receives are the dispatcher submit's PRE-offset coords
        // (vanilla adds getRenderOffset inside submit — javap-verified).
        MorphModels.RendererTransform transform =
                MorphModels.measure(renderer, living);
        poseStack.pushPose();
        poseStack.translate(x + transform.renderOffset().x,
                y + transform.renderOffset().y, z + transform.renderOffset().z);
        MorphModels.applyMeasuredTransform(poseStack, living.scale, transform);
        // order(1): draw the overlay after the base model it tints.
        McCompat.submitModel(collector.order(1), model, living, poseStack,
                RenderTypes.entityTranslucent(MorphModels.MORPHSKIN),
                living.lightCoords,
                LivingEntityRenderer.getOverlayCoords(living, 0.0f),
                ARGB.white(Math.min(alpha, 1.0f)), 0);
        poseStack.popPose();
    }

    /**
     * Ticks 10-70: the interim part-morph model in opaque morphskin
     * replaces the whole vanilla submit. No layers, no nametag (original
     * RenderMorph rendered neither); the shadow is re-submitted from the
     * state so the form keeps one (radius = the prev form's — the
     * original's prev→next shadow lerp is folded into this approximation).
     */
    public static void submitInterim(EntityRenderState state, double x,
            double y, double z, PoseStack poseStack,
            SubmitNodeCollector collector,
            MorphDummies.InterimPayload payload) {
        if (!(state instanceof LivingEntityRenderState living)) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        // Shadow stays at raw x,y,z (matches the dispatcher; dummy offsets ~0).
        if (!state.shadowPieces.isEmpty()) {
            collector.submitShadow(poseStack, state.shadowRadius,
                    state.shadowPieces);
        }
        // The MEASURED chain (wave 7 item 2): renderOffset in world space, then
        // entity scale → setupRotations [R|t] → flip → hook translation+scale →
        // -1.501 — bit-equivalent to the vanilla submit at mag=0 (prev renderer)
        // and mag=1 (next renderer), so no snap at either boundary (the phantom's
        // hook translate was previously discarded → "animates at eye height then
        // teleports down").
        MorphModels.RendererTransform transform = payload.transform();
        poseStack.translate(transform.renderOffset().x,
                transform.renderOffset().y, transform.renderOffset().z);
        MorphModels.applyMeasuredTransform(poseStack, payload.baseScale(),
                transform);
        int overlay = LivingEntityRenderer.getOverlayCoords(living, 0.0f);
        McCompat.submitModel(collector, payload.rig().model(), payload.frame(),
                poseStack, RenderTypes.entityCutout(MorphModels.MORPHSKIN),
                living.lightCoords, overlay, -1, 0);
        poseStack.popPose();
    }
}
