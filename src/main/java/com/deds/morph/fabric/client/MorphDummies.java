package com.deds.morph.fabric.client;

import com.deds.api.Deds;
import com.deds.morph.Morph;
import com.deds.morph.MorphEntities;
import com.deds.morph.MorphSort;
import com.deds.morph.MorphVariant;
import com.deds.morph.fabric.client.mixin.EntitySharedFlagsInvoker;
import com.deds.morph.fabric.client.mixin.SquidAccessor;

import com.mojang.authlib.GameProfile;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client-side morph visuals: one never-spawned "dummy" mob per morphed
 * player (posed from the player every frame and rendered in the player's
 * place by {@code EntityRenderDispatcherMixin}), plus the original's
 * 80-tick three-phase transformation and the morph sound on every change.
 *
 * <p><b>Transformation (SPEC {@code transform-animation}, recreated from
 * the decompiled original):</b> ticks 0-10 the OLD form renders normally
 * with a morphskin overlay fading in; 10-70 ONLY the interim part-morph
 * model ({@link MorphTransitionRig}) renders, pure morphskin; 70-80 the NEW
 * form renders normally with the overlay fading out. The interim rig is
 * built when the transition STARTS (like the original's packet-time model
 * copies), so its behavior never depends on when the player first gets
 * extracted (first-person starts, off-screen players). The original's
 * camera/eye-height glide is deferred to the hitbox wave (SPEC note).</p>
 *
 * <p>Everything here is loader/client glue by nature; the whole class is
 * the workaround surface for missing client API.
 * TODO(deds-api): lift — "render another entity in a player's place",
 * per-entity crash isolation, and sound-event playing/registration are all
 * API v1.1 candidates (SPEC "API prerequisites" 4, 9; sound registration
 * has no ModContext registrar yet).</p>
 */
@Environment(EnvType.CLIENT)
public final class MorphDummies {

    /** Resolves via sounds.json to a random one of morph1-6.ogg, like the
     *  original's 1.6.4 sound pool did for its "morph:morph" event. */
    private static final Identifier MORPH_SOUND =
            Identifier.fromNamespaceAndPath(Morph.MOD_ID, "morph");

    /**
     * Attachment syncs observed within this many ticks of FIRST seeing a
     * player are login/tracking-start payloads, not live morphs: swap
     * silently instead of animating (fixes the rejoin-replays-the-morph
     * bug — the state lands a tick or two after the player is first seen).
     */
    private static final int FIRST_SEEN_GRACE_TICKS = 40;

    /** Vanilla player standing eye height (the glide's own-form endpoint). */
    private static final float DEFAULT_PLAYER_EYE = 1.62f;

    /** Downward render dip for a NON-humanoid morph while the player sneaks
     *  (humanoids get the real crouch pose instead) — the vanilla sneak Y dip. */
    private static final double SNEAK_DIP = 0.15;

    /** Vanilla-style sneak CAMERA dip for a crouching non-humanoid morph (the
     *  {@code PlayerEyeHeightMixin.crouchDip} amount; see {@link #crouchCameraDip}). */
    private static final float SNEAK_CAMERA_DIP = 0.1f;

    /** {@code Entity} shared-flag index backing {@code LivingEntity.isFallFlying()}
     *  (javap-verified: {@code isFallFlying} == {@code getSharedFlag(7)}). */
    private static final int FALL_FLYING_FLAG = 7;

    /** Vanilla nametag ranges, squared: {@code EntityRenderer.extractNameTags}
     *  passes 64.0 as the name distance, and {@code LivingEntityRenderer
     *  .shouldShowName} drops a DISCRETE (sneaking) entity's tag at 32 (1024 sq). */
    private static final double NAME_RANGE_SQ = 64.0 * 64.0;
    private static final double DISCRETE_NAME_RANGE_SQ = 1024.0;

    /** Tolerance for the per-tick box-invariant compare (item B3). */
    private static final float BOX_EPSILON = 1.0e-4f;

    /** τ for the squid tentacle phase wrap (vanilla wraps at 2π). */
    private static final float TAU = (float) (Math.PI * 2.0);

    private record Dummy(MorphVariant variant, LivingEntity entity) {
    }

    /**
     * A running transformation. {@code fromDummy == null} = own form. The
     * interim rig is built once at transition start; {@code rigFailed}
     * degrades the 10-70 window to a plain form swap. {@code fromVariant} is
     * the pre-transition committed morph (empty = own form) — the hitbox mixin
     * keeps returning ITS box until the transition ends (snap-at-end, matching
     * the server); {@code prevEye}/{@code nextEye} bracket the camera glide.
     */
    private static final class Transition {
        final LivingEntity fromDummy;
        final Optional<MorphVariant> fromVariant;
        final int startTick;
        MorphTransitionRig rig;
        boolean rigFailed;
        float prevEye = DEFAULT_PLAYER_EYE;
        float nextEye = DEFAULT_PLAYER_EYE;

        Transition(LivingEntity fromDummy, Optional<MorphVariant> fromVariant,
                int startTick) {
            this.fromDummy = fromDummy;
            this.fromVariant = fromVariant;
            this.startTick = startTick;
        }
    }

    /** The three visual phases of the 80-tick window + steady state. */
    public enum Phase {
        /** No transition: just render the morph dummy. */
        STEADY,
        /** Ticks 0-10: prev form + morphskin overlay fading IN. */
        OVERLAY_IN,
        /** Ticks 10-70: only the interim part-morph model. */
        INTERIM,
        /** Ticks 70-80: next form + morphskin overlay fading OUT. */
        OVERLAY_OUT
    }

    /**
     * What the render mixin should do for one player this frame.
     * {@code primary} is the entity whose extracted state replaces the
     * player's (may be the player itself when the phase's form is the own
     * form); {@code aux} is the transition's other form, extracted too
     * during INTERIM so both models can be live-posed.
     */
    public record Plan(Phase phase, LivingEntity primary, LivingEntity aux,
            float progress, float overlayAlpha, Transition transition) {
    }

    /**
     * The first-person hand's plan for the local player this frame
     * ({@code prevForm}/{@code nextForm} null = the player's own form).
     */
    public record HandPlan(Phase phase, LivingEntity prevForm,
            LivingEntity nextForm, float progress, float overlayAlpha) {
    }

    /** Morphskin overlay pass over the normally-rendered form. */
    public record OverlayPayload(float alpha) {
    }

    /**
     * Replace the vanilla submit with the interim morph model.
     * {@code baseScale} is the {@link LivingEntityRenderState#scale} lerp;
     * {@code transform} is the prev→next lerp of the two renderers' MEASURED
     * submit transforms (wave 7 item 2: setupRotations {@code [R|t]} slerp/lerp'd
     * + the scale() hook's scale AND translation + renderOffset) applied where
     * the vanilla renderer applies them — bit-equivalent to the vanilla chain at
     * both endpoints, so the interim never snaps at tick 10 or 70 (the phantom's
     * hook translate(0, 1.3125, 0.1875) was being discarded, leaving its interim
     * animating ~1.3 blocks too high). {@code bodyRot} is retained for fallback
     * paths.
     */
    public record InterimPayload(MorphTransitionRig rig,
            MorphTransitionRig.Frame frame, float bodyRot, float baseScale,
            MorphModels.RendererTransform transform) {
    }

    private static final Map<UUID, Dummy> DUMMIES = new HashMap<>();
    private static final Map<UUID, Optional<MorphVariant>> LAST_SEEN =
            new HashMap<>();
    private static final Map<UUID, Integer> FIRST_SEEN = new HashMap<>();
    private static final Map<UUID, Transition> TRANSITIONS = new HashMap<>();
    /** The player OBJECT last seen per UUID — a new instance for a known UUID is
     *  a respawn/clone and must re-adopt the morph box (fix 5). */
    private static final Map<UUID, AbstractClientPlayer> LAST_INSTANCE =
            new HashMap<>();
    /** Morph variants whose entity type failed to resolve/build — logged once,
     *  then rendered as the vanilla player (1.6.4-era crash-isolation lesson). */
    private static final Set<MorphVariant> BROKEN = new HashSet<>();

    /** Per-frame render payloads stashed between extract and submit, keyed
     *  by the extracted state instance ({@link OverlayPayload} /
     *  {@link InterimPayload}). */
    private static final Map<EntityRenderState, Object>
            FRAME_PAYLOADS = new IdentityHashMap<>();

    /** True while WE are extracting: the mixin must not re-enter. */
    private static boolean rawExtraction;

    private static int clientTicks;

    private MorphDummies() {
    }

    // ------------------------------------------------------------------
    // per-tick upkeep (called from MorphClient's END_CLIENT_TICK)
    // ------------------------------------------------------------------

    public static void clientTick(Minecraft minecraft) {
        FRAME_PAYLOADS.clear();
        ClientLevel level = minecraft.level;
        if (level == null) {
            DUMMIES.clear();
            LAST_SEEN.clear();
            FIRST_SEEN.clear();
            TRANSITIONS.clear();
            LAST_INSTANCE.clear();
            // sortMorphs mode 3 is "most recently used SINCE CONNECTING to the
            // server" (O:morph/common/Morph.java:188) — leaving a world ends it.
            MorphSort.forgetRecent();
            return;
        }
        // SP pause freeze (wave 6 item D): Fabric's END_CLIENT_TICK fires from
        // Minecraft.tick(), which keeps running ~20/s while PAUSED — so morph
        // animations, transition aging and the camera glide kept advancing on the
        // Esc screen. One gate freezes clientTicks, advanceAnimation, transition
        // aging, LAST_SEEN diffing, reconcileBox and healOfflinePlayerSkin
        // together. Safe: the integrated server suspends whenever isPaused(), so
        // no sync can race; MP/LAN-host Esc never pauses, so running worlds keep
        // animating (required).
        if (minecraft.isPaused()) {
            return;
        }
        clientTicks++;
        Set<UUID> seen = new HashSet<>();
        for (AbstractClientPlayer player : level.players()) {
            UUID id = player.getUUID();
            seen.add(id);
            // Respawn/clone (fix 5): the player OBJECT is replaced while the
            // UUID persists. Treat it like a fresh sighting so the preserved morph
            // swaps in silently (no replay). The BOX is no longer refreshed here —
            // the unconditional reconcile at the end of this loop owns that now
            // (item B3); this branch keeps only the animation bookkeeping.
            AbstractClientPlayer prevInstance = LAST_INSTANCE.put(id, player);
            if (prevInstance != null && prevInstance != player) {
                FIRST_SEEN.put(id, clientTicks);
                LAST_SEEN.remove(id);
                DUMMIES.remove(id);
                TRANSITIONS.remove(id);
            }
            FIRST_SEEN.putIfAbsent(id, clientTicks);
            Optional<MorphVariant> current = Morph.STATE.get(player).current();
            Optional<MorphVariant> before = LAST_SEEN.put(id, current);
            // sortMorphs mode 3 (wave-9 item 6): the original floated the column
            // you just wore to the top the moment YOUR OWN morph packet arrived
            // (O:morph/client/core/PacketHandlerClient.java:135-163). This is the
            // same observation point — where the client already notices a
            // committed morph change (and where the morph sound fires).
            if (player == minecraft.player && !current.equals(before)) {
                current.ifPresent(v -> MorphSort.noteWorn(v.groupKey()));
            }
            if (before != null && !before.equals(current)) {
                if (clientTicks - FIRST_SEEN.get(id)
                        <= FIRST_SEEN_GRACE_TICKS) {
                    // Late login/tracking sync: adopt silently — no
                    // transformation, no sound (rejoin fix). The box is adopted by
                    // the reconcile at the end of this loop (item B3).
                    DUMMIES.remove(id);
                } else {
                    startTransition(minecraft, player, id, before, current);
                }
            }
            // Dummies are never level-ticked; advance their animation clocks
            // here so walk cycles and ambient animations run.
            Dummy dummy = DUMMIES.get(id);
            if (dummy != null) {
                advanceAnimation(player, dummy.entity());
            }
            Transition transition = TRANSITIONS.get(id);
            if (transition != null) {
                if (transition.fromDummy != null) {
                    advanceAnimation(player, transition.fromDummy);
                }
                // End the transition; the reconcile below then snaps the box on
                // this same tick (snap-at-end is preserved because the reconcile
                // SKIPS while a transition is still running) — item B3.
                if (clientTicks - transition.startTick
                        >= Morph.TRANSITION_TICKS) {
                    TRANSITIONS.remove(id);
                }
            }
            reconcileBox(player, id);
            healOfflinePlayerSkin(player, id);
        }
        DUMMIES.keySet().retainAll(seen);
        LAST_SEEN.keySet().retainAll(seen);
        FIRST_SEEN.keySet().retainAll(seen);
        TRANSITIONS.keySet().retainAll(seen);
        LAST_INSTANCE.keySet().retainAll(seen);
        // Review finding 11: selector/radial previews built while their target was
        // OFFLINE heal on the target's join even when NOBODY currently wears the
        // morph (healOfflinePlayerSkin only sees worn dummies, keyed by wearer).
        MorphSelector.healOfflinePreviews();
        MorphRadial.healOfflinePreviews();
    }

    /**
     * Heals an OFFLINE-resolved player morph once the target actually joins (wave 5
     * item E invalidation). Two caches would otherwise never notice:
     * {@code createLookup}'s supplier re-polls ONE captured future, so after it
     * completes with EMPTY textures it returns the default skin forever; and
     * {@link #DUMMIES} only rebuilds on a variant/level/instance change. So when a
     * dummy built WITHOUT a {@code PlayerInfo} sees one appear (false→true), drop it
     * (and the matching selector/radial preview entries, which have exactly the same
     * bug) so {@code dummyFor} rebuilds down the textured fast path.
     *
     * <p>Only on false→true — never the reverse, so a target logging out does not
     * throw away an already-resolved skin. SKIPPED during a transition: swapping the
     * dummy mid-morph could flip the slim/wide renderer and mismatch the interim rig.</p>
     */
    private static void healOfflinePlayerSkin(AbstractClientPlayer player, UUID id) {
        if (TRANSITIONS.containsKey(id)) {
            return;
        }
        Dummy dummy = DUMMIES.get(id);
        if (dummy == null
                || !(dummy.entity() instanceof MorphPlayerDummy playerDummy)
                || playerDummy.isFromPlayerInfo()) {
            return; // not an offline-built player morph
        }
        UUID targetId = dummy.variant().playerId().orElse(null);
        if (targetId == null || playerInfo(targetId) == null) {
            return; // target still offline to us
        }
        invalidatePlayerMorph(targetId);
    }

    /**
     * Drops every cached dummy/preview built for the given TARGET player morph, so
     * the next frame rebuilds it (wave 5 item E). Public because the selector and
     * radial preview caches must be purged too.
     *
     * <p>A wearer who is MID-TRANSITION is skipped (review finding 12): the
     * triggering wearer's guard in {@link #healOfflinePlayerSkin} covers only
     * itself, but this sweep removes entries for EVERY wearer of the target — and
     * yanking a transitioning wearer's dummy would rebuild it down the fast path
     * next frame, potentially flipping the slim/wide renderer under the interim
     * rig. The skipped wearer heals on a later tick, once its transition ends.</p>
     */
    public static void invalidatePlayerMorph(UUID targetId) {
        DUMMIES.entrySet().removeIf(e -> !TRANSITIONS.containsKey(e.getKey())
                && e.getValue().variant().isPlayer()
                && e.getValue().variant().playerId()
                        .filter(targetId::equals).isPresent());
        MorphSelector.invalidatePlayerPreview(targetId);
        MorphRadial.invalidatePlayerPreview(targetId);
    }

    /** True when the target UUID is currently in the tab list (i.e. online to this
     *  client) — the preview-heal sweeps key their false→true flip on this. */
    static boolean targetOnline(UUID targetId) {
        return targetId != null && playerInfo(targetId) != null;
    }

    /**
     * Asserts the collision-box invariant for one player, every tick (wave 5 item
     * B3) — the client half of the same "stop enumerating the events that recreate
     * a player" fix the server got.
     *
     * <p>An {@code EntityDimensions} override alone changes NOTHING:
     * {@code Entity.<init>} seeds {@code dimensions} from the {@code EntityType}
     * (player 0.6×1.8, eye 1.62) and {@code Entity.refreshDimensions()} is the only
     * thing that re-reads {@code getDimensions(pose)} into the AABB + eye-height —
     * which is exactly why CROUCHING appeared to fix it. The old code refreshed only
     * when the player INSTANCE changed or the seen variant changed, and on the first
     * sighting after a join both lookups return null, so neither branch ran and the
     * box stayed vanilla until a pose change.</p>
     *
     * <p>Idempotent and order-independent, so one call covers first join, late
     * attachment sync, respawn, dimension change, transition end, demorph, a broken
     * variant, and a remote player entering tracking range. SKIPPED while a
     * transition is running so the snap-at-end contract still holds; must run AFTER
     * {@code LAST_SEEN} is updated, since {@link #committedVariant} reads it.</p>
     */
    private static void reconcileBox(AbstractClientPlayer player, UUID id) {
        if (TRANSITIONS.containsKey(id)) {
            return; // mid-transformation: the box snaps only at the end
        }
        EntityDimensions want = player.getDimensions(player.getPose());
        // getEyeHeight() is the CAMERA accessor: PlayerEyeHeightMixin subtracts the
        // sneak dip for a local player crouching in a non-humanoid morph, while
        // refreshDimensions writes the RAW dimensions eye into the field. Compare
        // against the same dip-adjusted expectation (one shared predicate,
        // crouchCameraDip) or the two are a constant 0.1 apart and this "idempotent"
        // reconcile refreshes every tick forever (wave-5 review finding 4/5).
        float wantEye = want.eyeHeight() - crouchCameraDip(player);
        if (Math.abs(player.getBbWidth() - want.width()) > BOX_EPSILON
                || Math.abs(player.getBbHeight() - want.height()) > BOX_EPSILON
                || Math.abs(player.getEyeHeight() - wantEye) > BOX_EPSILON) {
            player.refreshDimensions();
        }
    }

    /**
     * The sneak camera dip currently applied to {@code player}'s
     * {@code getEyeHeight()} by {@code PlayerEyeHeightMixin.crouchDip}, or 0 —
     * THE single source of truth for both the mixin and {@link #reconcileBox}.
     * Non-zero only for the LOCAL player, crouching, in a committed NON-player
     * morph whose box is not a humanoid crouch (those get the real shrunk box;
     * player morphs get vanilla's own crouch eye).
     */
    public static float crouchCameraDip(Player player) {
        if (!(player instanceof net.minecraft.client.player.LocalPlayer local)
                || !local.isCrouching()) {
            return 0.0f;
        }
        Optional<MorphVariant> variant = committedVariant(local);
        if (variant.isEmpty() || variant.get().isPlayer()) {
            return 0.0f;
        }
        EntityDimensions dims =
                MorphEntities.dimensionsOf(variant.get(), local.level());
        if (dims != null && com.deds.morph.MorphCrouch.isHumanoidCrouch(dims)) {
            return 0.0f; // shrunk crouch box already lowered the eye
        }
        return SNEAK_CAMERA_DIP;
    }

    /**
     * Starts the 80-tick transformation out of the form that was VISIBLE
     * (the {@code before} state) into {@code current}, plays the morph
     * sound and builds the interim rig up front.
     *
     * <p>The old form's dummy is only reused when its type matches
     * {@code before} — a render frame between the state sync and this tick
     * may already have swapped the dummy map to the NEW form (that race
     * used to make transitions morph new→new, which showed the finished
     * mob in morphskin for the whole window).</p>
     */
    private static void startTransition(Minecraft minecraft,
            AbstractClientPlayer player, UUID id, Optional<MorphVariant> before,
            Optional<MorphVariant> current) {
        Dummy old = DUMMIES.remove(id);
        LivingEntity from = null;
        if (before.isPresent()) {
            from = old != null && old.variant().equals(before.get())
                    ? old.entity()
                    : createDummyEntity(before.get(), player.level());
            // from == null (unbuildable old type): morph out of the own
            // form instead — degraded but sane.
        }
        Transition transition = new Transition(from, before, clientTicks);
        transition.prevEye = from != null ? from.getEyeHeight()
                : DEFAULT_PLAYER_EYE;
        TRANSITIONS.put(id, transition);
        playMorphSound(minecraft, player);
        buildRig(minecraft, player, transition, current);
    }

    /**
     * Builds the interim rig at transition START (the original built its
     * model copies when the morph packet arrived): dummies are posed from
     * the player, both shared renderer models are posed from fresh states,
     * and the rig freezes prev-only parts in that morph-start pose. From
     * here on, rendering only needs per-frame live states.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void buildRig(Minecraft minecraft,
            AbstractClientPlayer player, Transition transition,
            Optional<MorphVariant> current) {
        try {
            LivingEntity prev = transition.fromDummy != null
                    ? transition.fromDummy : player;
            LivingEntity next = current.isPresent()
                    ? dummyFor(player, current.get()) : player;
            // Camera-glide endpoints (own form falls back to the vanilla eye).
            transition.nextEye = (next == null || next == player)
                    ? DEFAULT_PLAYER_EYE : next.getEyeHeight();
            if (next == null || prev == next) {
                transition.rigFailed = true;
                return;
            }
            if (prev != player) {
                pose(player, prev);
            }
            if (next != player) {
                pose(player, next);
            }
            EntityRenderDispatcher dispatcher =
                    minecraft.getEntityRenderDispatcher();
            EntityRenderState prevState = extractRaw(dispatcher, prev, 1.0f);
            EntityRenderState nextState = extractRaw(dispatcher, next, 1.0f);
            if (!(dispatcher.getRenderer(prev)
                            instanceof LivingEntityRenderer prevRenderer)
                    || !(dispatcher.getRenderer(next)
                            instanceof LivingEntityRenderer nextRenderer)
                    || !(prevState instanceof LivingEntityRenderState
                            prevLiving)
                    || !(nextState instanceof LivingEntityRenderState
                            nextLiving)) {
                transition.rigFailed = true;
                return;
            }
            // Pick baby vs adult by each form's OWN state, not the racy
            // shared getModel(); pose and build the rig from those exact
            // trees so the interim never captures the wrong variant.
            Model prevModel =
                    (Model) MorphModels.modelForState(prevRenderer, prevLiving);
            Model nextModel =
                    (Model) MorphModels.modelForState(nextRenderer, nextLiving);
            prevModel.setupAnim(prevState);
            nextModel.setupAnim(nextState);
            transition.rig = new MorphTransitionRig(prevModel, nextModel);
        } catch (Exception e) {
            transition.rigFailed = true;
            Deds.LOGGER.warn("[deds_morph] interim morph model failed for"
                    + " {}; falling back to plain form swap for this"
                    + " transition", player.getUUID(), e);
        }
    }

    // ------------------------------------------------------------------
    // render support (called by EntityRenderDispatcherMixin / MorphHands)
    // ------------------------------------------------------------------

    /** True while this class drives an extraction (mixin must not hook). */
    public static boolean isRawExtracting() {
        return rawExtraction;
    }

    /**
     * Runs any extraction with the morph swap switched off - for callers
     * that must see the REAL player state (26.3's per-frame player state,
     * versions/mc26.3 LevelExtractorPlayerStateMixin).
     */
    public static <T> T rawExtract(java.util.function.Supplier<T> extraction) {
        boolean prior = rawExtraction;
        rawExtraction = true;
        try {
            return extraction.get();
        } finally {
            rawExtraction = prior;
        }
    }

    /** Extracts a render state bypassing the morph swap mixin. */
    public static EntityRenderState extractRaw(
            EntityRenderDispatcher dispatcher, Entity entity,
            float partialTick) {
        boolean prior = rawExtraction;
        rawExtraction = true;
        try {
            return dispatcher.extractEntity(entity, partialTick);
        } finally {
            rawExtraction = prior;
        }
    }

    /**
     * The frame's render plan for this player, or null to render vanilla.
     * Also poses the involved dummies from the player's current and
     * previous-tick fields so the renderer interpolates them exactly like
     * the player.
     */
    public static Plan renderPlan(AbstractClientPlayer player,
            float partialTick) {
        // Spectators keep vanilla rendering: the dummy swap would show a
        // solid mob at an invisible spectator's position (critique wave-1
        // finding 1). Invisibility potions intentionally NOT handled — the
        // original's replacement path had the same jank.
        if (player.isSpectator()) {
            return null;
        }
        UUID id = player.getUUID();
        Transition transition = TRANSITIONS.get(id);
        if (transition != null && transition.fromDummy != null
                && transition.fromDummy.level() != player.level()) {
            TRANSITIONS.remove(id); // dimension hop mid-transition
            transition = null;
        }
        Optional<MorphVariant> current = effectiveState(player, id);
        LivingEntity target = current.isEmpty() ? null
                : dummyFor(player, current.get()); // null = own form
        if (transition == null) {
            return steady(player, target);
        }

        float progress = clientTicks - transition.startTick + partialTick;
        if (progress >= Morph.TRANSITION_TICKS) {
            TRANSITIONS.remove(id);
            return steady(player, target);
        }

        LivingEntity prev = transition.fromDummy != null
                ? transition.fromDummy : player;
        LivingEntity next = target != null ? target : player;
        if (prev == next) {
            TRANSITIONS.remove(id); // degenerate; belt and braces
            return steady(player, target);
        }
        if (prev != player) {
            pose(player, prev);
        }
        if (next != player) {
            pose(player, next);
        }
        // Exact original thresholds on morphProgress (+ partial tick).
        if (progress < 10.0f) {
            return new Plan(Phase.OVERLAY_IN, prev, null, progress,
                    progress / 10.0f, transition);
        }
        if (progress < 70.0f) {
            return new Plan(Phase.INTERIM, prev, next, progress, 0.0f,
                    transition);
        }
        return new Plan(Phase.OVERLAY_OUT, next, null, progress,
                Math.clamp(1.0f - (progress - 70.0f) / 10.0f, 0.0f, 1.0f),
                transition);
    }

    /**
     * The state to RENDER right now: when the synced attachment changed
     * but this tick's {@link #clientTick} has not processed it yet, keep
     * showing the last-processed form — the transition starts next tick
     * (kills the one-frame new-mob flash and the dummy-identity race).
     */
    private static Optional<MorphVariant> effectiveState(
            AbstractClientPlayer player, UUID id) {
        Optional<MorphVariant> processed = LAST_SEEN.get(id);
        return processed != null ? processed
                : Morph.STATE.get(player).current();
    }

    private static Plan steady(AbstractClientPlayer player,
            LivingEntity target) {
        if (target == null) {
            return null;
        }
        pose(player, target);
        return new Plan(Phase.STEADY, target, null, 0.0f, 0.0f, null);
    }

    /**
     * The first-person hand plan for the local player (original
     * HandRenderHandler phases): steady morphed = the morph's arm with its
     * real texture; 0-10 prev arm + overlay in; 10-70 interim morph arm in
     * morphskin; 70-80 next arm + overlay out. Null = vanilla hand.
     */
    public static HandPlan handPlan(AbstractClientPlayer player,
            float partialTick) {
        if (player == null || player.isSpectator()) {
            return null;
        }
        UUID id = player.getUUID();
        Optional<MorphVariant> current = effectiveState(player, id);
        LivingEntity target = current.isEmpty() ? null
                : dummyFor(player, current.get());
        Transition transition = TRANSITIONS.get(id);
        float progress = transition == null ? Float.MAX_VALUE
                : clientTicks - transition.startTick + partialTick;
        if (transition == null || progress >= Morph.TRANSITION_TICKS) {
            return target == null ? null
                    : new HandPlan(Phase.STEADY, null, target, 0.0f, 0.0f);
        }
        LivingEntity prev = transition.fromDummy; // null = own form
        if (progress < 10.0f) {
            return new HandPlan(Phase.OVERLAY_IN, prev, target, progress,
                    progress / 10.0f);
        }
        if (progress < 70.0f) {
            return new HandPlan(Phase.INTERIM, prev, target, progress,
                    0.0f);
        }
        return new HandPlan(Phase.OVERLAY_OUT, prev, target, progress,
                Math.clamp(1.0f - (progress - 70.0f) / 10.0f, 0.0f, 1.0f));
    }

    /** Stashes a morphskin overlay pass for this state's submit. */
    public static void stashOverlay(EntityRenderState state, float alpha) {
        FRAME_PAYLOADS.put(state, new OverlayPayload(alpha));
    }

    /**
     * INTERIM frames: pumps both SHARED renderer models with this frame's
     * live states, captures the interpolated frame from the transition's
     * rig and stashes it for this state's submit. Falls back to plain
     * prev/next rendering when the rig is unavailable.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void prepareInterim(AbstractClientPlayer player, Plan plan,
            EntityRenderState primaryState, EntityRenderState auxState,
            EntityRenderDispatcher dispatcher) {
        Transition transition = plan.transition();
        if (transition.rigFailed || transition.rig == null
                || !(primaryState instanceof LivingEntityRenderState prevLiving)
                || !(auxState instanceof LivingEntityRenderState nextLiving)) {
            return;
        }
        try {
            if (!(dispatcher.getRenderer(plan.primary())
                            instanceof LivingEntityRenderer prevRenderer)
                    || !(dispatcher.getRenderer(plan.aux())
                            instanceof LivingEntityRenderer nextRenderer)) {
                transition.rigFailed = true;
                return;
            }
            // Live pose pumping: the modern equivalent of the original's
            // per-frame offscreen force-renders. Pose the SAME trees the
            // rig was built from (correct baby/adult variant), not the racy
            // getModel(). Shared trees get re-posed by their own draws
            // afterwards, so this is side-effect free.
            ((Model) MorphModels.modelForState(prevRenderer, prevLiving))
                    .setupAnim(primaryState);
            ((Model) MorphModels.modelForState(nextRenderer, nextLiving))
                    .setupAnim(auxState);
            MorphTransitionRig.Frame frame =
                    transition.rig.capture(plan.progress());
            // Transform lerp: linear (t-10)/60, 0 at tick 10 → 1 at 70.
            // baseScale is the render-state scale; the MEASURED transform (wave 7
            // item 2) carries each renderer's full setupRotations [R|t] + its
            // scale() hook's scale AND translation + renderOffset — the interim
            // must reproduce all of them or it renders mis-anchored and snaps at
            // the boundaries (the phantom hook's translate was being discarded).
            float scaleMag = (plan.progress() - 10.0f) / 60.0f;
            float baseScale = prevLiving.scale
                    + (nextLiving.scale - prevLiving.scale) * scaleMag;
            MorphModels.RendererTransform transform = MorphModels.lerpTransform(
                    MorphModels.measure(prevRenderer, prevLiving),
                    MorphModels.measure(nextRenderer, nextLiving),
                    scaleMag);
            FRAME_PAYLOADS.put(primaryState, new InterimPayload(
                    transition.rig, frame, prevLiving.bodyRot, baseScale,
                    transform));
        } catch (Exception e) {
            transition.rigFailed = true;
            Deds.LOGGER.warn("[deds_morph] interim morph frame failed for"
                    + " {}; falling back to plain form swap for this"
                    + " transition", player.getUUID(), e);
        }
    }

    /** Claims (and forgets) the payload stashed for this submit. */
    public static Object takePayload(EntityRenderState state) {
        return FRAME_PAYLOADS.remove(state);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /** The (cached) dummy for a morph variant, or null if unbuildable. */
    private static LivingEntity dummyFor(AbstractClientPlayer player,
            MorphVariant variant) {
        if (BROKEN.contains(variant)) {
            return null;
        }
        UUID id = player.getUUID();
        Dummy dummy = DUMMIES.get(id);
        if (dummy == null || !dummy.variant().equals(variant)
                || dummy.entity().level() != player.level()) {
            LivingEntity living = createDummyEntity(variant, player.level());
            if (living == null) {
                BROKEN.add(variant);
                Deds.LOGGER.warn(
                        "[deds_morph] cannot build a '{}' to render — "
                        + "showing the vanilla player instead", variant);
                return null;
            }
            dummy = new Dummy(variant, living);
            DUMMIES.put(id, dummy);
        }
        return dummy.entity();
    }

    /**
     * Render-time crash isolation (critique wave-1 finding 6, promoted
     * after the zombie-horse extraction crash): a morph whose RENDERER
     * throws is quarantined — its type joins {@link #BROKEN} (vanilla
     * player rendering from then on), its dummy and any transition are
     * dropped — instead of taking the whole client down.
     */
    public static void quarantine(AbstractClientPlayer player,
            Throwable error) {
        UUID id = player.getUUID();
        TRANSITIONS.remove(id);
        DUMMIES.remove(id);
        Morph.STATE.get(player).current().ifPresent(variant -> {
            if (BROKEN.add(variant)) {
                Deds.LOGGER.error("[deds_morph] rendering morph {} failed;"
                        + " falling back to normal player rendering for this"
                        + " morph variant", variant, error);
            }
        });
    }

    /**
     * Builds a display-only living entity for the given morph variant (also
     * used by {@link MorphAcquisitions} for the suck-in effect's frozen victim).
     * Delegates to the shared {@link MorphEntities#create} builder so the SERVER
     * ability/hitbox system and this client renderer construct the identical
     * never-spawned mob. Never added to the world; null when the type is
     * unknown, not living, or its factory throws.
     */
    static LivingEntity createDummyEntity(MorphVariant variant, Level level) {
        if (variant.isPlayer() && variant.playerId().isPresent()) {
            return createPlayerDummy(variant, level);
        }
        return MorphEntities.create(variant, level);
    }

    /**
     * Builds a client PLAYER dummy for a player morph (§1.4): resolves the
     * target's {@link GameProfile}, an async skin lookup, and a
     * {@link MorphPlayerDummy} that polls that skin each frame. Client-only
     * (the shared {@link MorphEntities#create} returns null for a player variant),
     * so all {@code RemotePlayer}/{@code SkinManager} code stays here. Null when
     * off-client or the id is absent.
     */
    private static LivingEntity createPlayerDummy(MorphVariant variant, Level level) {
        if (!(level instanceof ClientLevel clientLevel)) {
            return null;
        }
        UUID uuid = variant.playerId().orElse(null);
        if (uuid == null) {
            return null;
        }
        String name = variant.playerName().orElse("");
        PlayerInfo info = playerInfo(uuid);
        if (info != null) {
            // FAST PATH — the target is in the tab list, so their profile already
            // carries texture properties (also correct on offline-mode/proxied
            // servers that inject textures there). requireSecure=false so an
            // unsigned skin still shows. Instant, no fetch.
            GameProfile profile = info.getProfile();
            Supplier<PlayerSkin> skin = Minecraft.getInstance().getSkinManager()
                    .createLookup(profile, false);
            return new MorphPlayerDummy(clientLevel, profile, skin, true);
        }
        // OFFLINE PATH (wave 5 item E). SkinManager NEVER fetches by UUID: its
        // cache key is sessionService.getPackedTextures(profile), a pure read of
        // profile.properties().get("textures"), so ANY hand-built GameProfile —
        // including the old UUIDUtil.createOfflineProfile(name), which also threw
        // away the real UUID for a name-hash — yields MinecraftProfileTextures.EMPTY
        // and therefore the DEFAULT skin, forever. PlayerSkinRenderCache DOES
        // resolve by UUID: its loader runs ResolvableProfile.resolveProfile on
        // Util.nonCriticalIoPool() (off-thread, no client hitch), which tries
        // getPlayerInfo(uuid) then ProfileResolver.fetchById(uuid), and feeds the
        // RESOLVED textured profile into SkinManager. This is what player heads use.
        // (Never call ProfileResolver.fetchById directly — it is a blocking
        // LoadingCache.getUnchecked over HTTP.)
        GameProfile profile = variant.playerProfile()
                .orElseGet(() -> new GameProfile(uuid, name));
        Supplier<PlayerSkinRenderCache.RenderInfo> lookup =
                Minecraft.getInstance().playerSkinRenderCache()
                        .createLookup(ResolvableProfile.createUnresolved(uuid));
        return new MorphPlayerDummy(clientLevel, profile,
                () -> lookup.get().playerSkin(), false);
    }

    /** The tracked {@link PlayerInfo} for a UUID, or null (no connection / not in
     *  the tab list — i.e. the target is offline to us). */
    private static PlayerInfo playerInfo(UUID uuid) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null ? connection.getPlayerInfo(uuid) : null;
    }

    // ------------------------------------------------------------------
    // hitbox / camera bridge (consumed by the common getDimensions mixin and
    // the client eye-height glide mixin)
    // ------------------------------------------------------------------

    /**
     * The client-side committed morph variant (snap-at-end semantics): during a
     * transition the PRE-transition form is returned so the collision box only
     * snaps when the transition ends, matching the server. Installed as
     * {@link MorphAbilities}' client resolver by {@code MorphClient}.
     */
    public static Optional<MorphVariant> committedVariant(Player player) {
        UUID id = player.getUUID();
        Transition transition = TRANSITIONS.get(id);
        if (transition != null) {
            return transition.fromVariant;
        }
        Optional<MorphVariant> seen = LAST_SEEN.get(id);
        return seen != null ? seen : Morph.STATE.get(player).current();
    }

    // ------------------------------------------------------------------
    // GUI player-preview swap (consumed by InventoryScreenMixin) — makes the
    // inventory (and any "render the player in a GUI") show the morph
    // ------------------------------------------------------------------

    /**
     * The entity a GUI should actually render for {@code entity}: the local
     * player's morph dummy when it is the morphed local player (so the survival
     * inventory shows the morph, with its adopted armour), else {@code entity}
     * unchanged. Consulted only by the {@code extractEntityInInventoryFollowsMouse}
     * mixin — our own selector/radial previews bypass that wrapper (they use the
     * {@code extractRenderState} invoker via {@link MorphPreview}), so their
     * own-form box correctly shows the player. Never throws — a broken morph
     * falls back to the passed entity.
     */
    public static LivingEntity guiPreviewSwap(LivingEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.player instanceof AbstractClientPlayer local)
                || entity != local) {
            return entity;
        }
        LivingEntity morph = guiMorphDummy(local);
        return morph != null ? morph : entity;
    }

    /**
     * The posed morph dummy for a local player to render in a GUI, or null when
     * unmorphed / unbuildable (render the player itself). Uses the same cached,
     * armour-adopting dummy the world render uses.
     */
    private static LivingEntity guiMorphDummy(AbstractClientPlayer player) {
        if (player.isSpectator()) {
            return null;
        }
        Optional<MorphVariant> current = effectiveState(player, player.getUUID());
        if (current.isEmpty()) {
            return null; // own form → render the player
        }
        LivingEntity dummy = dummyFor(player, current.get());
        if (dummy == null) {
            return null;
        }
        pose(player, dummy);
        return dummy;
    }

    /**
     * The glided first-person eye height for the local player during a morph
     * transition, or {@link Float#NaN} when there is no active transition (the
     * camera then uses the snapped eye-height field). {@code easeInQuad} over
     * {@code morphProgress/60} — the original's {@code pow(prog,2)} slide.
     */
    public static float eyeHeightOverride(AbstractClientPlayer player) {
        Transition transition = TRANSITIONS.get(player.getUUID());
        if (transition == null) {
            return Float.NaN;
        }
        float progress = clientTicks - transition.startTick;
        if (progress < 0.0f) {
            progress = 0.0f;
        }
        if (progress >= Morph.TRANSITION_TICKS) {
            return Float.NaN;
        }
        float frac = Math.min(progress / 60.0f, 1.0f);
        float eased = frac * frac;
        return transition.prevEye
                + (transition.nextEye - transition.prevEye) * eased;
    }

    /**
     * Copies the player's interpolation-relevant fields onto a dummy so the
     * renderer lerps it exactly like it would the player, and adopts the
     * player's equipment + the correct sneak/swim pose for the morph type.
     */
    private static void pose(AbstractClientPlayer player, LivingEntity dummy) {
        boolean aquatic = dummy.getType().builtInRegistryHolder()
                .is(EntityTypeTags.AQUATIC);
        boolean sneaking = player.isShiftKeyDown();
        // Humanoid (biped) morphs CROUCH like a player; everything else just
        // dips down a little while sneaking (fix 7). Aquatic never crouches.
        boolean humanoid = !aquatic && isHumanoidModel(dummy);
        double dip = (sneaking && !humanoid && !aquatic) ? SNEAK_DIP : 0.0;

        double px = player.getX();
        double py = player.getY() - dip;
        double pz = player.getZ();
        place(dummy, px, py, pz);
        dummy.xo = player.xo;
        dummy.yo = player.yo - dip;
        dummy.zo = player.zo;
        // Entity carries TWO prev-position families: xo/yo/zo (physics) and
        // xOld/yOld/zOld — and 26.2 extraction lerps state.x/y/z from the
        // *Old* family. Missing these left the dummy lerping from its
        // creation position every frame (user-reported flashing/floating,
        // 2026-07-21).
        dummy.xOld = player.xOld;
        dummy.yOld = player.yOld - dip;
        dummy.zOld = player.zOld;
        dummy.setYRot(player.getYRot());
        dummy.yRotO = player.yRotO;
        dummy.setXRot(player.getXRot());
        dummy.xRotO = player.xRotO;
        dummy.yBodyRot = player.yBodyRot;
        dummy.yBodyRotO = player.yBodyRotO;
        dummy.yHeadRot = player.yHeadRot;
        dummy.yHeadRotO = player.yHeadRotO;
        McCompat.copySwing(dummy, player);
        dummy.tickCount = player.tickCount;
        // Adopt the player's worn equipment so the morph renders armour + held
        // items (Minecraft skips any slot a mob's model can't show — acceptable
        // per the user).
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            dummy.setItemSlot(slot, player.getItemBySlot(slot));
        }
        // Enderman renders a CARRIED BLOCK from getCarriedBlock() (not a hand-item
        // layer), so mirror the player's main-hand BLOCK into it; a non-block held
        // item shows nothing (correct — endermen carry only blocks). Behavior §4.
        if (dummy instanceof EnderMan enderman) {
            ItemStack held = player.getMainHandItem();
            enderman.setCarriedBlock(held.getItem() instanceof BlockItem block
                    ? block.getBlock().defaultBlockState() : null);
        }
        // Mirror the player's KINEMATIC context onto the never-ticked dummy so
        // each mob's context-driven animations play (fly/fall/swim — Part 2).
        //
        // INVARIANT (wave 5 item A): never drive a morph animation from
        // client-authoritative kinematics. EntityType.PLAYER.trackDeltas() is
        // FALSE and RemotePlayer.aiStep never runs travel/gravity, so on every
        // OBSERVER's client a remote player's getDeltaMovement() is Vec3.ZERO
        // forever — anything keyed on it animates only on the wearer's own screen.
        // The per-tick POSITION DELTA is replicated (xOld/yOld/zOld are refreshed
        // every tick by Entity.setOldPosAndRot), so derive the velocity from it.
        // Vanilla extractors read the DUMMY's own velocity (BeeRenderer's
        // isOnGround wing-buzz gate, DolphinRenderer's isMoving undulation gate,
        // HumanoidRenderState.speedValue), so this is the only way to reach them.
        // RAW player fields here — no SNEAK_DIP; this is a velocity, not a position.
        dummy.setDeltaMovement(player.getX() - player.xOld,
                player.getY() - player.yOld,
                player.getZ() - player.zOld);
        dummy.setOnGround(player.onGround());
        dummy.setSprinting(player.isSprinting());
        // Mirror the player's on-fire state so the 3rd-person morph shows the
        // fire overlay (the render state's displayFireAnimation is driven by
        // isOnFire, which reads remainingFireTicks). isOnFire() carries the synced
        // on-fire flag even when the client's tick count is 0, so force >=1.
        dummy.setRemainingFireTicks(player.isOnFire()
                ? Math.max(player.getRemainingFireTicks(), 1) : 0);
        // Powder-snow freeze tint (A4): LivingEntityRenderer.extractRenderState
        // reads isFullyFrozen() off the DUMMY. ticksFrozen is synced data, so
        // mirroring it replicates to every observer. Unconditional = self-healing.
        dummy.setTicksFrozen(player.getTicksFrozen());
        // Elytra glide (A3): HumanoidRenderState.extractHumanoidRenderState reads
        // isFallFlying() off the DUMMY, so setPose(FALL_FLYING) alone never reached
        // it. isFallFlying() is getSharedFlag(7) (javap-verified), and the flag is
        // replicated — set it through the invoker (setSharedFlag is protected).
        // Unconditional, so it self-heals to false on landing.
        ((EntitySharedFlagsInvoker) dummy)
                .deds_morph$setSharedFlag(FALL_FLYING_FLAG, player.isFallFlying());
        // RED DAMAGE FLASH + DEATH ANIMATION (playtest bugs 2 & 3). Verified in
        // LivingEntityRenderer.extractRenderState:
        //   state.hasRedOverlay = (entity.hurtTime > 0 || entity.deathTime > 0)
        //   state.deathTime     = deathTime > 0 ? deathTime + partialTick : 0
        // and setupRotations rotates the body by -90° over sqrt(deathTime/20) for
        // the fall-over. The dummy is never ticked, so both stayed 0 — no flash,
        // no death topple. Assigned UNCONDITIONALLY every frame, so a cached dummy
        // also self-heals back to 0 on heal/respawn (no stuck hurt/dead pose).
        dummy.hurtTime = player.hurtTime;
        dummy.hurtDuration = player.hurtDuration;
        dummy.deathTime = player.deathTime;
        // Player morphs: borrow the wearer's cloak history so the cape physics
        // track this player (bug 1) — see MorphPlayerDummy.avatarState().
        if (dummy instanceof MorphPlayerDummy playerDummy) {
            playerDummy.setSource(player);
        }
        // NB: no squid branch here (wave 6 round 2). The old fix-J code wrote
        // RADIANS into xBodyRot — a DEGREES field (SquidRenderer applies
        // rotationDegrees) — pinning the squid upright forever, and stomped
        // xBodyRotO per frame. Squid body rots are per-TICK state, advanced by
        // the vanilla-exact aiStep replication in advanceAnimation; the original
        // also had NO look coupling (its copied pitch went into a field the squid
        // renderer never read).

        boolean inWater = player.isInWater();
        // SLEEPING (playtest bug 3, 2026-07-29). A morphed player in a bed just
        // stood on the pillow. The whole lie-down is driven, for EVERY living
        // renderer, by LivingEntityRenderer.submit + setupRotations reading two
        // render-state fields (javap, 26.2): hasPose(SLEEPING) picks the
        // YP(sleepDirectionToRotation(bedOrientation)) → ZP(getFlipDegrees()=90)
        // → YP(270) branch instead of the standing YP(180-bodyRot), and
        // submit()'s own preamble translates back along the bed direction by
        // (eyeHeight-0.1) so the head lands on the pillow — but ONLY when
        // bedOrientation != null, and extractRenderState fills bedOrientation
        // from the entity's getBedOrientation(), i.e. from its sleeping POS.
        // The never-ticked dummy has no sleeping pos, so a mob morph never even
        // entered the branch and a player morph entered it un-anchored (the
        // SPEC's "sleeping-morph mis-anchor" deviation). Mirroring the player's
        // sleeping pos onto the dummy reproduces the ENTIRE vanilla path,
        // eyeHeight included, with no state patching.
        //
        // Only HUMANOID morphs lie down — explicit user call ("it wouldn't make
        // sense for a chicken to lay on its back"); everything else keeps its
        // standing/aquatic pose on the bed. MorphHumanoid is the shared,
        // gametestable half; the HumanoidModel probe adds any modded biped that
        // shares no vanilla superclass.
        boolean sleeping = player.hasPose(Pose.SLEEPING);
        boolean humanoidBody = humanoid || com.deds.morph.MorphHumanoid
                .isHumanoid(dummy);
        // UNCONDITIONAL clear: dummies are cached and reused, so a stale
        // sleeping pos would keep a woken morph lying down forever.
        dummy.clearSleepingPos();
        // Pose: aquatic → swimming when the player is in water (upright; on land
        // it lays flat via the fish flop — markAquaticSwimming follows the
        // player's water state); humanoid + sneaking → real player crouch
        // (isCrouching() == hasPose(CROUCHING)); everything else stands (its Y
        // dip handles the sneak).
        if (sleeping && humanoidBody) {
            dummy.setSwimming(false);
            dummy.setShiftKeyDown(false);
            player.getSleepingPos().ifPresent(dummy::setSleepingPos);
            dummy.setPose(Pose.SLEEPING);
            // JITTER FIX (playtest, 2026-07-29: "humanoids sleeping jitter
            // around, like up and down really fast"). setSleepingPos writes
            // SLEEPING_POS_ID into the synched data, and
            // LivingEntity.onSyncedDataUpdated answers that key, ON A CLIENT
            // LEVEL ONLY, with `getSleepingPos().ifPresent(this::setPosToBed)`
            // — and setPosToBed is `setPos(x+0.5, y+0.6875, z+0.5)` (javap,
            // 26.2, LivingEntity offsets 5-38 / setPosToBed 0-31). Our dummies
            // live in the ClientLevel, so every frame this OVERWROTE the
            // position we had just mirrored off the player with the bed's
            // nominal coordinates, while xOld/yOld/zOld kept the player's real
            // ones. Extraction lerps state.y from yOld to y, so the morph swept
            // that gap over each tick's partial ticks and snapped back at the
            // tick boundary — a 20 Hz sawtooth whose amplitude is exactly the
            // distance between the bed's nominal sleep point and where the
            // sleeper actually settles (gravity drops a player from
            // bedY+0.6875 onto the bed's 0.5625-high collision top). The
            // per-frame clear+set cycle is what re-armed it every single frame.
            // Re-assert the mirrored position AFTER the data write: the morph
            // then renders exactly where vanilla would render the player.
            place(dummy, px, py, pz);
        } else if (dummy instanceof AbstractClientPlayer) {
            // Player morph (§1.4): mirror the morphing player's EXACT pose so
            // swimming (crawl), gliding (FALL_FLYING) and crouch all match.
            // (SLEEPING is handled above — a player dummy is always humanoid, so
            // it can only reach here awake.) A humanoid player dummy is never
            // AQUATIC-tagged, so the generic branches below would only ever
            // STAND/CROUCH it.
            Pose playerPose = player.getPose();
            dummy.setSwimming(playerPose == Pose.SWIMMING);
            dummy.setShiftKeyDown(sneaking);
            dummy.setPose(playerPose);
        } else if (aquatic) {
            dummy.setSwimming(inWater);
            dummy.setShiftKeyDown(false);
            dummy.setPose(inWater ? Pose.SWIMMING : Pose.STANDING);
        } else if (humanoid && sneaking) {
            dummy.setSwimming(false);
            dummy.setShiftKeyDown(true);
            dummy.setPose(Pose.CROUCHING);
        } else {
            dummy.setSwimming(false);
            dummy.setShiftKeyDown(false);
            dummy.setPose(Pose.STANDING);
        }
        // Diagnostic (wave 10): the render pipeline lerps state.x/y/z from the
        // *Old family to the live position, so this span IS the peak-to-peak
        // screen displacement the morph sweeps within one tick. For a
        // STATIONARY player it must be zero; anything else is jitter. Read by
        // the layer-3 harness's amplitude assertion.
        poseSpan = new double[] {
            dummy.getX() - dummy.xOld,
            dummy.getY() - dummy.yOld,
            dummy.getZ() - dummy.zOld,
        };
    }

    /**
     * Writes a dummy's world position, honouring the one type whose
     * {@code setPos} is not a plain write.
     *
     * <p>{@code Shulker.setPos} is OVERRIDDEN to snap to a block-grid centre
     * ({@code Mth.floor + 0.5}) on every call, so a shulker morph teleported
     * between blocks each frame. Bypass it with the raw position API so it
     * follows the smooth player coordinates (a never-ticked dummy skips the
     * attachment/peek recompute, so {@code setPos} was the only offender).</p>
     */
    private static void place(LivingEntity dummy, double x, double y, double z) {
        if (dummy instanceof Shulker) {
            dummy.setPosRaw(x, y, z);
            dummy.setBoundingBox(
                    dummy.getType().getDimensions().makeBoundingBox(x, y, z));
        } else {
            dummy.setPos(x, y, z);
        }
    }

    /** Last {@link #pose} call's render-interpolation span — see the assignment
     *  in {@code pose}. {@code {dx, dy, dz}}; all zero for a stationary
     *  player. Diagnostic seam for the layer-3 jitter assertion. */
    public static double[] poseSpan() {
        return poseSpan.clone();
    }

    private static double[] poseSpan = new double[3];

    /** Whether the morph dummy's renderer uses a {@link HumanoidModel} (biped) —
     *  the crouch-vs-dip discriminator (fix 7). */
    private static boolean isHumanoidModel(LivingEntity dummy) {
        try {
            return Minecraft.getInstance().getEntityRenderDispatcher()
                    .getRenderer(dummy) instanceof LivingEntityRenderer<?, ?, ?> r
                    && r.getModel() instanceof HumanoidModel;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Post-extract patch for every input a NEVER-TICKED dummy cannot carry itself
     * (wave 5 items A3/A4/D). Runs from {@code EntityRenderDispatcherMixin} right
     * after the dummy's render state is extracted.
     *
     * <ul>
     * <li>AQUATIC {@code isInWater} (2c): the fish renderer gates its out-of-water
     *     "flop onto the side" on {@code !isInWater} (verified
     *     {@code CodRenderer.setupRotations}), so in water the morph swims UPRIGHT
     *     and on land it lays flat. The dummy's own {@code isInWater()} is stale.</li>
     * <li>{@code isPassenger} (A3): cannot be faked on the dummy — it has no
     *     vehicle — so patch the extracted state; this is what folds a humanoid
     *     morph's legs when the wearer rides a horse/boat.</li>
     * <li>{@code isAutoSpinAttack} (A4): riptide spin, read off the dummy.</li>
     * <li>Named-mob nametag (D): see {@link #patchCustomName}.</li>
     * </ul>
     */
    public static void patchUntickedState(Player player, LivingEntity dummy,
            EntityRenderState state, float partialTick) {
        if (state instanceof LivingEntityRenderState living) {
            if (dummy.getType().builtInRegistryHolder()
                    .is(EntityTypeTags.AQUATIC)) {
                living.isInWater = player.isInWater();
            }
            living.isAutoSpinAttack = player.isAutoSpinAttack();
        }
        if (state instanceof HumanoidRenderState humanoid) {
            humanoid.isPassenger = player.isPassenger();
        }
        patchCustomName(player, dummy, state, partialTick);
    }

    /**
     * Named-mob nametag (wave 5 item D). A morph built from a name-tagged mob keeps
     * its {@code CustomName} through variant normalization, so the dummy has it via
     * the NBT load and we render the dummy through the MOB's own renderer — whose
     * {@code MobRenderer.shouldShowName} is (javap-verified)
     * {@code super.shouldShowName(..) && (shouldShowName() || (hasCustomName()
     * && mob == entityRenderDispatcher.crosshairPickEntity))}.
     *
     * <p>THE CATCH: {@code crosshairPickEntity} is whatever the observer is really
     * looking at — the morphed PLAYER, never our dummy — so that identity test
     * always fails and {@code extractNameTags} nulls {@code state.nameTag}. Recompute
     * it here with the PLAYER as the pick target, keeping vanilla's semantics: the
     * 64-block name range (and the 32-block range while sneaking/discrete, both from
     * {@code EntityRenderer.extractNameTags} / {@code LivingEntityRenderer.shouldShowName}),
     * an always-on tag only when the mob's own {@code CustomNameVisible} is set, and
     * never over the camera entity itself (vanilla suppresses your own tag).</p>
     *
     * <p>Unnamed mob morphs keep showing nothing; PLAYER morphs are untouched here
     * (they get the target's username from the dummy's GameProfile — wave 4 §1.5).</p>
     */
    private static void patchCustomName(Player player, LivingEntity dummy,
            EntityRenderState state, float partialTick) {
        if (dummy instanceof AbstractClientPlayer || !dummy.hasCustomName()) {
            return; // player morphs use the wave-4 path; unnamed mobs show nothing
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (player == minecraft.getCameraEntity()) {
            return; // never float your own name over your own camera
        }
        // The rest of vanilla's LivingEntityRenderer.shouldShowName teamless branch
        // (review finding 13), evaluated against the PLAYER observers actually see:
        // F1 hides every nametag, an invisible player must not be revealed by the
        // name, and a ridden vehicle shows none (rideable morphs make that live).
        if (minecraft.gui.hud.isHidden()
                || player.isInvisibleTo(minecraft.player)
                || player.isVehicle()) {
            return;
        }
        double maxSq = player.isDiscrete() ? DISCRETE_NAME_RANGE_SQ : NAME_RANGE_SQ;
        if (state.distanceToCameraSq >= maxSq) {
            return;
        }
        boolean lookedAt = minecraft.getEntityRenderDispatcher()
                .crosshairPickEntity == player;
        if (dummy.isCustomNameVisible() || lookedAt) {
            state.nameTag = dummy.getCustomName();
            // BLOCKER (review finding 1/2): the attachment must be set WITH the
            // name. Vanilla only writes nameTagAttachment inside extractNameTags'
            // shown branch — which never ran here (the dummy can't be the crosshair
            // pick) — the fresh-per-extraction state leaves it null, and
            // SubmitNodeCollection.submitNameTag opens with ifnonnull/return, so a
            // null Vec3 silently drew NOTHING in exactly the crosshair-look case.
            state.nameTagAttachment = dummy.getAttachments().getNullable(
                    EntityAttachment.NAME_TAG, 0, dummy.getYRot(partialTick));
        }
    }

    /**
     * Once-per-tick animation advance: converge the walk-cycle speed on the
     * player's and step the stride position, plus per-mob context animations the
     * dummy's frozen aiStep would otherwise drive (Part 2). The dummy never
     * level-ticks, so these are advanced here.
     */
    private static void advanceAnimation(AbstractClientPlayer player,
            LivingEntity dummy) {
        dummy.walkAnimation.update(player.walkAnimation.speed(), 1.0f, 1.0f);
        resetIdlePose(dummy);
        // Elytra wing animation (A3, corrected by review finding 3): vanilla
        // LivingEntity.tick() advances elytraAnimationState UNCONDITIONALLY —
        // its non-gliding branch is what lerps the wings BACK to the folded rest
        // pose (0.3/tick). Gating on isFallFlying latched the spread-glide angles
        // forever after landing (wings stuck wide open while walking) and left the
        // pre-first-glide pose at 0/0/0 instead of folded. tick() reads the DUMMY's
        // own isFallFlying flag, which pose() mirrors from the wearer every frame.
        dummy.elytraAnimationState.tick();
        boolean airborne = !player.onGround();
        if (dummy instanceof Bat bat) {
            // ALWAYS the flying pose: Bat.<init> calls setResting(isClientSide),
            // so a client dummy bat is created hanging upside-down. A morphed bat
            // must never hang — force flying + run the fly animation state (2a).
            bat.setResting(false);
            bat.flyAnimationState.startIfStopped(dummy.tickCount);
            bat.restAnimationState.stop();
        } else if (dummy instanceof Chicken chicken) {
            // Flap the wings when AIRBORNE — the chicken's flap fields are
            // advanced in aiStep (never run for the dummy), so drive them here
            // (2b). Vanilla Chicken.aiStep gates purely on onGround(); it never
            // consults the velocity for the flap. Keying on `airborne` (replicated
            // to every observer) instead of getDeltaMovement (which is ALWAYS zero
            // for a remote player — EntityType.PLAYER.trackDeltas() is false) is
            // what makes the flap visible to OTHER players, and it also restores
            // the vanilla behaviour of flapping while RISING (jump/bubble column).
            chicken.oFlap = chicken.flap;
            chicken.oFlapSpeed = chicken.flapSpeed;
            chicken.flapSpeed += (airborne ? 4.0f : -1.0f) * 0.3f;
            chicken.flapSpeed = Math.clamp(chicken.flapSpeed, 0.0f, 1.0f);
            if (airborne && chicken.flapping < 1.0f) {
                chicken.flapping = 1.0f;
            }
            chicken.flapping *= 0.9f;
            chicken.flap += chicken.flapping * 2.0f;
        } else if (dummy instanceof Parrot parrot) {
            // Parrot shares Chicken's public flap fields, but its `flapping`
            // damper is private — drive `flap` by `flapSpeed` directly so an
            // AIRBORNE parrot flaps its wings whenever off the ground (flying OR
            // falling — a parrot is a flyer), not only while descending (3).
            parrot.oFlap = parrot.flap;
            parrot.oFlapSpeed = parrot.flapSpeed;
            parrot.flapSpeed += (airborne ? 4.0f : -1.0f) * 0.3f;
            parrot.flapSpeed = Math.clamp(parrot.flapSpeed, 0.0f, 1.0f);
            parrot.flap += parrot.flapSpeed * 2.0f;
        } else if (dummy instanceof Squid squid) {
            // Vanilla-exact per-tick replication of Squid.aiStep's client-visible
            // parts (wave 6 round 2; every constant bytecode-verified). The
            // original had NO squid-specific code — it ticked the dummy's full
            // vanilla AI with the player's motion copied in; this is the 26.2
            // equivalent of exactly that. Velocity comes from the PLAYER's
            // per-tick position delta (replicated-input rule — a remote player's
            // getDeltaMovement() is always zero), the tentacle/roll RNG is
            // per-observer local (true in the original too), and yBodyRot is NOT
            // touched (pose()'s player copy = the original's yaw overwrite).
            SquidAccessor access = (SquidAccessor) squid;
            squid.xBodyRotO = squid.xBodyRot;
            squid.zBodyRotO = squid.zBodyRot;
            squid.oldTentacleMovement = squid.tentacleMovement;
            squid.oldTentacleAngle = squid.tentacleAngle;
            squid.tentacleMovement += access.deds_morph$tentacleSpeed();
            if (squid.tentacleMovement > TAU) {
                // Local wrap + reroll (the SERVER branch of vanilla's wrap — the
                // dummy never receives the entity-event-19 resync clients wait on).
                squid.tentacleMovement -= TAU;
                if (squid.getRandom().nextInt(10) == 0) {
                    access.deds_morph$setTentacleSpeed(
                            1.0f / (squid.getRandom().nextFloat() + 1.0f) * 0.2f);
                }
            }
            double vx = player.getX() - player.xOld;
            double vy = player.getY() - player.yOld;
            double vz = player.getZ() - player.zOld;
            float horiz = (float) Math.sqrt(vx * vx + vz * vz);
            if (player.isInWater()) {
                float rotateSpeed = access.deds_morph$rotateSpeed();
                if (squid.tentacleMovement < (float) Math.PI) {
                    // Power stroke: tentacles sweep, roll bursts to full speed
                    // late in the stroke, else decays fast.
                    float f = squid.tentacleMovement / (float) Math.PI;
                    squid.tentacleAngle = Mth.sin(f * f * (float) Math.PI)
                            * (float) Math.PI * 0.25f;
                    if (f > 0.75f) {
                        rotateSpeed = 1.0f;
                    } else {
                        rotateSpeed *= 0.8f;
                    }
                } else {
                    // Recovery: tentacles closed, roll decays slowly.
                    squid.tentacleAngle = 0.0f;
                    rotateSpeed *= 0.99f;
                }
                access.deds_morph$setRotateSpeed(rotateSpeed);
                // Yes: vanilla literally adds PI*rotateSpeed*1.5 to a DEGREES field.
                squid.zBodyRot += (float) Math.PI * rotateSpeed * 1.5f;
                squid.xBodyRot += (-((float) Mth.atan2(horiz, vy)) * 57.295776f
                        - squid.xBodyRot) * 0.1f;
            } else {
                squid.tentacleAngle = Mth.abs(Mth.sin(squid.tentacleMovement))
                        * (float) Math.PI * 0.25f;
                // The classic flop-flat-on-land.
                squid.xBodyRot += (-90.0f - squid.xBodyRot) * 0.02f;
            }
        }
    }

    /**
     * Resets any AI-idle pose a captured/loaded mob might carry so a morph never
     * shows an AI-assumed idle stance (§2). A never-ticked dummy has no AI to
     * enter these, but {@code load()} can restore a data-backed pose (a captured
     * sitting wolf), so re-assert per tick. Modded mobs default to their
     * constructor pose (almost always neutral standing); only mobs whose
     * constructor forces an idle pose (Bat — handled above) or whose pose is
     * synched-data-backed (tamable sit, fox sit) need an entry.
     */
    private static void resetIdlePose(LivingEntity dummy) {
        if (dummy instanceof TamableAnimal tamable) {
            tamable.setInSittingPose(false); // wolf/cat/parrot never sit as a morph
        }
        if (dummy instanceof Fox fox) {
            // Fox.setSleeping/setFaceplanted are private → rely on the fresh-dummy
            // awake default; only the public sit state is reset here.
            fox.setSitting(false);
        }
    }

    private static void playMorphSound(Minecraft minecraft, Player player) {
        // Volume/pitch 1.0 like the original's transition sound.
        minecraft.getSoundManager().playDelayed(new SimpleSoundInstance(
                MORPH_SOUND, SoundSource.PLAYERS, 1.0f, 1.0f,
                RandomSource.create(), false, 0,
                SoundInstance.Attenuation.LINEAR,
                player.getX(), player.getY(), player.getZ(), false), 0);
    }
}
