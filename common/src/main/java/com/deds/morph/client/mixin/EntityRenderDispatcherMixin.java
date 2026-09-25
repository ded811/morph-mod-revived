package com.deds.morph.client.mixin;

import com.deds.morph.client.MorphDummies;
import com.deds.morph.client.MorphRenderPasses;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The whole render-replacement surface of Morph (kept deliberately tiny;
 * all policy lives in {@link MorphDummies}, all custom submit passes in
 * {@link MorphRenderPasses}).
 *
 * <p>26.2 renders entities in two steps: {@code extractEntity} builds an
 * {@link EntityRenderState}, {@code submit} draws it. For a morphed player
 * we swap the extraction to the morph's dummy mob (posed from the player),
 * so culling, lighting, shadow and nametag handling all run unchanged on
 * the morph's state. During the 80-tick transformation the extraction
 * additionally stashes a per-frame payload which this mixin's submit hook
 * turns into the original's three phases: a morphskin overlay pass on top
 * of the normal render (ticks 0-10 and 70-80), or a full replacement by
 * the interim part-morph model (ticks 10-70).</p>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "extractEntity", at = @At("HEAD"), cancellable = true)
    private void deds_morph$extractMorphInstead(Entity entity,
            float partialTick,
            CallbackInfoReturnable<EntityRenderState> cir) {
        if (MorphDummies.isRawExtracting()
                || !(entity instanceof AbstractClientPlayer player)) {
            return;
        }
        EntityRenderDispatcher self = (EntityRenderDispatcher) (Object) this;
        try {
            // Inside the guard: building the plan poses the dummy (equipment,
            // carried block, sleeping pose), and a mob whose setters throw must
            // be quarantined, not take down the render thread.
            MorphDummies.Plan plan =
                    MorphDummies.renderPlan(player, partialTick);
            if (plan == null) {
                return; // unmorphed (or unrenderable morph): vanilla player
            }
            EntityRenderState primary =
                    MorphDummies.extractRaw(self, plan.primary(),
                            partialTick);
            // Patch every input the never-ticked dummy cannot carry itself:
            // aquatic swim state, riding pose, riptide spin, named-mob nametag
            // (partialTick feeds the nameTagAttachment's yaw resolve).
            MorphDummies.patchUntickedState(player, plan.primary(), primary,
                    partialTick);
            switch (plan.phase()) {
                case OVERLAY_IN, OVERLAY_OUT ->
                        MorphDummies.stashOverlay(primary,
                                plan.overlayAlpha());
                case INTERIM -> {
                    EntityRenderState aux = MorphDummies.extractRaw(self,
                            plan.aux(), partialTick);
                    MorphDummies.prepareInterim(player, plan, primary, aux,
                            self);
                }
                case STEADY -> {
                }
            }
            cir.setReturnValue(primary);
        } catch (Exception e) {
            // never let a broken morph renderer crash the client: quarantine
            // the morph type and fall through to vanilla player rendering
            MorphDummies.quarantine(player, e);
        }
    }

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void deds_morph$submitMorphPasses(EntityRenderState state,
            CameraRenderState camera, double x, double y, double z,
            PoseStack poseStack, SubmitNodeCollector collector,
            CallbackInfo ci) {
        Object payload = MorphDummies.takePayload(state);
        if (payload == null) {
            return;
        }
        EntityRenderDispatcher self = (EntityRenderDispatcher) (Object) this;
        if (payload instanceof MorphDummies.OverlayPayload overlay) {
            // Extra pass on top of the vanilla submit (which proceeds).
            MorphRenderPasses.submitMorphskinOverlay(self, state, x, y, z,
                    poseStack, collector, overlay.alpha());
        } else if (payload instanceof MorphDummies.InterimPayload interim) {
            // Ticks 10-70: the interim model REPLACES the vanilla render
            // (original: hard switch to ModelMorph via RenderMorph).
            MorphRenderPasses.submitInterim(state, x, y, z, poseStack,
                    collector, interim);
            ci.cancel();
        }
    }
}
