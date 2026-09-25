package com.deds.morph.fabric.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.rendertype.RenderTypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The interim "part morph" model for one running transformation — the
 * recreation of the original's per-frame box-rebuilding morph (Morph
 * 0.7.1), shown from tick 10 to 70 of the 80-tick window, entirely in
 * morphskin.
 *
 * <p><b>Pairing (wave 6 round 2 — the shrink-side fix):</b> per level,
 * INVISIBLE children are first excluded from both sides entirely (read
 * after the morph-start setupAnim, so skin-layer toggles apply — hidden
 * hat/jacket/sleeve/pants parts never pair, never bud, never pop); then
 * children pair NAME-FIRST (exact child-name matches), and leftovers pair
 * by greedy nearest-pivot — globally smallest pivot distance first, ties
 * broken by name for determinism — under the SUCK-IN GUARD (wave 7): a
 * candidate is rejected outright when the dying part would have to rise
 * more than {@link #RISE_LIMIT_PX} model pixels or move more than
 * {@link #LATERAL_OUT_LIMIT_PX} pixels farther OUT from the body axis to
 * reach it (cow hind legs were pairing player ARMS and rising full-size to
 * shoulder height; the original's semantic order always sent dying mass
 * inward/downward). Unmatched children remain prev-only
 * shrinkers / next-only buds; recursion per pair (depth ≤ 20); within a
 * part, box j pairs with box j. WHY: 26.2 bakes children through a plain
 * HashMap, so the old index-zip paired in String-hash order — cow
 * body↔chicken wing, enderman leg↔player sleeve — and the ease-in pivot
 * curve made dying parts ACCELERATE toward those wrong targets at the end
 * ("fly outwards, disappear mid-air"). The 1.6.4 original paired by
 * reflection FIELD-DECLARATION order, which was semantic (head, body,
 * arms, legs); name matching restores that outcome exactly for same-family
 * morphs, and pivot proximity is the closest recoverable signal to it for
 * the rest (coordinator-decided judgment call). The rig is ONE synthetic
 * tree built when the morph starts (the original built its copies when the
 * morph packet arrived), so it does not depend on when rendering first
 * happens.</p>
 *
 * <p><b>Geometry (the original's VISIBLE mechanism — wave 6 item B):</b>
 * every box is re-created every frame with corner offsets lerped
 * continuously and sizes lerped then rounded to integers (the original's
 * stepped growth), mag = {@code ((t-10)/50)^2} until tick 60, then held at
 * the target. A box with <b>no prev counterpart grows zero-size from the
 * PART ORIGIN</b> (its body-anchored pivot); a box with <b>no next
 * counterpart collapses to zero there</b> — that pivot-anchored grow/
 * collapse is the "suck-in" the original showed. (The original's
 * construction-time random scatter and full-size child padding were
 * render-dead: its updateCubeMorph rebuilt every box from these zero
 * defaults on the first interim frame, before anything drew. Implementing
 * them made incoming geometry float full-size away from the body.) Whole
 * prev-only parts keep their frozen morph-start pose while their boxes
 * collapse; budding parts' pivots hard-track the next form's live pose
 * from frame one. Incoming boxes stay invisible until
 * {@code round(size*mag) >= 1}, so new parts appear late and small, then
 * swell out of the body — exactly the original.</p>
 *
 * <p><b>Pose (exact curves):</b> on {@code t = progress - 10}: angles lerp
 * with {@code (t/30)^2} until tick 40 then hard-track the next form's LIVE
 * pose; pivots with {@code (t/50)^2} until 60 then hold. Both source
 * models are pumped live each frame (limbs keep swinging mid-morph).</p>
 *
 * <p><b>End-state invariant (by construction):</b> from tick 60 the boxes
 * equal the next form's exactly (positions, integer sizes, recovered
 * grow), pivots/angles/scale track the next form's live pose verbatim, and
 * the submit uses the same orientation recipe as the vanilla renderer — so
 * the hard switch to the real render at tick 70 only changes the texture
 * (which the fading overlay covers), not the silhouette.</p>
 */
@Environment(EnvType.CLIENT)
final class MorphTransitionRig {

    private static final int MAX_DEPTH = 20;
    /** x,y,z, xRot,yRot,zRot, xScale,yScale,zScale, visible. */
    private static final int POSE_STRIDE = 10;

    /** Suck-in guard (wave 7 item 1) — a leftover pairing is REJECTED when the
     *  dying part would have to fly UP more than this many model pixels to reach
     *  its target (model y is DOWN, so "up" = target pivot y smaller). Numerically
     *  pinned: the legit chicken-legs→cow-legs small-to-big pairing rises exactly
     *  7px, so the limit must be &gt;7; do NOT tune without re-running the matrix. */
    private static final float RISE_LIMIT_PX = 8.0f;

    /** Suck-in guard, lateral half — rejected when the target pivot sits more
     *  than this many pixels farther OUT from the body axis than the source.
     *  Numerically pinned: the legit player-legs→cow-front-legs pairing is 2.1px
     *  out, so the limit must be &gt;2.1; do NOT tune. */
    private static final float LATERAL_OUT_LIMIT_PX = 3.0f;

    /** Per-frame interpolated pose + box data, computed at extract time. */
    record Frame(float[] pose, float[][] boxes) {
    }

    /**
     * One synthetic part: paired ({@code prevLive} and {@code nextLive}
     * both set), prev-only shrinker ({@code nextLive == null}, frozen
     * pose), or next-only bud ({@code prevLive == null}).
     */
    private static final class Node {
        final ModelPart part;
        final List<ModelPart.Cube> cubes;
        final ModelPart prevLive;
        final ModelPart nextLive;
        final PartPose frozen;
        final boolean frozenVisible;
        final float[][] prevBoxes;
        final float[][] nextBoxes;
        /** Draw-time cache: skip the cube rebuild when data is unchanged. */
        float[] builtBoxes;

        Node(ModelPart part, List<ModelPart.Cube> cubes, ModelPart prevLive,
                ModelPart nextLive, PartPose frozen, boolean frozenVisible,
                float[][] prevBoxes, float[][] nextBoxes) {
            this.part = part;
            this.cubes = cubes;
            this.prevLive = prevLive;
            this.nextLive = nextLive;
            this.frozen = frozen;
            this.frozenVisible = frozenVisible;
            this.prevBoxes = prevBoxes;
            this.nextBoxes = nextBoxes;
        }
    }

    private final List<Node> nodes = new ArrayList<>();
    private final Model<Frame> model;

    /**
     * Builds the rig from the two SHARED renderer models, which must both
     * be posed (setupAnim already called with morph-start states) so
     * frozen parts keep the pose the morph began with. Fully deterministic
     * (wave 6 item B): the original's construction-time random scatter was
     * render-dead upstream and is gone here too.
     */
    MorphTransitionRig(Model<?> prevShared, Model<?> nextShared) {
        ModelPart root = buildNode(prevShared.root(), nextShared.root(), 0);
        this.model = new InterimModel(root);
    }

    /**
     * Recursively builds the union tree. Exactly one of prev/next may be
     * null (never both — guaranteed by {@link #pairChildren}'s construction:
     * name-first matches and greedy nearest-pivot pairs carry both sides, and
     * the leftover shrinker/bud entries each carry exactly one).
     */
    private ModelPart buildNode(ModelPart prev, ModelPart next, int depth) {
        float[][] prevBoxes = prev != null
                ? MorphModels.boxSpecs(prev) : new float[0][];
        float[][] nextBoxes = next != null
                ? MorphModels.boxSpecs(next) : new float[0][];

        List<ModelPart.Cube> cubes = new ArrayList<>();
        Map<String, ModelPart> children = new LinkedHashMap<>();
        if (depth < MAX_DEPTH) {
            int k = 0;
            for (ChildPair pair : pairChildren(prev, next)) {
                children.put("c" + k++,
                        buildNode(pair.prev(), pair.next(), depth + 1));
            }
        }
        ModelPart part = new ModelPart(cubes, children);
        nodes.add(new Node(part, cubes, prev, next,
                prev != null ? MorphModels.fullPose(prev) : PartPose.ZERO,
                prev != null && prev.visible, prevBoxes, nextBoxes));
        return part;
    }

    /** One matched child slot; exactly one side may be null (shrinker/bud). */
    private record ChildPair(ModelPart prev, ModelPart next) {
    }

    /**
     * The per-level child pairing (wave 6 round 2): exclude invisible children
     * from BOTH sides (P2), pair exact names (P1), pair leftovers by greedy
     * nearest-pivot (globally smallest distance first, ties by names), and leave
     * the rest as prev-only shrinkers / next-only buds. Deterministic: name
     * matching is order-independent, the leftover greedy is fully sorted, and
     * pair order is prev's child order, then sorted leftovers, then stragglers.
     */
    private static List<ChildPair> pairChildren(ModelPart prev, ModelPart next) {
        LinkedHashMap<String, ModelPart> prevLeft = visibleChildren(prev);
        LinkedHashMap<String, ModelPart> nextLeft = visibleChildren(next);
        List<ChildPair> pairs = new ArrayList<>();

        // 1) Exact-name matches, in prev child order.
        for (Iterator<Map.Entry<String, ModelPart>> it =
                prevLeft.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, ModelPart> entry = it.next();
            ModelPart match = nextLeft.remove(entry.getKey());
            if (match != null) {
                pairs.add(new ChildPair(entry.getValue(), match));
                it.remove();
            }
        }

        // 2) Leftovers by greedy nearest-pivot (pivots read after the
        //    morph-start setupAnim, in this part's local frame).
        record Candidate(double distSq, String prevName, String nextName) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, ModelPart> pe : prevLeft.entrySet()) {
            for (Map.Entry<String, ModelPart> ne : nextLeft.entrySet()) {
                ModelPart a = pe.getValue();
                ModelPart b = ne.getValue();
                double dx = a.x - b.x;
                double dy = a.y - b.y;
                double dz = a.z - b.z;
                // Suck-in guard (wave 7 item 1): dying mass must never fly UP
                // (>8px; model y is down, so dy = a.y - b.y > 0 means the target
                // is above) or laterally OUT (target >3px farther from the body
                // axis) to reach its target — that was cow hind legs rising
                // full-size to player SHOULDERS on cow→player. Rejected parts
                // fall through to the existing shrink-collapse/bud paths (the
                // original's pivot-anchored suck-in).
                if (dy > RISE_LIMIT_PX
                        || Math.abs(b.x) - Math.abs(a.x) > LATERAL_OUT_LIMIT_PX) {
                    continue;
                }
                candidates.add(new Candidate(dx * dx + dy * dy + dz * dz,
                        pe.getKey(), ne.getKey()));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distSq)
                .thenComparing(Candidate::prevName)
                .thenComparing(Candidate::nextName));
        for (Candidate candidate : candidates) {
            ModelPart a = prevLeft.get(candidate.prevName());
            ModelPart b = nextLeft.get(candidate.nextName());
            if (a == null || b == null) {
                continue; // one side already claimed by a closer pair
            }
            prevLeft.remove(candidate.prevName());
            nextLeft.remove(candidate.nextName());
            pairs.add(new ChildPair(a, b));
        }

        // 3) True prev-only shrinkers, then next-only buds.
        for (ModelPart shrinker : prevLeft.values()) {
            pairs.add(new ChildPair(shrinker, null));
        }
        for (ModelPart bud : nextLeft.values()) {
            pairs.add(new ChildPair(null, bud));
        }
        return pairs;
    }

    /** A part's VISIBLE children by name (P2 build-time exclusion: a child with
     *  {@code visible == false} — e.g. a disabled skin layer, read after the
     *  morph-start setupAnim — is absent from its side entirely). */
    private static LinkedHashMap<String, ModelPart> visibleChildren(ModelPart part) {
        LinkedHashMap<String, ModelPart> out = new LinkedHashMap<>();
        if (part == null) {
            return out;
        }
        for (Map.Entry<String, ModelPart> entry
                : MorphModels.childMapOf(part).entrySet()) {
            if (entry.getValue().visible) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    Model<Frame> model() {
        return model;
    }

    /**
     * Interpolates one frame. Both shared models must be freshly posed
     * from this frame's render states before calling (live pose pumping —
     * the modern stand-in for the original's offscreen force-renders).
     *
     * @param ft float morph progress in ticks (10 ≤ ft < 70 while shown)
     */
    Frame capture(float ft) {
        int tick = (int) ft;
        float t = ft - 10.0f;
        // Original windows use the INTEGER tick, so the unclamped curves
        // can overshoot 1.0 for the sub-tick remainder of ticks 40/60 —
        // replicated as-is.
        float angMag = (t / 30.0f) * (t / 30.0f);
        float posMagRaw = (t / 50.0f) * (t / 50.0f);
        boolean angHard = tick > 40;
        boolean posHard = tick > 60;
        float posMag = posHard ? 1.0f : posMagRaw;

        float[] pose = new float[nodes.size() * POSE_STRIDE];
        float[][] boxes = new float[nodes.size()][];
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            capturePose(node, pose, i * POSE_STRIDE, angMag, angHard,
                    posMag);
            boxes[i] = captureBoxes(node, posMag);
        }
        return new Frame(pose, boxes);
    }

    private static void capturePose(Node node, float[] pose, int o,
            float angMag, boolean angHard, float posMag) {
        if (node.prevLive == null) {
            // Next-only bud: tracks the next form's live pose verbatim
            // (the original set budding transforms straight to next's).
            ModelPart next = node.nextLive;
            pose[o] = next.x;
            pose[o + 1] = next.y;
            pose[o + 2] = next.z;
            pose[o + 3] = next.xRot;
            pose[o + 4] = next.yRot;
            pose[o + 5] = next.zRot;
            pose[o + 6] = next.xScale;
            pose[o + 7] = next.yScale;
            pose[o + 8] = next.zScale;
            pose[o + 9] = next.visible ? 1.0f : 0.0f;
            return;
        }
        if (node.nextLive == null) {
            // Prev-only shrinker: frozen at the morph-start pose; its
            // boxes collapse to nothing by tick 60.
            PartPose frozen = node.frozen;
            pose[o] = frozen.x();
            pose[o + 1] = frozen.y();
            pose[o + 2] = frozen.z();
            pose[o + 3] = frozen.xRot();
            pose[o + 4] = frozen.yRot();
            pose[o + 5] = frozen.zRot();
            pose[o + 6] = frozen.xScale();
            pose[o + 7] = frozen.yScale();
            pose[o + 8] = frozen.zScale();
            pose[o + 9] = node.frozenVisible ? 1.0f : 0.0f;
            return;
        }
        ModelPart prev = node.prevLive;
        ModelPart next = node.nextLive;
        pose[o] = lerp(prev.x, next.x, posMag);
        pose[o + 1] = lerp(prev.y, next.y, posMag);
        pose[o + 2] = lerp(prev.z, next.z, posMag);
        // Raw angle lerp, no wrapping — original behavior.
        pose[o + 3] = angHard ? next.xRot
                : lerp(prev.xRot, next.xRot, angMag);
        pose[o + 4] = angHard ? next.yRot
                : lerp(prev.yRot, next.yRot, angMag);
        pose[o + 5] = angHard ? next.zRot
                : lerp(prev.zRot, next.zRot, angMag);
        pose[o + 6] = lerp(prev.xScale, next.xScale, posMag);
        pose[o + 7] = lerp(prev.yScale, next.yScale, posMag);
        pose[o + 8] = lerp(prev.zScale, next.zScale, posMag);
        // Paired nodes are constant-visible: BOTH sides were visible at build
        // time (P2 excludes invisible children up front), so the old mid-lerp
        // flip — which popped a part out instantly at p≈45 when its mis-paired
        // next side was a hidden skin layer — has nothing left to switch on.
        pose[o + 9] = 1.0f;
    }

    /**
     * The original's per-frame box rebuild rules (the VISIBLE ones — wave 6
     * item B): corner offsets lerp continuously, sizes lerp and ROUND to
     * integers, and a missing counterpart on EITHER side defaults to
     * pos(0,0,0) size 0 in the part's local space. So a box with no prev
     * counterpart grows zero→target from the PART ORIGIN (its body-anchored
     * pivot — the "suck-in"), and a box with no next counterpart collapses
     * to zero there. The original's construction-time scatter (random spawn
     * points, full-size padded children) was render-dead upstream — its
     * updateCubeMorph deletes and re-adds every box each frame from these
     * defaults starting at the FIRST interim frame, overwriting the
     * construction values before anything draws — so the old seeded
     * budOrigins/budFullSize path here showed full-size floating geometry
     * the original never displayed. Grow (cube deformation) lerps too so
     * the end state matches the next form's rendered geometry exactly.
     */
    private static float[] captureBoxes(Node node, float posMag) {
        int slots = Math.max(node.prevBoxes.length, node.nextBoxes.length);
        float[] out = new float[slots * MorphModels.BOX_SPEC];
        for (int s = 0; s < slots; s++) {
            float[] from = s < node.prevBoxes.length
                    ? node.prevBoxes[s]
                    : new float[MorphModels.BOX_SPEC]; // no prev: zero at origin
            float[] to = s < node.nextBoxes.length ? node.nextBoxes[s]
                    : new float[MorphModels.BOX_SPEC];
            int o = s * MorphModels.BOX_SPEC;
            out[o] = lerp(from[0], to[0], posMag);
            out[o + 1] = lerp(from[1], to[1], posMag);
            out[o + 2] = lerp(from[2], to[2], posMag);
            out[o + 3] = Math.round(lerp(from[3], to[3], posMag));
            out[o + 4] = Math.round(lerp(from[4], to[4], posMag));
            out[o + 5] = Math.round(lerp(from[5], to[5], posMag));
            out[o + 6] = lerp(from[6], to[6], posMag);
            out[o + 7] = lerp(from[7], to[7], posMag);
            out[o + 8] = lerp(from[8], to[8], posMag);
        }
        return out;
    }

    private static float lerp(float from, float to, float mag) {
        return from + (to - from) * mag;
    }

    /**
     * The interim canvas. setupAnim runs at DRAW time (26.2 submits are
     * deferred) and writes the captured frame — poses AND rebuilt cubes —
     * into this rig's private tree; the shared renderer trees are never
     * touched.
     */
    private final class InterimModel extends Model<Frame> {

        InterimModel(ModelPart root) {
            super(root, RenderTypes::entityCutout);
        }

        @Override
        public void setupAnim(Frame frame) {
            for (int i = 0; i < nodes.size(); i++) {
                Node node = nodes.get(i);
                float[] pose = frame.pose();
                int o = i * POSE_STRIDE;
                node.part.x = pose[o];
                node.part.y = pose[o + 1];
                node.part.z = pose[o + 2];
                node.part.xRot = pose[o + 3];
                node.part.yRot = pose[o + 4];
                node.part.zRot = pose[o + 5];
                node.part.xScale = pose[o + 6];
                node.part.yScale = pose[o + 7];
                node.part.zScale = pose[o + 8];
                node.part.visible = pose[o + 9] > 0.5f;
                rebuildCubes(node, frame.boxes()[i]);
            }
        }

        private void rebuildCubes(Node node, float[] data) {
            if (Arrays.equals(node.builtBoxes, data)) {
                return; // static after tick 60 (and often within a tick)
            }
            node.builtBoxes = data;
            node.cubes.clear();
            for (int o = 0; o + MorphModels.BOX_SPEC <= data.length;
                    o += MorphModels.BOX_SPEC) {
                if (data[o + 3] <= 0.0f && data[o + 4] <= 0.0f
                        && data[o + 5] <= 0.0f) {
                    continue; // fully collapsed box renders nothing
                }
                // texU/texV 0: morphskin is uniform noise, so the patch
                // offset is invisible and non-zero would clamp off the 64px
                // UV range (see MorphModels.makeCube).
                node.cubes.add(MorphModels.makeCube(0, 0,
                        data[o], data[o + 1], data[o + 2], data[o + 3],
                        data[o + 4], data[o + 5], data[o + 6], data[o + 7],
                        data[o + 8]));
            }
        }
    }
}
