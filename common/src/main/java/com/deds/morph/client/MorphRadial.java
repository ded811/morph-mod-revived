package com.deds.morph.client;

import com.deds.api.client.ClientInput;
import com.deds.api.id.BId;
import com.deds.morph.Morph;
import com.deds.morph.MorphState;
import com.deds.morph.MorphVariant;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The favourites radial menu: hold grave ({@code `}) outside the selector to
 * fan the player's favourite morphs (plus the own form) around a ring, aim with
 * the mouse, release to morph into the highlighted entry — iChun Morph's radial
 * (original {@code renderRadialMenu} / {@code selectRadialMenu}).
 *
 * <p>Because this is a HUD overlay while no {@code Screen} is open, the mouse is
 * grabbed (there is no OS cursor) and its motion would normally turn the camera.
 * We route the raw grabbed-mouse delta through {@link ClientInput} — which also
 * suppresses the camera turn while the radial is up — so aiming moves the ring
 * cursor, not the view. The only thing that used to look like a stray "cursor"
 * was the vanilla crosshair HUD element (hidden by the loader glue, on Fabric
 * {@code MorphFabricClient}, while the radial shows via {@link #isShowing()}) plus our own aim marker (removed — the
 * original draws it only when {@code renderCrosshairInRadialMenu==1}, default
 * off).</p>
 *
 * <p>Recreation of iChun's Morph. The load-bearing behaviour is ring + aim +
 * release-to-morph; the backdrop is a simplified translucent darken (the
 * original's stencil-clipped doughnut has no {@code GuiGraphicsExtractor}
 * equivalent — spec §6). TODO(deds-api): lift — the HUD radial + grabbed-motion
 * aim are API v1.1 candidates.</p>
 */
public final class MorphRadial {

    private static final int OPEN_TIME = 3;         // ring open animation (ticks)
    private static final float RING_RADIUS = 80f;   // mob-slot ring radius
    private static final int PREVIEW_BOX = 34;
    private static final int PICKED_GROW = 6;
    private static final float SELECT_MAG = 0.6f;   // 0.8 * 0.75 (original)
    private static final float CONFIRM_MAG = 0.8f;  // release must exceed this
    private static final float AIM_MAG = 0.8f;      // original magAcceptance
    private static final int YELLOW = 0xFFFFFF00;
    private static final int GOLD = 0xFFFFAA00;
    private static final int WHITE = 0xFFFFFFFF;

    // Doughnut backdrop (fix 3): a dark translucent RING behind the mobs, not a
    // full-screen darken. Radii frame the 34px mob boxes seated on the 80px ring
    // (mobs span ~63..97). Approximated by horizontal scanline fills — no stencil
    // needed. Alpha: 0.4 idle / 0.6 while aiming (original), faded in by prog.
    private static final float DOUGHNUT_INNER = 50f;
    private static final float DOUGHNUT_OUTER = 112f;
    private static final float BACKDROP_ALPHA_IDLE = 0.4f;
    private static final float BACKDROP_ALPHA_AIM = 0.6f;

    private static boolean show;
    private static float snapYaw;
    private static float snapPitch;
    private static double aimX;
    private static double aimY;
    private static int openTimer;
    private static boolean graveWas;
    /** Ring slices; {@code null} = the own form at index 0. */
    private static final List<MorphVariant> RING = new ArrayList<>();

    private static final Map<MorphVariant, LivingEntity> PREVIEW = new HashMap<>();
    private static Level previewLevel;

    /** Drops the preview dummies (they hold the client world); on disconnect. */
    public static void clearPreviews() {
        PREVIEW.clear();
        previewLevel = null;
    }

    private MorphRadial() {
    }

    /** Whether the radial is currently showing (drives crosshair suppression). */
    public static boolean isShowing() {
        return show;
    }

    /**
     * Motion listener registered in {@code MorphClient}: accumulate the raw
     * grabbed-mouse delta into the aim cursor and clamp it to the unit circle
     * (original: {@code += dx/100}, {@code += dy/100}, then normalise if &gt;1).
     */
    public static void onMotion(double dx, double dy) {
        if (!show) {
            return;
        }
        aimX += dx / 100.0;
        aimY += dy / 100.0;
        double mag = Math.sqrt(aimX * aimX + aimY * aimY);
        if (mag > 1.0) {
            aimX /= mag;
            aimY /= mag;
        }
    }

    /**
     * Per-tick lifecycle, driven from {@code MorphSelector.clientTick}.
     *
     * @param stripOpen whether the selector strip is showing — the radial is
     *                  inert while it is (grave means "favourite" in the strip)
     */
    public static void clientTick(Minecraft mc, boolean stripOpen) {
        LocalPlayer player = mc.player;
        // allowMorphSelection (wave 10) closes the favourites ring too —
        // DEVIATION D10-4. The original gated only `selectorShow`
        // (O:client/core/TickHandlerClient.java:954-958) and left `radialShow`
        // (:930) open, so its own "Can you open the morph GUI? 0 = No" option
        // still let you morph from the ring. A pack that turns the morph GUI
        // off means the whole GUI.
        boolean allowed = MorphSelector.selectionAllowed();
        boolean canOpen = player != null && mc.gui.screen() == null
                && !stripOpen && allowed;
        boolean grave = McCompat.keyDown(mc,
                InputConstants.KEY_GRAVE);

        if (!show) {
            if (canOpen && grave && !graveWas) {
                openRadial(player);
            }
        } else if (player == null || mc.gui.screen() != null || stripOpen
                || !allowed) {
            closeRadial(); // a screen opened / the strip took over — abandon
        } else {
            // hard camera lock to the snapshot (belt-and-suspenders with the
            // ClientInput camera-turn suppression).
            player.setYRot(snapYaw);
            player.yRotO = snapYaw;
            player.setXRot(snapPitch);
            player.xRotO = snapPitch;
            if (!grave) {
                confirmRadial(player); // release = pick
            }
        }

        graveWas = grave;
        if (openTimer > 0) {
            openTimer--;
        }
    }

    private static void openRadial(LocalPlayer player) {
        MorphState state = Morph.STATE.get(player);
        RING.clear();
        RING.add(null); // own form at ring[0] (always a favourite in the original)
        // Every favourite variant, in toggle order (spec §6: own form + all
        // favourites). favourites() is the authoritative starred set.
        RING.addAll(state.favourites());
        snapYaw = player.getYRot();
        snapPitch = player.getXRot();
        aimX = 0.0;
        aimY = 0.0;
        openTimer = OPEN_TIME;
        show = true;
        ClientInput.setSuppressCameraTurn(true);
    }

    private static void closeRadial() {
        show = false;
        ClientInput.setSuppressCameraTurn(false);
    }

    private static void confirmRadial(LocalPlayer player) {
        int slice = selectedSlice();
        double mag = Math.sqrt(aimX * aimX + aimY * aimY);
        if (slice >= 0 && mag > CONFIRM_MAG) {
            MorphVariant picked = RING.get(slice);
            Optional<MorphVariant> target = Optional.ofNullable(picked);
            if (!Morph.STATE.get(player).current().equals(target)) {
                Morph.SELECT.sendToServer(target);
            }
        }
        closeRadial();
    }

    /** The slice under the aim cursor, or -1 if the cursor is near centre. */
    private static int selectedSlice() {
        int n = RING.size();
        double mag = Math.sqrt(aimX * aimX + aimY * aimY);
        if (n == 0 || mag < SELECT_MAG) {
            return -1;
        }
        double aimAngle = Math.toDegrees(Math.atan2(aimY, aimX));
        double leeway = 360.0 / n;
        for (int i = 0; i < n; i++) {
            double centre = Math.toDegrees(2.0 * Math.PI * i / n - Math.PI / 2.0);
            if (Math.abs(angleDelta(aimAngle, centre)) <= leeway / 2.0) {
                return i;
            }
        }
        return -1;
    }

    private static double angleDelta(double a, double b) {
        double d = (a - b) % 360.0;
        if (d > 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ------------------------------------------------------------------
    // HUD render (the second HUD element the loader glue registers; on
    // Fabric, MorphFabricClient)
    // ------------------------------------------------------------------

    public static void render(GuiGraphicsExtractor graphics,
            DeltaTracker deltaTracker) {
        if (!show) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.screen() != null) {
            return;
        }
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int cx = width / 2;
        int cy = (height + 32) / 2;
        int n = RING.size();
        Font font = mc.font;
        MorphState state = Morph.STATE.get(player);
        float partial = deltaTracker.getGameTimeDeltaPartialTick(false);

        // Open animation (fix 3): the ring scales/fades in over OPEN_TIME ticks.
        // prog 0→1; ring radius eases with sqrt(prog) (original 80*pow(prog,.5)).
        float prog = clamp01((OPEN_TIME - (openTimer - partial)) / (float) OPEN_TIME);
        float ringRadius = RING_RADIUS * (float) Math.sqrt(prog);

        double mag = Math.sqrt(aimX * aimX + aimY * aimY);
        boolean aiming = mag > AIM_MAG;

        // Dark translucent DOUGHNUT behind the mobs (fix 3) — a real wheel, not a
        // full-screen darken. Radii + alpha grow with prog (fade/scale in).
        float alpha = (aiming ? BACKDROP_ALPHA_AIM : BACKDROP_ALPHA_IDLE) * prog;
        int backdrop = ((int) (alpha * 255f) & 0xFF) << 24;
        drawDoughnut(graphics, cx, cy, DOUGHNUT_INNER * prog,
                DOUGHNUT_OUTER * prog, backdrop);

        int slice = selectedSlice();
        for (int i = 0; i < n; i++) {
            double angle = 2.0 * Math.PI * i / n - Math.PI / 2.0;
            int px = cx + (int) Math.round(Math.cos(angle) * ringRadius);
            int py = cy + (int) Math.round(Math.sin(angle) * ringRadius);
            boolean picked = i == slice;
            int box = PREVIEW_BOX + (picked ? PICKED_GROW : 0);

            MorphVariant variant = RING.get(i);
            // Own form (null) renders the real player — reliable (the vanilla
            // inventory renders the player the same way); MorphPreview fixes the
            // facing so it does not spin. Favourites render their dummy.
            LivingEntity preview = variant == null
                    ? player : previewFor(mc, variant);
            if (preview != null) {
                renderPreview(graphics, preview, player,
                        px - box / 2, py - box / 2, box);
            }
            // Name-only fallback: always draw the name so a slot is never wholly
            // invisible even when the preview could not be built.
            Component name = variant == null
                    ? player.getName()
                    : (variant.isPlayer()
                            ? Component.literal(variant.playerName().orElse("Player"))
                            : nameOf(variant.type()));
            boolean worn = variant == null
                    ? state.current().isEmpty()
                    : state.current().equals(Optional.of(variant));
            int colour = picked ? YELLOW : (worn ? GOLD : WHITE);
            int nameW = font.width(name.getString());
            graphics.text(font, name, px - nameW / 2, py + box / 2 + 1,
                    colour, true);
        }
        // No aim crosshair: the original draws it only when
        // renderCrosshairInRadialMenu==1 (default 0 = off). The vanilla
        // crosshair is suppressed by the loader glue (MorphFabricClient on
        // Fabric) while the radial shows.
    }

    /**
     * Draws a filled dark ring (annulus) via horizontal scanline fills — the
     * stencil-free substitute for the original's stencil-clipped doughnut
     * (26.2's GuiGraphicsExtractor has no stencil). One 1px-tall {@code fill} per
     * row: outside the inner radius the row is a single band; inside it splits
     * into a left + right band leaving the centre hole.
     */
    private static void drawDoughnut(GuiGraphicsExtractor graphics, int cx,
            int cy, float inner, float outer, int colour) {
        if (outer <= 0f || (colour >>> 24) == 0) {
            return;
        }
        int ro = Math.round(outer);
        float innerSq = inner * inner;
        float outerSq = outer * outer;
        for (int dy = -ro; dy <= ro; dy++) {
            float outerDx = (float) Math.sqrt(Math.max(0f, outerSq - dy * dy));
            int y = cy + dy;
            if (Math.abs(dy) < inner) {
                float innerDx = (float) Math.sqrt(Math.max(0f, innerSq - dy * dy));
                graphics.fill(cx - Math.round(outerDx), y,
                        cx - Math.round(innerDx), y + 1, colour);
                graphics.fill(cx + Math.round(innerDx), y,
                        cx + Math.round(outerDx), y + 1, colour);
            } else {
                graphics.fill(cx - Math.round(outerDx), y,
                        cx + Math.round(outerDx), y + 1, colour);
            }
        }
    }

    /** Static posed preview: fixed three-quarter FRONT pose, per-entity scale
     *  (Part 1, shared with the selector via MorphPreview). */
    private static void renderPreview(GuiGraphicsExtractor graphics,
            LivingEntity entity, LocalPlayer player, int left, int top, int box) {
        if (entity != player) {
            prepPreview(entity, player);
        }
        // Own form seats/scales as a full player, morph-independent (1b).
        float dispW = entity == player
                ? MorphPreview.PLAYER_WIDTH : entity.getBbWidth();
        float dispH = entity == player
                ? MorphPreview.PLAYER_HEIGHT : entity.getBbHeight();
        // Enlarge the picked slice by scaling up from the base fit (the original
        // scaled the selected preview larger).
        int scale = MorphPreview.fitScale(Math.max(dispW, dispH)) * box / PREVIEW_BOX;
        MorphPreview.render(graphics, entity, left, top, left + box, top + box,
                scale, dispW, dispH, 0.0f);
    }

    /** Positions the never-ticked preview dummy at the player (a loaded, valid
     *  render position) while its un-advanced walk animation keeps limbs idle. */
    private static void prepPreview(LivingEntity entity, LocalPlayer player) {
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        entity.setPos(x, y, z);
        entity.xo = x;
        entity.yo = y;
        entity.zo = z;
        entity.xOld = x;
        entity.yOld = y;
        entity.zOld = z;
    }

    /**
     * Drops the cached preview for a player morph once that target comes online
     * (wave 5 item E) — same stale-default-skin bug as the selector's cache.
     */
    static void invalidatePlayerPreview(java.util.UUID targetId) {
        PREVIEW.keySet().removeIf(v -> v.isPlayer()
                && v.playerId().filter(targetId::equals).isPresent());
    }

    /** Per-tick sweep (wave-5 review finding 11) — same offline-built-preview heal
     *  as {@code MorphSelector.healOfflinePreviews}; see there for the mechanism. */
    static void healOfflinePreviews() {
        PREVIEW.entrySet().removeIf(e -> e.getKey().isPlayer()
                && e.getValue() instanceof MorphPlayerDummy dummy
                && !dummy.isFromPlayerInfo()
                && MorphDummies.targetOnline(e.getKey().playerId().orElse(null)));
    }

    private static LivingEntity previewFor(Minecraft mc, MorphVariant variant) {
        if (mc.level != previewLevel) {
            PREVIEW.clear();
            previewLevel = mc.level;
        }
        if (PREVIEW.containsKey(variant)) {
            return PREVIEW.get(variant);
        }
        // Build from the full variant (type + NBT) so favourites of a specific
        // variant preview correctly; may be null (name-only fallback).
        LivingEntity preview = mc.level == null ? null
                : MorphDummies.createDummyEntity(variant, mc.level);
        PREVIEW.put(variant, preview);
        return preview;
    }

    private static Component nameOf(BId id) {
        Identifier ident = Identifier.fromNamespaceAndPath(
                id.namespace(), id.path());
        if (BuiltInRegistries.ENTITY_TYPE.containsKey(ident)) {
            return BuiltInRegistries.ENTITY_TYPE.getValue(ident).getDescription();
        }
        return Component.literal(id.toString());
    }
}
