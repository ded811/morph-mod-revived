package com.deds.morph.fabric.client;

import com.deds.api.Deds;
import com.deds.morph.AcquireFx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * The acquisition "suck-in" effect — client-local recreation of the
 * original's 40-tick {@code EntityMorphAcquisition} (Morph 0.7.1): the
 * killed mob's model, frozen in its final pose and drawn entirely in
 * morphskin, bursts apart while every part shrinks to nothing, and the
 * whole cluster flies from the corpse into the killer over the first 20
 * ticks, then clings to the killer until it silently vanishes at 40. No
 * particles, no fade, no sound of its own, no shadow, no nametag.
 *
 * <p>Recreated exactly: the per-part scatter (for part index i, reseed
 * {@code java.util.Random(i*1000L)} each frame; rotate by
 * {@code 560*rand.nextFloat()*p} degrees about a random ± axis, then
 * translate {@code rand.nextDouble()*p*0.3} blocks per axis, with the
 * transforms ACCUMULATING across parts — no reset between them, which is
 * what makes later parts fling further), {@code p = ((t)/50)^2}; position
 * lerp corpse→killer with {@code (t/20)^2} clamped (killer position live);
 * whole-model scale lerp victim→killer with the same p. Approximated: the
 * original re-added every ModelBox at {@code (1-((t)/40)^2)} of its size —
 * 26.2 cubes are immutable, so each ROOT part scales toward its pivot by
 * that factor instead (children collapse onto the root pivot rather than
 * their own box corners; integer size-stepping is smoothed).</p>
 *
 * <p>Effects are plain objects, never registered entities: ticked from
 * {@code MorphClient}'s END_CLIENT_TICK and drawn during the level
 * renderer's submit-collection pass (Fabric
 * {@code LevelRenderEvents.COLLECT_SUBMITS}) as a world-space pass — the
 * effect stays visible whether or not the killer's own model is (the
 * original was a world entity too; killer-anchored submits would vanish in
 * first person).</p>
 */
@Environment(EnvType.CLIENT)
public final class MorphAcquisitions {

    private static final List<Effect> EFFECTS = new ArrayList<>();

    private MorphAcquisitions() {
    }

    private static final class Effect {
        final ClientLevel level;
        final LivingEntity dummy;
        final List<ModelPart> parts;
        final float[] baseScales; // frozen pose scale per part, xyz stride 3
        /** Largest box dimension (px) in each part's subtree, for the integer
         *  wink-out: the original re-added boxes at integer-truncated sizes, so
         *  a part BLANKED once its largest box rounded below 1 px (t≈36.5-39.4)
         *  — not a sub-pixel speck drifting at max scatter until exactly 40. */
        final float[] maxBoxDimPx;
        final UUID killer;
        final Vec3 victimPos;
        final float bodyYaw;
        final float victimScale;
        /** The victim renderer's scale() hook (slime size etc.), per axis —
         *  so a large slime bursts at the size you actually killed. */
        final Vector3f victimHookScale;
        int progress;
        Vec3 pos;
        Vec3 prevPos;
        Vec3 lastKillerPos;

        Effect(ClientLevel level, LivingEntity dummy, ModelPart root,
                UUID killer, Vec3 victimPos, float bodyYaw,
                float victimScale, Vector3f victimHookScale) {
            this.level = level;
            this.dummy = dummy;
            this.parts = MorphModels.childrenOf(root);
            // The original's ModelMorphAcquisition:30-36 root-pivot shift: every
            // ROOT cube's rotationPointY -= 8 (px). Paired with the chain's
            // -1.0078125 lift (see submitEffect) this puts the tumble/shrink
            // origin at the killer's MID-BODY (feet+1.0078) instead of above the
            // head — the pair CANCELS at p=0, so the rest pose is unchanged (to
            // the known 0.0068-block -1.501-vs-1.5078125 constant), but the
            // accumulated per-part scatter rotations now tumble parts in place
            // around the chest instead of swinging them on wide arcs up/outward.
            // Private copied tree; children untouched; baseScales unaffected.
            for (ModelPart part : parts) {
                part.y -= 8.0f;
            }
            this.baseScales = new float[parts.size() * 3];
            this.maxBoxDimPx = new float[parts.size()];
            for (int i = 0; i < parts.size(); i++) {
                ModelPart part = parts.get(i);
                baseScales[i * 3] = part.xScale;
                baseScales[i * 3 + 1] = part.yScale;
                baseScales[i * 3 + 2] = part.zScale;
                maxBoxDimPx[i] = maxBoxDim(part);
            }
            this.killer = killer;
            this.victimPos = victimPos;
            this.bodyYaw = bodyYaw;
            this.victimScale = victimScale;
            this.victimHookScale = victimHookScale;
            this.pos = victimPos;
            this.prevPos = victimPos;
            this.lastKillerPos = victimPos;
        }
    }

    /** S2C handler ({@code deds_morph:acquire_fx}), client main thread. */
    public static void begin(AcquireFx fx) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        try {
            LivingEntity dummy =
                    MorphDummies.createDummyEntity(fx.victimVariant(), level);
            if (dummy == null) {
                return; // unknown/broken type: skip the effect silently
            }
            // Freeze the victim's stance: position + body yaw from the
            // packet, then one forced extraction+pose (the original
            // force-rendered the dying mob once offscreen and kept that
            // pose for the effect's whole life).
            dummy.setPos(fx.victimPos());
            dummy.xo = fx.victimPos().x;
            dummy.yo = fx.victimPos().y;
            dummy.zo = fx.victimPos().z;
            dummy.xOld = fx.victimPos().x;
            dummy.yOld = fx.victimPos().y;
            dummy.zOld = fx.victimPos().z;
            dummy.setYRot(fx.victimBodyYaw());
            dummy.yRotO = fx.victimBodyYaw();
            dummy.yBodyRot = fx.victimBodyYaw();
            dummy.yBodyRotO = fx.victimBodyYaw();
            dummy.yHeadRot = fx.victimBodyYaw();
            dummy.yHeadRotO = fx.victimBodyYaw();

            EntityRenderDispatcher dispatcher =
                    minecraft.getEntityRenderDispatcher();
            EntityRenderState state = dispatcher.extractEntity(dummy, 1.0f);
            if (!(dispatcher.getRenderer(dummy)
                            instanceof LivingEntityRenderer renderer)
                    || !(state instanceof LivingEntityRenderState living)) {
                return;
            }
            // Correct baby/adult tree for the victim (not the racy shared
            // getModel()), and its real render scale (slime size etc.).
            @SuppressWarnings({"unchecked", "rawtypes"})
            Model<EntityRenderState> model =
                    (Model) MorphModels.modelForState(renderer, living);
            model.setupAnim(state);
            ModelPart root = MorphModels.copyPosed(model.root());
            Vector3f victimHookScale =
                    MorphModels.measureHookScale(renderer, living);
            EFFECTS.add(new Effect(level, dummy, root, fx.killer(),
                    fx.victimPos(), fx.victimBodyYaw(), living.scale,
                    victimHookScale));
        } catch (Exception e) {
            // Display-only: a broken model must never crash the client.
            Deds.LOGGER.warn("[deds_morph] could not start acquisition"
                    + " effect for {}", fx.victimVariant(), e);
        }
    }

    /** Once per client tick (original EntityMorphAcquisition.onUpdate). */
    public static void clientTick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            EFFECTS.clear();
            return;
        }
        // SP pause freeze (wave 6 item D): keep the 40-tick suck-in effect from
        // advancing on the Esc screen — same gate as MorphDummies.clientTick.
        if (minecraft.isPaused()) {
            return;
        }
        for (Iterator<Effect> it = EFFECTS.iterator(); it.hasNext(); ) {
            Effect effect = it.next();
            if (effect.level != level || ++effect.progress > 40) {
                it.remove(); // lifetime exactly 40 ticks, silent end
                continue;
            }
            effect.prevPos = effect.pos;
            Player killer = level.getPlayerByUUID(effect.killer);
            if (killer != null) {
                effect.lastKillerPos = killer.position();
            }
            // Quadratic ease-in flight, done at tick 20; the victim is
            // gone so its coords are frozen, the killer's are live —
            // afterwards the cluster rides the killer until tick 40.
            float prog = Math.min(effect.progress / 20.0f, 1.0f);
            prog *= prog;
            effect.pos = effect.victimPos.lerp(effect.lastKillerPos, prog);
            // Light is sampled at the effect's current position.
            effect.dummy.setPos(effect.pos);
        }
    }

    /** World-space submit pass (LevelRenderEvents.COLLECT_SUBMITS). */
    public static void submit(LevelRenderContext context) {
        if (EFFECTS.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        float partialTick = minecraft.getDeltaTracker()
                .getGameTimeDeltaPartialTick(true);
        Vec3 cameraPos = context.levelState().cameraRenderState.pos;
        PoseStack poseStack = context.poseStack();
        SubmitNodeCollector collector = context.submitNodeCollector();
        EntityRenderDispatcher dispatcher =
                minecraft.getEntityRenderDispatcher();
        Random random = new Random();
        for (Effect effect : EFFECTS) {
            try {
                submitEffect(effect, partialTick, cameraPos, poseStack,
                        collector, dispatcher, random);
            } catch (Exception e) {
                Deds.LOGGER.warn("[deds_morph] acquisition effect render"
                        + " failed; dropping it", e);
                effect.progress = 41; // culled next tick
            }
        }
    }

    private static void submitEffect(Effect effect, float partialTick,
            Vec3 cameraPos, PoseStack poseStack,
            SubmitNodeCollector collector, EntityRenderDispatcher dispatcher,
            Random random) {
        float ft = effect.progress + partialTick;
        // Same curves as the original model: scatter/scale p = (t/50)^2
        // (max ~0.67 at death), box shrink (t/40)^2 → gone exactly at 40.
        float p = (ft / 50.0f) * (ft / 50.0f);
        float shrink = 1.0f - Math.clamp((ft / 40.0f) * (ft / 40.0f),
                0.0f, 1.0f);
        Vec3 renderPos = effect.prevPos.lerp(effect.pos, partialTick);
        int light = dispatcher.getPackedLightCoords(effect.dummy,
                partialTick);

        poseStack.pushPose();
        poseStack.translate(renderPos.x - cameraPos.x,
                renderPos.y - cameraPos.y, renderPos.z - cameraPos.z);
        // Vanilla living orientation with bodyRot 0 (the effect entity's
        // own yaw was 0), then the victim's frozen body yaw — the
        // original's model applied it inside the flipped model space.
        McCompat.rotate(poseStack, Axis.YP.rotationDegrees(180.0f));
        poseStack.scale(-1.0f, -1.0f, 1.0f);
        // -1.0078125, NOT the generic -1.501 (wave 7, acquisition suck-in): the
        // original's tumble/shrink origin sits at the killer's MID-BODY
        // (feet+1.0078) via a deliberate PAIR — effect-entity yOffset -0.5 plus
        // every root cube's rotationPointY -= 8 (see the Effect ctor). The pair
        // cancels at p=0 (rest pose bit-parity to the original's fudge), but the
        // accumulated per-part rotations now tumble parts around the chest
        // instead of swinging them on wide arcs from above the head.
        poseStack.translate(0.0f, -1.0078125f, 0.0f);
        McCompat.rotate(poseStack, Axis.YP.rotationDegrees(effect.bodyYaw));
        // Whole-model per-axis scale lerp victim→killer with the same p
        // (the original measured each renderer's scale and lerped). The
        // victim's FULL render scale = state.scale * its scale() hook
        // (slime size etc.); the killer is a player, whose hook is the
        // identity, so its full scale is just getScale(). Never reaches the
        // killer's within the effect's life (p maxes ~0.64).
        Player killer = effect.level.getPlayerByUUID(effect.killer);
        // ×0.9375: the 1.6.4 RenderPlayer preRenderCallback scale the original
        // MEASURED as the killer's endpoint (wave 7, acquisition suck-in item 2).
        float killerScale = (killer != null ? killer.getScale() : 1.0f)
                * 0.9375f;
        float pc = Math.max(p, 0.0f);
        float wholeX = lerp(effect.victimScale * effect.victimHookScale.x(),
                killerScale, pc);
        float wholeY = lerp(effect.victimScale * effect.victimHookScale.y(),
                killerScale, pc);
        float wholeZ = lerp(effect.victimScale * effect.victimHookScale.z(),
                killerScale, pc);
        // The original's ModelMorphAcquisition:83-85 yOffset compensation: as the
        // whole-model scale departs from 1, re-seat the cluster so it shrinks
        // about the eye line rather than sliding down with the scale.
        poseStack.translate(0.0f, (1.0f - wholeY) * 1.62f, 0.0f);
        poseStack.scale(wholeX, wholeY, wholeZ);

        for (int i = 0; i < effect.parts.size(); i++) {
            // EXACT original scatter: reseed per part, consume the random
            // stream in the original's evaluation order, and DO NOT
            // push/pop between parts — the transforms accumulate, so each
            // later part inherits every earlier part's rotate+translate.
            random.setSeed(i * 1000L);
            float angle = 560.0f * random.nextFloat() * p;
            float ax = random.nextFloat()
                    * (random.nextFloat() > 0.5f ? -1.0f : 1.0f) * p;
            float ay = random.nextFloat()
                    * (random.nextFloat() > 0.5f ? -1.0f : 1.0f) * p;
            float az = random.nextFloat()
                    * (random.nextFloat() > 0.5f ? -1.0f : 1.0f) * p;
            float lengthSq = ax * ax + ay * ay + az * az;
            if (angle != 0.0f && lengthSq > 1.0E-10f) {
                // glRotatef normalized the axis; JOML needs it explicit.
                Vector3f axis = new Vector3f(ax, ay, az).normalize();
                McCompat.rotate(poseStack, new Quaternionf().rotationAxis(
                        angle * ((float) Math.PI / 180.0f), axis));
            }
            poseStack.translate(random.nextDouble() * p * 0.3,
                    random.nextDouble() * p * 0.3,
                    random.nextDouble() * p * 0.3);

            // Integer wink-out (wave 7, acquisition suck-in item 3): the original
            // re-added every box at integer-truncated sizes, so a part BLANKED
            // once its largest box rounded below 1 px (t≈36.5-39.4); our smooth
            // scale left sub-pixel specks drifting at max scatter until exactly
            // tick 40 — the "disappear in mid air" tail. Skips ONLY the submit:
            // the scatter rotate+translate above must still run so later parts
            // inherit identical accumulated transforms.
            if (effect.maxBoxDimPx[i] * shrink < 0.5f) {
                continue;
            }

            ModelPart part = effect.parts.get(i);
            part.xScale = effect.baseScales[i * 3] * shrink;
            part.yScale = effect.baseScales[i * 3 + 1] * shrink;
            part.zScale = effect.baseScales[i * 3 + 2] * shrink;
            collector.submitModelPart(part, poseStack,
                    RenderTypes.entityCutout(MorphModels.MORPHSKIN), light,
                    OverlayTexture.NO_OVERLAY, null);
        }
        poseStack.popPose();
    }

    private static float lerp(float from, float to, float mag) {
        return from + (to - from) * mag;
    }

    /** Largest box dimension (px) anywhere in {@code part}'s subtree — children
     *  included, since our shrink scales the whole root part (a root with no own
     *  cubes but a boxed child must not wink out early). */
    private static float maxBoxDim(ModelPart part) {
        float max = 0.0f;
        for (float[] spec : MorphModels.boxSpecs(part)) {
            max = Math.max(max,
                    Math.max(spec[3], Math.max(spec[4], spec[5])));
        }
        for (ModelPart child : MorphModels.childrenOf(part)) {
            max = Math.max(max, maxBoxDim(child));
        }
        return max;
    }
}
