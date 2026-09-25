package com.deds.morph.client;

import com.deds.morph.Morph;
import com.deds.morph.client.mixin.AgeableMobRendererAccessor;
import com.deds.morph.client.mixin.LivingEntityRendererInvoker;
import com.deds.morph.client.mixin.ModelPartAccessor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ModelPart-tree plumbing shared by the transformation and acquisition
 * animations: private tree copies of a renderer's live model, box-geometry
 * extraction for the per-frame cube rebuilds, and the shared morphskin
 * render bits.
 *
 * <p>Copies share the immutable baked {@link ModelPart.Cube} lists with the
 * source tree and duplicate only the mutable pose state, so a copy can be
 * re-posed every frame without ever touching the renderer's shared instance
 * (26.2 poses shared trees at DRAW time — mutating them per submit is
 * unsafe). Children keep the source map's iteration order, which is the
 * deterministic part order every pairing loop uses.</p>
 */
final class MorphModels {

    /**
     * The original's morphskin.png (256x256 near-black noise), staged under
     * our namespace. Everything mid-morph renders in this texture.
     */
    static final Identifier MORPHSKIN = Identifier.fromNamespaceAndPath(
            Morph.MOD_ID, "textures/skin/morphskin.png");

    /** Stride of a box spec: min xyz, ungrown size xyz, grow xyz. */
    static final int BOX_SPEC = 9;

    /**
     * Texture size fed to rebuilt morphskin cubes. morphskin.png is 256x256
     * uniform noise, but a cube's normalized UV span is {@code faceSize /
     * texSize} — so the size given here (NOT the real image size) sets how
     * much noise a face samples. Vanilla mob/player models bake at ~64px,
     * so their morphskin OVERLAY passes ({@link MorphRenderPasses} and the
     * acquisition scatter, which draw REAL cubes) sample {@code faceSize/64}
     * of the noise. Feeding 256 here made rebuilt cubes sample
     * {@code faceSize/256} — 4x more zoomed-in, i.e. coarse/blocky noise
     * that visibly swapped resolution against the overlay passes (playtest
     * bug B, 2026-07-22). 64 matches the player and vanilla mobs; morphskin
     * is uniform, so only this SCALE matters, not the exact patch.
     */
    private static final float MORPHSKIN_TEX_SIZE = 64.0f;

    private static final Set<Direction> ALL_FACES =
            EnumSet.allOf(Direction.class);

    private MorphModels() {
    }

    /**
     * Deep-copies a model tree with its CURRENT pose (call after the owning
     * model's {@code setupAnim}). Cubes are shared, pose fields + visibility
     * + initial pose are copied per part.
     */
    static ModelPart copyPosed(ModelPart source) {
        ModelPartAccessor access = (ModelPartAccessor) (Object) source;
        Map<String, ModelPart> children = new LinkedHashMap<>();
        for (Map.Entry<String, ModelPart> entry
                : access.deds_morph$children().entrySet()) {
            children.put(entry.getKey(), copyPosed(entry.getValue()));
        }
        ModelPart copy = new ModelPart(access.deds_morph$cubes(), children);
        copy.setInitialPose(source.getInitialPose());
        copy.x = source.x;
        copy.y = source.y;
        copy.z = source.z;
        copy.xRot = source.xRot;
        copy.yRot = source.yRot;
        copy.zRot = source.zRot;
        copy.xScale = source.xScale;
        copy.yScale = source.yScale;
        copy.zScale = source.zScale;
        copy.visible = source.visible;
        copy.skipDraw = source.skipDraw;
        return copy;
    }

    /** Direct children in the tree's deterministic iteration order. */
    static List<ModelPart> childrenOf(ModelPart part) {
        return new ArrayList<>(childMapOf(part).values());
    }

    /** The part's children map (deterministic order), read-only use. */
    static Map<String, ModelPart> childMapOf(ModelPart part) {
        return ((ModelPartAccessor) (Object) part).deds_morph$children();
    }

    /**
     * The part's OWN baked cubes (immutable and shared — safe to reuse in
     * another tree, per this class's copy design note). These carry the
     * real baked UVs, so rendering them with a form's real texture maps
     * correctly — unlike {@link #makeCube}, which is morphskin-only.
     */
    static List<ModelPart.Cube> cubesOf(ModelPart part) {
        return ((ModelPartAccessor) (Object) part).deds_morph$cubes();
    }

    /**
     * The part's OWN boxes as specs of {@link #BOX_SPEC} floats each:
     * un-grown min corner + un-grown sizes (the baked cube's min/max
     * fields) plus the per-axis grow ("CubeDeformation") recovered from the
     * polygon vertex extents — a cube rebuilt from a spec renders the exact
     * same geometry as the baked source cube.
     */
    static float[][] boxSpecs(ModelPart part) {
        List<ModelPart.Cube> cubes =
                ((ModelPartAccessor) (Object) part).deds_morph$cubes();
        float[][] specs = new float[cubes.size()][];
        for (int i = 0; i < cubes.size(); i++) {
            ModelPart.Cube cube = cubes.get(i);
            float[] spec = new float[BOX_SPEC];
            spec[0] = cube.minX;
            spec[1] = cube.minY;
            spec[2] = cube.minZ;
            spec[3] = cube.maxX - cube.minX;
            spec[4] = cube.maxY - cube.minY;
            spec[5] = cube.maxZ - cube.minZ;
            float[] grown = vertexExtents(cube);
            if (grown != null) {
                spec[6] = Math.max(0.0f, (grown[0] - spec[3]) / 2.0f);
                spec[7] = Math.max(0.0f, (grown[1] - spec[4]) / 2.0f);
                spec[8] = Math.max(0.0f, (grown[2] - spec[5]) / 2.0f);
            }
            specs[i] = spec;
        }
        return specs;
    }

    /** Grown width/height/depth from polygon vertices, or null if none. */
    private static float[] vertexExtents(ModelPart.Cube cube) {
        boolean any = false;
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (ModelPart.Polygon polygon : cube.polygons) {
            for (ModelPart.Vertex vertex : polygon.vertices()) {
                any = true;
                minX = Math.min(minX, vertex.x());
                minY = Math.min(minY, vertex.y());
                minZ = Math.min(minZ, vertex.z());
                maxX = Math.max(maxX, vertex.x());
                maxY = Math.max(maxY, vertex.y());
                maxZ = Math.max(maxZ, vertex.z());
            }
        }
        return any
                ? new float[] {maxX - minX, maxY - minY, maxZ - minZ}
                : null;
    }

    /**
     * Builds one render-ready MORPHSKIN cube from spec values (all faces
     * visible). Only ever textured with morphskin (uniform noise): callers
     * pass {@code texU/texV} 0 — the patch offset is invisible on uniform
     * noise, and non-zero offsets would leave the {@link #MORPHSKIN_TEX_SIZE
     * 64px} UV range and risk edge-clamping. For a form's REAL texture,
     * reuse the source cubes' baked UVs ({@link #cubesOf}) instead — this
     * constructor regenerates UVs from {@code (texU,texV)} and cannot carry
     * a real texture's mapping.
     */
    static ModelPart.Cube makeCube(int texU, int texV, float minX,
            float minY, float minZ, float sizeX, float sizeY, float sizeZ,
            float growX, float growY, float growZ) {
        return new ModelPart.Cube(texU, texV, minX, minY, minZ, sizeX,
                sizeY, sizeZ, growX, growY, growZ, false,
                MORPHSKIN_TEX_SIZE, MORPHSKIN_TEX_SIZE, ALL_FACES);
    }

    /**
     * The original's model-height measure: the tallest of the part's own
     * boxes, as an int (used to seat differently-sized arms in the
     * first-person hand slot).
     */
    static int maxBoxHeight(float[][] specs) {
        int height = 0;
        for (float[] spec : specs) {
            height = Math.max(height, (int) Math.abs(spec[4]));
        }
        return height;
    }

    // NB: the former randomPointInSpecs/randomCoord "budding spawn" helpers are
    // gone (wave 6 item B): they implemented the original's construction-time
    // scatter, which was render-dead upstream — the interim rebuilds every box
    // from the zero-at-part-origin defaults from its first visible frame.

    /** Snapshot of a part's full mutable pose (incl. scale). */
    static PartPose fullPose(ModelPart part) {
        return new PartPose(part.x, part.y, part.z, part.xRot, part.yRot,
                part.zRot, part.xScale, part.yScale, part.zScale);
    }

    // NB: the former generic applyLivingTransform (entity scale → YP(180−bodyRot)
    // → flip → hook scale → −1.501) is gone: both the interim submit and the
    // morphskin overlay pass now use the MEASURED transform below (wave-7 review
    // fix 1) — which decomposes to exactly that chain for standard renderers and
    // is boundary-exact for the rest (phantom's translating hook, squid's
    // pivoted tilts, cod's land flop). measure()'s identity fallback IS that
    // generic chain in data form.

    /**
     * The per-axis scale a renderer's {@code scale(state, PoseStack)} hook
     * applies for this state — the modern equivalent of the original mod
     * reading the modelview diagonal before/after the preRenderCallback.
     * Runs the real hook on a throwaway pose and reads its scale (hooks
     * only scale/translate, no rotation, so the matrix scale is exact).
     * Returns (1,1,1) for the common no-op hook or on any failure.
     */
    static Vector3f measureHookScale(LivingEntityRenderer<?, ?, ?> renderer,
            LivingEntityRenderState state) {
        try {
            PoseStack probe = new PoseStack();
            ((LivingEntityRendererInvoker) renderer)
                    .deds_morph$invokeScale(state, probe);
            return probe.last().pose().getScale(new Vector3f());
        } catch (Exception e) {
            return new Vector3f(1.0f, 1.0f, 1.0f);
        }
    }

    /**
     * A renderer's FULL measured submit transform for one state (wave 7 item 2).
     * {@code measureHookScale} above reads only the scale hook's SCALE column and
     * discards its translation — but hooks are not always pure scales:
     * {@code PhantomRenderer.scale} is {@code scale(1+0.15·size)} then
     * {@code translate(0, 1.3125, 0.1875)} (bytecode-verified), which is why a
     * phantom interim animated ~1.3 blocks too high and "teleported down" at tick
     * 70. And {@code setupRotations} is not always the generic
     * {@code YP(180−bodyRot)} — squid pivots+tilts, phantom adds a glide
     * {@code XP(xRot)}. This record carries both hooks fully decomposed, plus the
     * renderer's {@code getRenderOffset}.
     *
     * @param rot          the setupRotations rotation ({@code [R|t]}'s R — exact:
     *                     that hook composes only rotations + translations)
     * @param rotTrans     the setupRotations translation column ({@code [R|t]}'s t)
     * @param hookScale    the scale() hook's scale ({@code M = S·T} decomposition)
     * @param hookTrans    the scale() hook's translation column ({@code S·t}, so
     *                     re-applying translate-then-scale reproduces M exactly)
     * @param renderOffset the renderer's world-space render offset
     */
    record RendererTransform(Quaternionf rot, Vector3f rotTrans,
            Vector3f hookScale, Vector3f hookTrans, Vec3 renderOffset) {
    }

    /**
     * Empirically measures {@code renderer}'s full submit transform for
     * {@code state} by probing both protected hooks on throwaway poses (the same
     * pattern as {@link #measureHookScale}, extended). Generic by construction —
     * no per-mob table, so modded renderers honoring the LivingEntityRenderer
     * contract are covered. Any failure falls back to the identity transform
     * ({@code YP(180−bodyRot)}, scale 1, zero translates) = the pre-wave-7
     * behavior. setupRotations is invoked with {@code (state, probe,
     * state.bodyRot, state.scale)} — the javap-verified vanilla call shape.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static RendererTransform measure(LivingEntityRenderer<?, ?, ?> renderer,
            LivingEntityRenderState state) {
        try {
            PoseStack rotProbe = new PoseStack();
            ((LivingEntityRendererInvoker) renderer)
                    .deds_morph$invokeSetupRotations(state, rotProbe,
                            state.bodyRot, state.scale);
            Quaternionf rot = rotProbe.last().pose()
                    .getUnnormalizedRotation(new Quaternionf());
            Vector3f rotTrans = rotProbe.last().pose()
                    .getTranslation(new Vector3f());

            PoseStack hookProbe = new PoseStack();
            ((LivingEntityRendererInvoker) renderer)
                    .deds_morph$invokeScale(state, hookProbe);
            Vector3f hookScale = hookProbe.last().pose()
                    .getScale(new Vector3f());
            Vector3f hookTrans = hookProbe.last().pose()
                    .getTranslation(new Vector3f());

            Vec3 renderOffset =
                    ((EntityRenderer) renderer).getRenderOffset(state);
            return new RendererTransform(rot, rotTrans, hookScale, hookTrans,
                    renderOffset == null ? Vec3.ZERO : renderOffset);
        } catch (Exception e) {
            return new RendererTransform(
                    Axis.YP.rotationDegrees(180.0f - state.bodyRot),
                    new Vector3f(), new Vector3f(1.0f, 1.0f, 1.0f),
                    new Vector3f(), Vec3.ZERO);
        }
    }

    /** Component lerp of two measured transforms: rotation slerp'd (hemisphere-
     *  stabilized), all four vector parts lerp'd — exact at both endpoints
     *  (mag 0 = prev renderer, mag 1 = next renderer), so the interim never
     *  snaps at either boundary. */
    static RendererTransform lerpTransform(RendererTransform from,
            RendererTransform to, float mag) {
        // Hemisphere stabilization (wave-7 review fix 2): q and −q encode the
        // SAME rotation, but slerping between opposite-hemisphere
        // representations takes the long arc — and at dot ≈ 0 (Shulker passes
        // bodyRot+180 to super, putting its measured rot analytically 180° in
        // yaw from a humanoid's; same for isUpsideDown "Dinnerbone" targets)
        // JOML's internal path choice keys on its own cosom float noise and can
        // flip PER FRAME while the player turns (interim blob yaw-flickering up
        // to 180°). Negate 'to' into 'from's hemisphere when the dot is
        // negative; the dot is computed HERE from the components, so the sign
        // test is a pure, frame-stable function of the two measured quaternions.
        // TIE-BREAK at dot == 0.0f exactly: the strict < comparison keeps the
        // UNNEGATED 'to' (the coordinator-offered deterministic choice) — both
        // arcs are equally long there, and the same inputs always pick the same
        // one.
        Quaternionf fromRot = from.rot();
        Quaternionf toRot = new Quaternionf(to.rot());
        float dot = fromRot.x() * toRot.x() + fromRot.y() * toRot.y()
                + fromRot.z() * toRot.z() + fromRot.w() * toRot.w();
        if (dot < 0.0f) {
            toRot.set(-toRot.x(), -toRot.y(), -toRot.z(), -toRot.w());
        }
        return new RendererTransform(
                fromRot.slerp(toRot, mag, new Quaternionf()),
                lerpVec(from.rotTrans(), to.rotTrans(), mag),
                lerpVec(from.hookScale(), to.hookScale(), mag),
                lerpVec(from.hookTrans(), to.hookTrans(), mag),
                new Vec3(
                        lerp(from.renderOffset().x, to.renderOffset().x, mag),
                        lerp(from.renderOffset().y, to.renderOffset().y, mag),
                        lerp(from.renderOffset().z, to.renderOffset().z, mag)));
    }

    /**
     * Applies a measured transform in vanilla's exact submit order (the generic
     * living chain with its single YP step replaced by the measured {@code [R|t]}
     * and the hook's translation restored): entity scale → rotTrans → rot →
     * (−1,−1,1) flip → hookTrans → hookScale → −1.501 lift. The world-space
     * {@code renderOffset} is applied by the CALLER in its initial translate.
     */
    static void applyMeasuredTransform(PoseStack poseStack, float entityScale,
            RendererTransform transform) {
        poseStack.scale(entityScale, entityScale, entityScale);
        poseStack.translate(transform.rotTrans().x(), transform.rotTrans().y(),
                transform.rotTrans().z());
        McCompat.rotate(poseStack, transform.rot());
        poseStack.scale(-1.0f, -1.0f, 1.0f);
        poseStack.translate(transform.hookTrans().x(),
                transform.hookTrans().y(), transform.hookTrans().z());
        poseStack.scale(transform.hookScale().x(), transform.hookScale().y(),
                transform.hookScale().z());
        poseStack.translate(0.0f, -1.501f, 0.0f);
    }

    private static Vector3f lerpVec(Vector3f from, Vector3f to, float mag) {
        return new Vector3f(
                from.x() + (to.x() - from.x()) * mag,
                from.y() + (to.y() - from.y()) * mag,
                from.z() + (to.z() - from.z()) * mag);
    }

    private static double lerp(double from, double to, float mag) {
        return from + (to - from) * mag;
    }

    /**
     * The model tree the renderer would actually draw for this state. For
     * an {@link net.minecraft.client.renderer.entity.AgeableMobRenderer}
     * (zombies, most mobs) that is baby vs adult chosen by the state's own
     * {@code isBaby} — NOT the racy shared {@code getModel()} field that the
     * last-rendered entity leaves behind. Every other renderer has one
     * stable model, so {@code getModel()} is correct there.
     */
    static Model<?> modelForState(LivingEntityRenderer<?, ?, ?> renderer,
            LivingEntityRenderState state) {
        if (renderer instanceof AgeableMobRendererAccessor ageable) {
            return state.isBaby
                    ? ageable.deds_morph$babyModel()
                    : ageable.deds_morph$adultModel();
        }
        return renderer.getModel();
    }
}
