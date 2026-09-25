package com.deds.morph.client;

import com.deds.api.Deds;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * First-person hand override — recreation of the original
 * HandRenderHandler/RenderPlayerHand behavior ({@code handRenderOverride=1}
 * default): while morphed, the empty-hand first-person arm is the MORPH's
 * "assumed arm" instead of the player arm.
 *
 * <p>Original rules recreated: steady-state morphed shows the morph's arm
 * part with the morph's REAL texture; during a transformation, ticks 0-10
 * show the previous form's arm plus a morphskin overlay fading in, 10-70
 * show an interim arm — the two arms' boxes index-paired and lerped with
 * the CLAMPED mag {@code ((t-10)/50)^2}, integer-rounded sizes — in pure
 * morphskin, and 70-80 the next form's arm with the overlay fading out.
 * Whatever arm renders is seated in the vanilla hand slot: pivot = the
 * player arm's bind pivot with {@code y += 12 - armBoxHeight} (the
 * original's height normalization). The "assumed arm" is picked by part
 * NAME (the modern equivalent of the original's per-model field
 * heuristics): {@code right_arm}, else {@code right_front_leg}, else any
 * right arm/hand name, else a first tentacle; an armless morph renders NO
 * hand at all — steady and edge phases alike — and its interim side is a
 * zero-width placeholder box, exactly like the original's.</p>
 *
 * <p>Held items stay vanilla (the original also replaced only the
 * empty-hand arm render). Both hands are overridden with the same assumed
 * arm — the original predates off-hand rendering (documented deviation).</p>
 */
public final class MorphHands {

    /** The interim/steady hand canvases, one per hand so a frame with two
     *  empty hands does not fight over one part's mutable state. */
    private static final HandCanvas RIGHT = new HandCanvas();
    private static final HandCanvas LEFT = new HandCanvas();

    /** Placeholder "arm" for armless forms: a zero-width box, invisible
     *  but lerpable — the original's stand-in had the same shape. */
    private static final float[][] ARMLESS_BOXES =
            {{-3.0f, -2.0f, -2.0f, 0.0f, 12.0f, 0.0f, 0.0f, 0.0f, 0.0f}};

    /**
     * A resolved morph arm. {@code boxes} = its box specs for the interim
     * lerp; {@code cubes} = the ACTUAL baked cubes (real UVs) rendered for
     * the real-texture passes; {@code cubes == null} marks an armless form
     * (no first-person hand). {@code armBoxHeight} caches the real arm's
     * box height for seating.
     */
    private record HandForm(float[][] boxes, List<ModelPart.Cube> cubes,
            Identifier texture, int armBoxHeight) {

        boolean armless() {
            return cubes == null;
        }
    }

    /** Assumed-arm data per entity type; empty = broken/non-living form. */
    /** Keyed by the dummy itself (one per worn variant, rebuilt on change):
     *  keyed by type, the first cow's texture and whichever baby/adult model
     *  rendered last were frozen for every cow for the whole session. */
    private static final Map<LivingEntity, Optional<HandForm>> FORMS =
            new java.util.WeakHashMap<>();

    private static final class HandCanvas {
        final List<ModelPart.Cube> cubes = new ArrayList<>();
        final ModelPart part = new ModelPart(cubes, Map.of());
        float[] built;
    }

    /**
     * How many times {@link #submitHand} has been entered, ever. Read by the
     * client render test (reflectively: it lives in another package) to prove
     * the first-person hand hook is really wired: the hand mixin sits in a
     * per-loader mixin config, and a config the loader never lists fails
     * silently, leaving the vanilla player arm with no error anywhere.
     * Nothing in the mod reads it. Render thread only, like every caller.
     * Kept beside {@link #submitHandReplaced}: calls without replacements
     * mean the hook runs but falls back to the vanilla arm; no calls at all
     * mean the hook is not applied.
     */
    static int submitHandCalls;

    /**
     * How many of those calls actually REPLACED the vanilla arm (cancelled
     * it and submitted the morph's own), ever. Every fallback path (not
     * morphed, a form with no resolvable arm, a thrown exception) leaves it
     * alone, so a test can tell "the morph arm rendered" apart from "the hook
     * was entered and the vanilla arm rendered anyway". Read reflectively by
     * both loaders' client tests; nothing in the mod reads it. Render thread
     * only.
     */
    static int submitHandReplaced;

    private MorphHands() {
    }

    /**
     * Called from AvatarRendererMixin at the HEAD of renderRightHand /
     * renderLeftHand; cancels the vanilla arm when a morph arm replaces it.
     */
    public static void submitHand(AvatarRenderer<?> renderer,
            PoseStack poseStack, SubmitNodeCollector collector, int light,
            boolean rightHand, CallbackInfo ci) {
        submitHandCalls++;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (!(minecraft.player instanceof AbstractClientPlayer player)) {
                return;
            }
            float partialTick = minecraft.getDeltaTracker()
                    .getGameTimeDeltaPartialTick(true);
            MorphDummies.HandPlan plan =
                    MorphDummies.handPlan(player, partialTick);
            if (plan == null) {
                return; // unmorphed: vanilla hand
            }
            HandCanvas canvas = rightHand ? RIGHT : LEFT;
            switch (plan.phase()) {
                case STEADY -> steadyOrEdge(renderer, poseStack, collector,
                        light, rightHand, canvas, plan.nextForm(), 0.0f, ci);
                case OVERLAY_IN -> steadyOrEdge(renderer, poseStack,
                        collector, light, rightHand, canvas, plan.prevForm(),
                        plan.overlayAlpha(), ci);
                case OVERLAY_OUT -> steadyOrEdge(renderer, poseStack,
                        collector, light, rightHand, canvas, plan.nextForm(),
                        plan.overlayAlpha(), ci);
                case INTERIM -> interim(renderer, poseStack, collector,
                        light, rightHand, canvas, plan, ci);
            }
            // Last statement of the try: a phase that threw after cancelling
            // (and so drew nothing) does not count either.
            if (ci.isCancelled()) {
                submitHandReplaced++;
            }
        } catch (Exception e) {
            // Never let a broken morph model break the first-person hand;
            // fall through to the vanilla arm.
            Deds.LOGGER.warn("[deds_morph] first-person morph hand failed;"
                    + " showing the vanilla hand", e);
        }
    }

    /**
     * Steady state and both overlay edges: {@code form == null} means the
     * phase shows the player's OWN arm — vanilla renders it (no cancel)
     * and only the morphskin overlay is added on top.
     */
    private static void steadyOrEdge(AvatarRenderer<?> renderer,
            PoseStack poseStack, SubmitNodeCollector collector, int light,
            boolean rightHand, HandCanvas canvas, LivingEntity form,
            float overlayAlpha, CallbackInfo ci) {
        if (form == null) {
            // Own player arm; overlay rides the vanilla submit's part —
            // both submits of the same part draw with the same final pose.
            if (overlayAlpha > 0.0f) {
                ModelPart arm = playerArm(renderer, rightHand);
                McCompat.submitPartTinted(collector.order(1), arm, poseStack,
                        RenderTypes.entityTranslucent(MorphModels.MORPHSKIN),
                        light, OverlayTexture.NO_OVERLAY,
                        ARGB.white(overlayAlpha));
            }
            return;
        }
        HandForm handForm = form(form);
        if (handForm == null) {
            return; // unresolvable form: vanilla hand
        }
        ci.cancel();
        if (handForm.armless()) {
            return; // armless morph: no first-person hand (original)
        }
        // Fix A (2026-07-22 playtest): render the arm's ACTUAL baked cubes
        // (real UVs) — makeCube regenerates UVs from (0,0), which on a mob
        // texture is usually transparent, so the real-texture pass drew
        // nothing (arm vanished into steady / edge phases).
        setRealCubes(canvas, handForm.cubes());
        seatPose(renderer, rightHand, canvas, handForm.armBoxHeight());
        collector.submitModelPart(canvas.part, poseStack,
                RenderTypes.entityTranslucent(handForm.texture()), light,
                OverlayTexture.NO_OVERLAY, null);
        if (overlayAlpha > 0.0f) {
            // Overlay rides the same real cubes → morphskin samples at the
            // real UV density, matching the third-person body overlay.
            McCompat.submitPartTinted(collector.order(1), canvas.part, poseStack,
                    RenderTypes.entityTranslucent(MorphModels.MORPHSKIN),
                    light, OverlayTexture.NO_OVERLAY,
                    ARGB.white(overlayAlpha));
        }
    }

    /**
     * Ticks 10-70: the interim arm in pure morphskin. Boxes index-pair
     * over the two arms with the arm variant's CLAMPED mag
     * {@code ((t-10)/50)^2}; positions lerp continuously, sizes lerp and
     * round to integers (the original's arm morph).
     */
    private static void interim(AvatarRenderer<?> renderer,
            PoseStack poseStack, SubmitNodeCollector collector, int light,
            boolean rightHand, HandCanvas canvas, MorphDummies.HandPlan plan,
            CallbackInfo ci) {
        float[][] prev = armBoxes(renderer, rightHand, plan.prevForm());
        float[][] next = armBoxes(renderer, rightHand, plan.nextForm());
        if (prev == null || next == null) {
            return; // unresolvable form: vanilla hand
        }
        float mag = (plan.progress() - 10.0f) / 50.0f;
        mag = Math.min(mag * mag, 1.0f);
        int slots = Math.max(prev.length, next.length);
        float[] data = new float[slots * MorphModels.BOX_SPEC];
        for (int s = 0; s < slots; s++) {
            float[] from = s < prev.length
                    ? prev[s] : new float[MorphModels.BOX_SPEC];
            float[] to = s < next.length
                    ? next[s] : new float[MorphModels.BOX_SPEC];
            int o = s * MorphModels.BOX_SPEC;
            for (int axis = 0; axis < 3; axis++) {
                data[o + axis] = lerp(from[axis], to[axis], mag);
                data[o + 3 + axis] = Math.round(
                        lerp(from[3 + axis], to[3 + axis], mag));
                data[o + 6 + axis] = lerp(from[6 + axis], to[6 + axis], mag);
            }
        }
        ci.cancel();
        // Interim arm is morphskin only → rebuilt cubes (Fix B density).
        int slotsHeight = interimBoxHeight(data, slots);
        rebuild(canvas, data);
        seatPose(renderer, rightHand, canvas, slotsHeight);
        collector.submitModelPart(canvas.part, poseStack,
                RenderTypes.entityTranslucent(MorphModels.MORPHSKIN), light,
                OverlayTexture.NO_OVERLAY, null);
    }

    /** Tallest interim box height (integer), for the seat normalization. */
    private static int interimBoxHeight(float[] data, int slots) {
        int height = 0;
        for (int s = 0; s < slots; s++) {
            height = Math.max(height,
                    (int) Math.abs(data[s * MorphModels.BOX_SPEC + 4]));
        }
        return height;
    }

    /**
     * Seats the canvas in the vanilla hand slot: player arm bind pivot,
     * {@code y += 12 - armHeight} (the original's normalization so shorter
     * arms do not float), vanilla's slight z-tilt. Cubes are set separately
     * ({@link #setRealCubes} for real-texture passes, {@link #rebuild} for
     * the morphskin interim).
     */
    private static void seatPose(AvatarRenderer<?> renderer,
            boolean rightHand, HandCanvas canvas, int armBoxHeight) {
        PartPose slot = playerArm(renderer, rightHand).getInitialPose();
        canvas.part.x = slot.x();
        canvas.part.z = slot.z();
        canvas.part.y = slot.y() + (12 - armBoxHeight);
        canvas.part.xRot = 0.0f;
        canvas.part.yRot = 0.0f;
        canvas.part.zRot = rightHand ? 0.1f : -0.1f;
        canvas.part.xScale = 1.0f;
        canvas.part.yScale = 1.0f;
        canvas.part.zScale = 1.0f;
        canvas.part.visible = true;
    }

    /**
     * Puts a form's REAL (immutable, real-UV) cubes into the canvas for a
     * real-texture pass; invalidates the interim rebuild cache so a later
     * morphskin frame rebuilds fresh.
     */
    private static void setRealCubes(HandCanvas canvas,
            List<ModelPart.Cube> cubes) {
        canvas.built = null;
        canvas.cubes.clear();
        canvas.cubes.addAll(cubes);
    }

    private static void rebuild(HandCanvas canvas, float[] data) {
        if (java.util.Arrays.equals(canvas.built, data)) {
            return;
        }
        canvas.built = data;
        canvas.cubes.clear();
        for (int o = 0; o + MorphModels.BOX_SPEC <= data.length;
                o += MorphModels.BOX_SPEC) {
            if (data[o + 3] <= 0.0f && data[o + 4] <= 0.0f
                    && data[o + 5] <= 0.0f) {
                continue;
            }
            canvas.cubes.add(MorphModels.makeCube(0, 0, data[o],
                    data[o + 1], data[o + 2], data[o + 3], data[o + 4],
                    data[o + 5], data[o + 6], data[o + 7], data[o + 8]));
        }
    }

    /** A form's arm boxes for the interim lerp (null form = player arm). */
    private static float[][] armBoxes(AvatarRenderer<?> renderer,
            boolean rightHand, LivingEntity form) {
        if (form == null) {
            return MorphModels.boxSpecs(playerArm(renderer, rightHand));
        }
        HandForm handForm = form(form);
        return handForm == null ? null : handForm.boxes();
    }

    private static ModelPart playerArm(AvatarRenderer<?> renderer,
            boolean rightHand) {
        return rightHand ? renderer.getModel().rightArm
                : renderer.getModel().leftArm;
    }

    /** Resolves (and caches) a morph form's assumed arm + real texture. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static HandForm form(LivingEntity form) {
        if (form instanceof AbstractClientPlayer playerDummy) {
            return playerForm(playerDummy); // NEVER via FORMS (§1.4 major fix)
        }
        return FORMS.computeIfAbsent(form, key -> {
            try {
                EntityRenderDispatcher dispatcher = Minecraft.getInstance()
                        .getEntityRenderDispatcher();
                EntityRenderState state =
                        MorphDummies.extractRaw(dispatcher, form, 1.0f);
                if (!(dispatcher.getRenderer(form)
                        instanceof LivingEntityRenderer renderer)
                        || !(state instanceof LivingEntityRenderState living)) {
                    return Optional.empty();
                }
                Identifier texture = renderer.getTextureLocation(living);
                Model<?> model = MorphModels.modelForState(renderer, living);
                ModelPart arm = findAssumedArm(model.root());
                if (arm == null) {
                    // Armless morph: no first-person hand, but keep the
                    // placeholder boxes for the interim lerp against it.
                    return Optional.of(new HandForm(ARMLESS_BOXES, null,
                            texture, 0));
                }
                float[][] boxes = MorphModels.boxSpecs(arm);
                return Optional.of(new HandForm(boxes,
                        MorphModels.cubesOf(arm), texture,
                        MorphModels.maxBoxHeight(boxes)));
            } catch (Exception e) {
                Deds.LOGGER.warn("[deds_morph] cannot resolve a morph arm"
                        + " for {}", key.getType(), e);
                return Optional.empty();
            }
        }).orElse(null);
    }

    /**
     * A PLAYER morph's assumed arm, resolved FRESH each frame (never via
     * {@link #FORMS}) — wave 4 §1.4 first-person-hand fix. Every player dummy
     * shares {@code getType()==minecraft:player}, so an EntityType-keyed cache
     * would freeze ONE skin+arm for ALL player morphs (a second player showing
     * the first's skin; a pre-download default skin caching permanently). Instead
     * re-read THIS dummy's resolved skin ({@code getSkin().body().texturePath()})
     * and its slim/wide {@code AvatarRenderer} arm each frame — self-correcting for
     * the async skin download (and re-picks slim/wide once the real skin lands).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static HandForm playerForm(AbstractClientPlayer playerDummy) {
        try {
            EntityRenderDispatcher dispatcher =
                    Minecraft.getInstance().getEntityRenderDispatcher();
            if (!(dispatcher.getRenderer(playerDummy)
                    instanceof LivingEntityRenderer renderer)) {
                return null;
            }
            Identifier texture = playerDummy.getSkin().body().texturePath();
            ModelPart arm = findAssumedArm(((Model<?>) renderer.getModel()).root());
            if (arm == null) {
                return null; // no arm (should not happen for a humanoid player)
            }
            float[][] boxes = MorphModels.boxSpecs(arm);
            return new HandForm(boxes, MorphModels.cubesOf(arm), texture,
                    MorphModels.maxBoxHeight(boxes));
        } catch (Exception e) {
            Deds.LOGGER.warn("[deds_morph] cannot resolve a player morph arm", e);
            return null;
        }
    }

    /**
     * The original's per-model arm heuristics, translated to modern part
     * names: right arm, else right front leg (quadrupeds, creeper, spider,
     * wolf, cats, horses), else anything named like a right arm/hand, else
     * the first tentacle (squid). Null = armless.
     */
    private static ModelPart findAssumedArm(ModelPart root) {
        ModelPart byName = findByName(root, "right_arm");
        if (byName == null) {
            byName = findByName(root, "right_front_leg");
        }
        if (byName == null) {
            byName = findMatching(root, name -> name.contains("right")
                    && (name.contains("arm") || name.contains("hand")));
        }
        if (byName == null) {
            byName = findMatching(root, name -> name.contains("tentacle"));
        }
        return byName;
    }

    private static ModelPart findByName(ModelPart part, String name) {
        return findMatching(part, candidate -> candidate.equals(name));
    }

    private static ModelPart findMatching(ModelPart part,
            java.util.function.Predicate<String> match) {
        for (Map.Entry<String, ModelPart> entry
                : MorphModels.childMapOf(part).entrySet()) {
            if (match.test(entry.getKey())) {
                return entry.getValue();
            }
        }
        for (ModelPart child : MorphModels.childMapOf(part).values()) {
            ModelPart found = findMatching(child, match);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static float lerp(float from, float to, float mag) {
        return from + (to - from) * mag;
    }
}
