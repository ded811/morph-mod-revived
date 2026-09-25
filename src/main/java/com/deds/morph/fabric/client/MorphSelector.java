package com.deds.morph.fabric.client;

import com.deds.api.id.BId;
import com.deds.morph.Morph;
import com.deds.morph.MorphAbility;
import com.deds.morph.MorphSort;
import com.deds.morph.MorphState;
import com.deds.morph.MorphVariant;
import com.deds.morph.api.Ability;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The morph selector: iChun Morph's in-game morph picker, rebuilt as a HUD
 * overlay (NOT a {@code Screen} — the player keeps walking/looking while it is
 * open, exactly like the original which drew during the render tick while no
 * screen was up). A left-edge scrollable column of 42&times;42 boxes: the
 * player's own form at row 0, then one box per acquired <b>group</b> — a mob
 * type, or a distinct target player (wave 4). Groups come from
 * {@link MorphState#groupedByKey()}; the selected row expands <b>horizontally</b>
 * to show that group's variants ({@link MorphState#variantsOfKey}). Each box shows
 * a STATIC posed entity preview, the group's name (mob name, or the target
 * player's username — colour-coded), a top-right favourite star, and the morph's
 * derived ability icons.
 *
 * <p>Recreation of iChun's Morph (original mod by iChun); see
 * docs/specs/morph/wave2/redesign-selector-look.md. Vertical axis =
 * {@code selected} (group/type), horizontal axis = {@code variantIndex} (variant
 * within the selected group). {@code SHIFT+[} / {@code SHIFT+]} move the variant
 * axis; plain {@code [} / {@code ]} move the group axis. TODO(deds-api): lift —
 * the HUD-overlay + raw-input + entity-preview surface are API v1.1 candidates.</p>
 */
@Environment(EnvType.CLIENT)
public final class MorphSelector {

    // --- layout (original units: GUI-scaled px, box = 42) ---
    private static final int BOX = 42;
    private static final int BAND = 5 * BOX; // 210: a 5-box tall centred band
    private static final int SLIDE = 52;     // 42 + 10 margin (open slide-in)

    // --- timing (original constants) ---
    private static final int SELECTOR_SHOW_TIME = 10;
    private static final int SCROLL_TIME = 3;

    // --- textures (staged originals; 64x64 boxes, 16x16 star, 32x32 icons) ---
    private static final Identifier TEX_UNSELECTED = gui("unselected");
    private static final Identifier TEX_UNSELECTED_SIDE = gui("unselected_side");
    private static final Identifier TEX_SELECTED = gui("selected");
    private static final Identifier TEX_FAVOURITE = gui("favourite");

    // --- name colours (spec §3: yellow=highlight, gold=worn, white=other) ---
    private static final int YELLOW = 0xFFFFFF00;
    private static final int GOLD = 0xFFFFAA00;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int SHADOW = 0x99000000; // black drop-shadow (α0.6)

    // --- favourite star (spec §4): 9 px, top-right, 16x16 source ---
    private static final int STAR = 9;
    private static final float STAR_DX = 29.5f; // boxLeft + 29.5
    private static final float STAR_DY = 2.5f;  // boxTop  + 2.5

    // --- ability icons (spec §5): 12 px, 32x32 source, laid out in a U around
    //     the preview (left edge top→bottom, bottom edge, up the right edge) so
    //     they never cover the mob's face. Slots are (dx,dy) from the box top-left;
    //     the top row is left free for the name + top-right favourite star. ---
    private static final int ICON = 12;
    private static final int ICON_TEX = 32;    // 32x32 source pngs
    private static final int[][] ICON_SLOTS = {
        {0, 1}, {0, 14}, {0, 27},   // left edge, top → bottom
        {15, 30},                   // bottom centre
        {30, 27}, {30, 14},         // right edge, bottom → up (clears the star)
    };

    // --- ability-icon OVERFLOW scroll (wave 10; the user's "just rotate out
    //     the icons or something if theirs more than 6", and iChun's own
    //     design at O:client/core/TickHandlerClient.java:1631-1762). Engages
    //     ONLY past ICON_SLOTS.length icons; at or below it the U is drawn
    //     exactly as before, with no clock, no clipping and identical pixels.
    //
    //     SCROLL_PATH is the U extended by one slot-spacing at each end, so an
    //     icon slides IN above the top-left corner and OUT above the top-right
    //     one instead of popping into existence mid-box. Index 0 is the entry
    //     (path coordinate s = -1); indices 1..6 are ICON_SLOTS (s = 0..5);
    //     index 7 (s = 6) continues up the right edge PAST the favourite star
    //     (the star is drawn after the icons, so it stays on top during that
    //     one transit slot); index 8 (s = 7) is the exit, clipped away. ---
    private static final int[][] SCROLL_PATH = {
        {0, -12},                                   // s = -1  entry (clipped)
        {0, 1}, {0, 14}, {0, 27},                   // s = 0,1,2
        {15, 30},                                   // s = 3
        {30, 27}, {30, 14},                         // s = 4,5
        {30, 1},                                    // s = 6   transit
        {30, -12},                                  // s = 7   exit (clipped)
    };
    /** Lowest/highest path coordinate an icon is drawn at (exclusive). */
    private static final float SCROLL_FIRST = -1.0f;
    private static final float SCROLL_LAST = 7.0f;
    /** The original's cadence: one icon-slot every 30 client ticks (1.5 s) —
     *  {@code O:TickHandlerClient.java:1717-1723} offsets by
     *  {@code (size + 1) * (round + renderTick) / 30} over a 13 px pitch. */
    private static final int SCROLL_TICKS_PER_ICON = 30;

    /** Client-tick clock for the overflow scroll (the original's
     *  {@code abilityScroll}, {@code O:TickHandlerClient.java:177}). */
    private static int abilityScroll;

    /** This frame's partial tick, published by {@link #render} for the icon
     *  scroll. The original folded its {@code renderTick} into the same offset
     *  ({@code :1719}) so the motion is smooth between ticks rather than
     *  stepping 20 times a second; wall time is deliberately NOT used. */
    private static float framePartial;

    // Preview pose + scale live in MorphPreview (the ORIGINAL per-entity fit +
    // the fixed three-quarter FRONT pose, shared with the radial).

    // --- selector state (client-only) ---
    private static boolean open;
    /** 0 = own form; i &gt; 0 = the (i-1)-th acquired TYPE group. */
    private static int selected;
    private static int selectedPrev;
    /** Variant axis: index within the currently-selected type group. */
    private static int variantIndex;
    private static int variantIndexPrev;

    // --- animation timers (decremented each client tick) ---
    private static int selectorTimer;   // open slide-in, 10 -> 0
    private static int scrollTimer;     // vertical tween, 3 -> 0
    private static int scrollTimerHori; // horizontal tween, 3 -> 0

    // --- raw-poll edge state (mouse only; keyboard actions are rebindable
    //     KeyMappings dispatched via ClientKeys — see MorphClient) ---
    private static boolean lmbWas;
    private static boolean rmbWas;

    // --- entity preview cache (one LivingEntity per variant; null = name-only) ---
    private static final Map<MorphVariant, LivingEntity> PREVIEW = new HashMap<>();
    private static Level previewLevel;

    private MorphSelector() {
    }

    private static Identifier gui(String name) {
        return Identifier.fromNamespaceAndPath(
                Morph.MOD_ID, "textures/gui/" + name + ".png");
    }

    private static Identifier icon(MorphAbility ability) {
        return Identifier.fromNamespaceAndPath(
                Morph.MOD_ID, "textures/icon/" + ability.id() + ".png");
    }

    // ------------------------------------------------------------------
    // grouped model (spec §0): vertical = type group, horizontal = variant
    // ------------------------------------------------------------------

    /**
     * The acquired GROUPS in first-acquisition order (vertical rows 1..n), keyed
     * by {@link MorphVariant#groupKey()} — one column per mob type AND per distinct
     * target player (§1.6). {@code Object} keys: a {@link BId} for a mob, a
     * {@code "player:<uuid>"} string for a player morph.
     */
    private static List<Object> groupKeys(MorphState state) {
        return new ArrayList<>(sortedGroups(state).keySet());
    }

    /**
     * The columns in {@code sortMorphs} order (wave-9 item 6). The strip's
     * ordering is a pure function of the state + the config mode + mode 3's
     * "most recently worn" key, all resolved by {@link MorphSort}; the column
     * LABEL is what mode 1/2 sort on (the original sorted on
     * {@code getEntityName()}, so a raw registry-id sort would read wrong).
     */
    private static java.util.LinkedHashMap<Object, List<MorphVariant>>
            sortedGroups(MorphState state) {
        int mode = Morph.CONFIG != null ? Morph.CONFIG.get().sortMorphs() : 0;
        return MorphSort.sorted(state, mode,
                key -> groupLabel(state, key), MorphSort.recentlyWorn());
    }

    /** The displayed name of a column, used as mode 1/2's sort key. */
    private static String groupLabel(MorphState state, Object groupKey) {
        for (MorphVariant variant : state.acquired()) {
            if (variant.groupKey().equals(groupKey)) {
                return columnLabel(variant).getString();
            }
        }
        return String.valueOf(groupKey);
    }

    /** The selected group's variant list, or empty for the own form / no groups. */
    private static List<MorphVariant> selectedVariants(MorphState state) {
        java.util.LinkedHashMap<Object, List<MorphVariant>> groups =
                sortedGroups(state);
        List<Object> keys = new ArrayList<>(groups.keySet());
        if (selected <= 0 || selected > keys.size()) {
            return List.of();
        }
        // Mode 2 sorts WITHIN a column, so take the ordered list from the sorted
        // map rather than re-deriving it from acquisition order.
        return groups.get(keys.get(selected - 1));
    }

    // ------------------------------------------------------------------
    // browse keys (rebindable; ClientKeys dispatches these on the main thread)
    // ------------------------------------------------------------------

    /** {@code [} default: open the strip, else move up (group / variant). */
    public static void prev() {
        browse(-1);
    }

    /** {@code ]} default: open the strip, else move down (group / variant). */
    public static void next() {
        browse(1);
    }

    // ------------------------------------------------------------------
    // rebindable action keys (registered via ClientKeys in MorphClient, so they
    // appear in the "Morph Mod Revived" controls category and rebind cleanly). Each
    // fires once per press (ClientKeys dispatches via consumeClick) and no-ops
    // unless the strip is open. LMB/RMB remain additional raw mouse triggers.
    // ------------------------------------------------------------------

    /** {@code Enter} default: wear the highlighted morph (or own form). */
    public static void selectKey() {
        whenOpen(MorphSelector::confirm);
    }

    /** {@code Esc} default: close the strip without changing morph. */
    public static void cancelKey() {
        if (open) {
            close();
        }
    }

    /** {@code Delete} default: drop the highlighted acquired morph. */
    public static void removeKey() {
        whenOpen(MorphSelector::removeHighlighted);
    }

    /** {@code grave} default: toggle the highlighted morph's favourite star. */
    public static void favouriteKey() {
        whenOpen(MorphSelector::favouriteHighlighted);
    }

    /** Runs {@code action} with the local player iff the strip is open + usable. */
    private static void whenOpen(java.util.function.Consumer<LocalPlayer> action) {
        if (!open) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.screen() != null) {
            return;
        }
        action.accept(player);
    }

    private static void browse(int delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.screen() != null) {
            return;
        }
        if (!open) {
            if (!selectionAllowed()) {
                return; // allowMorphSelection = 0: the strip never opens
            }
            openStrip(player);
            return;
        }
        // SHIFT selects the variant (horizontal) axis; plain = vertical.
        if (shiftDown(mc)) {
            moveVariant(player, delta);
        } else {
            moveSelected(player, delta);
        }
    }

    private static void openStrip(LocalPlayer player) {
        open = true;
        selectorTimer = SELECTOR_SHOW_TIME;
        MorphState state = Morph.STATE.get(player);
        int[] pos = currentPosition(state);
        selected = pos[0];
        selectedPrev = selected;
        variantIndex = pos[1];
        variantIndexPrev = variantIndex;
        scrollTimer = 0;
        scrollTimerHori = 0;
    }

    private static void moveSelected(LocalPlayer player, int delta) {
        MorphState state = Morph.STATE.get(player);
        int rows = groupKeys(state).size() + 1;
        selectedPrev = selected;
        selected = Math.floorMod(selected + delta, rows);
        // Moving vertically resets the variant axis to 0 (original L512/562).
        variantIndex = 0;
        variantIndexPrev = 0;
        scrollTimer = SCROLL_TIME;
        scrollTimerHori = 0;
    }

    private static void moveVariant(LocalPlayer player, int delta) {
        MorphState state = Morph.STATE.get(player);
        int size = selectedVariants(state).size();
        if (size <= 1) {
            return; // single-variant group: nothing to scroll horizontally
        }
        variantIndexPrev = variantIndex;
        variantIndex = Math.floorMod(variantIndex + delta, size);
        scrollTimerHori = SCROLL_TIME;
    }

    /** The (group row, variant column) of the currently-worn morph, by group key
     *  (so a player morph lands in its OWN column, not a shared player row). */
    private static int[] currentPosition(MorphState state) {
        if (state.current().isEmpty()) {
            return new int[] {0, 0};
        }
        MorphVariant worn = state.current().get();
        List<Object> keys = groupKeys(state);
        int row = keys.indexOf(worn.groupKey());
        if (row < 0) {
            return new int[] {0, 0};
        }
        int col = state.variantsOfKey(worn.groupKey()).indexOf(worn);
        return new int[] {row + 1, Math.max(0, col)};
    }

    // ------------------------------------------------------------------
    // mouse wheel (via ClientInput; cancels the vanilla hotbar scroll)
    // ------------------------------------------------------------------

    /** Scroll listener registered in {@code MorphClient}. Consumes while open. */
    public static boolean onScroll(double horizontal, double vertical) {
        if (!open) {
            return false; // let the hotbar scroll normally
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.screen() != null) {
            return false;
        }
        int dir = vertical > 0 ? -1 : (vertical < 0 ? 1 : 0); // up = prev
        if (dir != 0) {
            if (shiftDown(mc)) {
                moveVariant(player, dir);
            } else {
                moveSelected(player, dir);
            }
        }
        return true; // consume — no hotbar slot change while the strip is open
    }

    // ------------------------------------------------------------------
    // per-tick input + upkeep
    // ------------------------------------------------------------------

    /**
     * True when the config lets the morph GUI open at all
     * ({@code allowMorphSelection}, wave 10;
     * {@code O:morph/common/Morph.java:160} "Requested by SoundLogic — can you
     * open the morph GUI? 0 = No, 1 = Yes").
     *
     * <p>Defaults to allowed when the config handle is not up yet (the same
     * fallback {@code drawAbilityIcons} uses). The original synced this into
     * {@code SessionState} server-side but then gated on the client's OWN local
     * config int ({@code O:client/core/TickHandlerClient.java:954}), so a server
     * could never really enforce it; ours reads the same side's config, i.e. the
     * same effective behaviour.</p>
     */
    static boolean selectionAllowed() {
        return Morph.CONFIG == null || Morph.CONFIG.get().allowMorphSelection();
    }

    /** Registered on {@code ClientTickEvents.END_CLIENT_TICK} in MorphClient. */
    public static void clientTick(Minecraft mc) {
        LocalPlayer player = mc.player;
        boolean usable = player != null && mc.gui.screen() == null;

        if (open && !usable) {
            close(); // auto-close when a screen opens or the player vanishes
        }
        // allowMorphSelection: the original force-closed the strip from its own
        // client tick every tick rather than only gating the open key
        // (O:TickHandlerClient.java:954-958) — self-healing against a config
        // reload while the strip is up. Same shape here.
        if (open && !selectionAllowed()) {
            close();
        }
        // Ability-icon scroll clock (wave 10). The original incremented
        // abilityScroll once per client WORLD tick (O:TickHandlerClient.java:177)
        // and the icon strip advances one slot per 30 of them. Gated on
        // isPaused because Minecraft.tick keeps running on the SP pause screen
        // (Playbook §3) — a paused game must not animate.
        if (!mc.isPaused()) {
            abilityScroll++;
        }

        // Mouse triggers only: LMB = select, RMB = cancel (additional to the
        // rebindable keyboard KeyMappings). Enter/Esc/Delete/grave are now
        // rebindable actions dispatched by ClientKeys (see MorphClient).
        boolean lmb = mc.mouseHandler.isLeftPressed();
        boolean rmb = mc.mouseHandler.isRightPressed();

        if (open && usable) {
            clampSelected(player);
            if (edge(lmb, lmbWas)) {
                confirm(player);
            } else if (edge(rmb, rmbWas)) {
                close();
            }
        }

        lmbWas = lmb;
        rmbWas = rmb;

        if (selectorTimer > 0) {
            selectorTimer--;
        }
        if (scrollTimer > 0 && --scrollTimer == 0) {
            selectedPrev = selected;
        }
        if (scrollTimerHori > 0 && --scrollTimerHori == 0) {
            variantIndexPrev = variantIndex;
        }

        // the favourites radial shares the grave key but only when we are shut
        MorphRadial.clientTick(mc, open);
    }

    /** The highlighted variant (selected group + variantIndex), or empty for the
     *  own form / an empty group. */
    private static Optional<MorphVariant> highlighted(MorphState state) {
        List<MorphVariant> variants = selectedVariants(state);
        if (variants.isEmpty()) {
            return Optional.empty();
        }
        int idx = Math.floorMod(variantIndex, variants.size());
        return Optional.of(variants.get(idx));
    }

    private static void confirm(LocalPlayer player) {
        MorphState state = Morph.STATE.get(player);
        Optional<MorphVariant> target = highlighted(state); // empty = own form
        // skip a no-op re-select of the morph already worn (original guard)
        if (!state.current().equals(target)) {
            Morph.SELECT.sendToServer(target);
        }
        close();
    }

    private static void removeHighlighted(LocalPlayer player) {
        MorphState state = Morph.STATE.get(player);
        Optional<MorphVariant> target = highlighted(state);
        if (target.isEmpty()) {
            return; // own form (row 0) is never removable
        }
        MorphVariant variant = target.get();
        if (state.current().equals(Optional.of(variant))) {
            return; // never remove the morph currently worn
        }
        if (state.isFavourite(variant)) {
            return; // original protects favourites from deletion
        }
        Morph.REMOVE.sendToServer(variant);
    }

    private static void favouriteHighlighted(LocalPlayer player) {
        MorphState state = Morph.STATE.get(player);
        highlighted(state).ifPresent(Morph.FAVOURITE::sendToServer);
    }

    private static void clampSelected(LocalPlayer player) {
        MorphState state = Morph.STATE.get(player);
        int rows = groupKeys(state).size() + 1;
        if (selected >= rows) {
            selected = rows - 1;
        }
        if (selected < 0) {
            selected = 0;
        }
        int size = selectedVariants(state).size();
        if (size == 0) {
            variantIndex = 0;
        } else if (variantIndex >= size) {
            variantIndex = size - 1;
        }
    }

    private static void close() {
        open = false;
    }

    /** True to callers that want to know whether the strip is showing. */
    public static boolean isOpen() {
        return open;
    }

    // ------------------------------------------------------------------
    // HUD render (HudElement registered in MorphClient)
    // ------------------------------------------------------------------

    public static void render(GuiGraphicsExtractor graphics,
            DeltaTracker deltaTracker) {
        if (!open) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.screen() != null) {
            return;
        }
        MorphState state = Morph.STATE.get(player);
        List<Object> keys = groupKeys(state);
        Font font = mc.font;
        int rows = keys.size() + 1;
        int height = graphics.guiHeight();
        float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
        framePartial = partial; // consumed by the ability-icon overflow scroll

        // open slide-in from the left: x offset -52 * (1 - progress)^2
        float openP = clamp01((SELECTOR_SHOW_TIME - (selectorTimer - partial))
                / (float) SELECTOR_SHOW_TIME);
        int slide = Math.round(-SLIDE * (1f - openP) * (1f - openP));

        // vertical scroll tween: display old layout at pV=0, new at pV=1
        float scrollP = clamp01((SCROLL_TIME - (scrollTimer - partial))
                / (float) SCROLL_TIME);
        float yTween = BOX * (selected - selectedPrev) * (1f - scrollP * scrollP);

        // horizontal variant tween: shift the whole strip while it settles
        float scrollPH = clamp01((SCROLL_TIME - (scrollTimerHori - partial))
                / (float) SCROLL_TIME);
        float xTweenHori =
                BOX * (variantIndex - variantIndexPrev) * (1f - scrollPH * scrollPH);

        int gap = (height - BAND) / 2;
        int maxShow = (int) Math.ceil(height / (float) BOX) + 2;

        for (int i = 0; i < rows; i++) {
            if (Math.abs(i - selected) > maxShow) {
                continue; // cull rows well off-screen
            }
            int boxTop = Math.round(gap + BOX * (i - selected) + yTween);
            if (boxTop > height || boxTop + BOX < 0) {
                continue;
            }

            if (i == 0) {
                renderOwnForm(graphics, font, state, player, slide, boxTop,
                        selected == 0);
            } else if (i == selected) {
                renderSelectedGroup(graphics, font, state, mc, player,
                        state.variantsOfKey(keys.get(i - 1)), slide, boxTop,
                        xTweenHori);
            } else {
                renderCollapsedGroup(graphics, font, state, mc, player,
                        state.variantsOfKey(keys.get(i - 1)), slide, boxTop);
            }
        }
    }

    /** Row 0: the player's own form (the real player, statically posed), single
     *  box, no star. Uses {@code mc.player} directly (reliable — the vanilla
     *  inventory renders the player the same way); MorphPreview fixes the facing,
     *  so it does not spin with the camera. */
    private static void renderOwnForm(GuiGraphicsExtractor graphics, Font font,
            MorphState state, LocalPlayer player, int boxLeft, int boxTop,
            boolean isSelected) {
        graphics.blit(TEX_UNSELECTED, boxLeft, boxTop,
                boxLeft + BOX, boxTop + BOX, 0f, 1f, 0f, 1f);
        if (isSelected) {
            graphics.blit(TEX_SELECTED, boxLeft, boxTop,
                    boxLeft + BOX, boxTop + BOX, 0f, 1f, 0f, 1f);
        }
        renderPreview(graphics, player, player, boxLeft, boxTop);
        int colour = isSelected ? YELLOW
                : (state.current().isEmpty() ? GOLD : WHITE);
        graphics.text(font, player.getName(), boxLeft + BOX + 4, boxTop + 4,
                colour, true);
    }

    /** A non-selected type row: a single representative box (the first variant),
     *  with its single mob-type name to the right. */
    private static void renderCollapsedGroup(GuiGraphicsExtractor graphics,
            Font font, MorphState state, Minecraft mc, LocalPlayer player,
            List<MorphVariant> variants, int boxLeft, int boxTop) {
        if (variants.isEmpty()) {
            return;
        }
        MorphVariant variant = variants.get(0);
        graphics.blit(TEX_UNSELECTED, boxLeft, boxTop,
                boxLeft + BOX, boxTop + BOX, 0f, 1f, 0f, 1f);
        drawBoxContents(graphics, state, mc, player, variant, boxLeft, boxTop);
        boolean worn = state.current().equals(Optional.of(variant));
        graphics.text(font, columnLabel(variant), boxLeft + BOX + 4,
                boxTop + 4, worn ? GOLD : WHITE, true);
    }

    /** The selected type row: a horizontal strip of its variants (spec §1). The
     *  mob-type name is drawn ONCE, to the right of the RIGHTMOST variant box
     *  (fix 4) — not per-variant (they would overlap/repeat). */
    private static void renderSelectedGroup(GuiGraphicsExtractor graphics,
            Font font, MorphState state, Minecraft mc, LocalPlayer player,
            List<MorphVariant> variants, int boxLeft, int boxTop,
            float xTweenHori) {
        int n = variants.size();
        // Pass 1: variant box backgrounds, left→right (right cap on top).
        for (int j = 0; j < n; j++) {
            int bx = boxLeft + Math.round(BOX * (j - variantIndex) + xTweenHori);
            Identifier tex = (n != 1 && j != n - 1)
                    ? TEX_UNSELECTED_SIDE : TEX_UNSELECTED;
            graphics.blit(tex, bx, boxTop, bx + BOX, boxTop + BOX,
                    0f, 1f, 0f, 1f);
        }
        // The selected overlay is drawn ONCE over the base column (the
        // highlighted variant), not per-variant (spec §1).
        graphics.blit(TEX_SELECTED, boxLeft, boxTop,
                boxLeft + BOX, boxTop + BOX, 0f, 1f, 0f, 1f);
        // Pass 2: previews + star + icons per variant.
        int rightmostBx = boxLeft;
        for (int j = 0; j < n; j++) {
            int bx = boxLeft + Math.round(BOX * (j - variantIndex) + xTweenHori);
            drawBoxContents(graphics, state, mc, player, variants.get(j),
                    bx, boxTop);
            if (j == n - 1) {
                rightmostBx = bx;
            }
        }
        // Fix 4: ONE name for the whole group, right of the last box. This is the
        // highlighted (selected) row, so the name is YELLOW. A player column shows
        // the target's username (§1.6).
        graphics.text(font, columnLabel(variants.get(0)),
                rightmostBx + BOX + 4, boxTop + 4, YELLOW, true);
    }

    /** Preview + favourite star + ability icons for one box (the name is drawn
     *  once per row by the caller — fix 4). */
    private static void drawBoxContents(GuiGraphicsExtractor graphics,
            MorphState state, Minecraft mc, LocalPlayer player,
            MorphVariant variant, int boxLeft, int boxTop) {
        LivingEntity preview = previewFor(mc, variant);
        if (preview != null) {
            renderPreview(graphics, preview, player, boxLeft, boxTop);
            drawAbilityIcons(graphics, preview, boxLeft, boxTop);
        }
        if (state.isFavourite(variant)) {
            drawStar(graphics, boxLeft, boxTop);
        }
    }

    /**
     * Static posed entity preview seated in the box (spec §2). The pose is fixed
     * by passing CONSTANT mouseX/mouseY (≈25° body yaw + ≈14° forward tilt);
     * because {@code extractEntityInInventoryFollowsMouse} feeds mouseX/Y only
     * into the yaw/pitch terms, constant values yield an unmoving posed render.
     */
    private static void renderPreview(GuiGraphicsExtractor graphics,
            LivingEntity entity, LocalPlayer player, int boxLeft, int boxTop) {
        // The own form IS the live player; only position the built dummies
        // (never touch the real player). MorphPreview bypasses the FollowsMouse
        // wrapper (via the extractRenderState invoker), so the inventory
        // morph-swap mixin does not fire here — the own-form box shows the player.
        if (entity != player) {
            prepPreview(entity, player);
        }
        // Seat/scale from the PREVIEWED entity's own size — for the own form use
        // the player defaults, NOT mc.player's current (morph-changed) bb, so a
        // tiny current morph never shifts/shrinks the previews (1b).
        float dispW = entity == player
                ? MorphPreview.PLAYER_WIDTH : entity.getBbWidth();
        float dispH = entity == player
                ? MorphPreview.PLAYER_HEIGHT : entity.getBbHeight();
        int scale = MorphPreview.fitScale(Math.max(dispW, dispH));
        MorphPreview.render(graphics, entity, boxLeft, boxTop,
                boxLeft + BOX, boxTop + BOX, scale, dispW, dispH, 0.0f);
    }

    /**
     * Gives the (never-ticked, cached) preview dummy a proper, loaded render
     * position so extraction never sees a degenerate/unloaded state, while its
     * un-advanced walk animation keeps limbs idle. The pose (body/head yaw,
     * pitch) is set by the FollowsMouse call, not here.
     */
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

    /** Favourite star at the box TOP-RIGHT (spec §4): 9 px, black drop-shadow
     *  double-draw (shadow at +1,+1 then white on top), 16x16 source. */
    private static void drawStar(GuiGraphicsExtractor graphics,
            int boxLeft, int boxTop) {
        int sx = Math.round(boxLeft + STAR_DX);
        int sy = Math.round(boxTop + STAR_DY);
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEX_FAVOURITE,
                sx + 1, sy + 1, 0f, 0f, STAR, STAR, 16, 16, 16, 16, SHADOW);
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEX_FAVOURITE,
                sx, sy, 0f, 0f, STAR, STAR, 16, 16, 16, 16, WHITE);
    }

    /**
     * The morph's derived ability icons (fix 3): 12 px, laid out in a U around
     * the preview — down the LEFT edge, across the BOTTOM, up the RIGHT edge —
     * so they never cover the mob's face (spec §5's column stacked over it). Each
     * icon is black-shadow double-drawn. Gated by the master {@code abilities}
     * config (was {@code showAbilitiesInGui}).
     */
    private static void drawAbilityIcons(GuiGraphicsExtractor graphics,
            LivingEntity preview, int boxLeft, int boxTop) {
        // Master ability gate (was showAbilitiesInGui); default on when the
        // config is unavailable client-side.
        if (Morph.CONFIG != null && !Morph.CONFIG.get().abilities()) {
            return;
        }
        List<Identifier> icons = iconsFor(preview);
        if (icons.isEmpty()) {
            return;
        }
        // FITS: the shipped look, unchanged — same slots, same order, same
        // pixels, no clock and no scissor. Only overflow costs anything.
        if (icons.size() <= ICON_SLOTS.length) {
            for (int i = 0; i < icons.size(); i++) {
                drawIconAt(graphics, icons.get(i), boxLeft + ICON_SLOTS[i][0],
                        boxTop + ICON_SLOTS[i][1]);
            }
            return;
        }
        drawScrollingIcons(graphics, icons, boxLeft, boxTop);
    }

    /**
     * This morph's icon textures in draw order: the derived built-ins first,
     * then any third-party ability that supplies one.
     *
     * <p>The original consulted its own {@code Ability.entityHasAbility} for
     * exactly this pass ({@code O:morph/api/Ability.java:113-117}); ours is
     * {@code AbilityRegistry.resolve}, the same predicate the server derives
     * the set from — no sync needed (SPEC deviation D9-6).</p>
     */
    private static List<Identifier> iconsFor(LivingEntity preview) {
        EnumSet<MorphAbility> abilities = MorphAbility.deriveAbilities(preview);
        List<Ability> custom = com.deds.morph.api.AbilityRegistry.resolve(preview);
        List<Identifier> icons = new ArrayList<>(abilities.size() + custom.size());
        for (MorphAbility ability : abilities) {
            icons.add(icon(ability));
        }
        for (Ability ability : custom) {
            BId iconId = ability.icon();
            if (iconId == null) {
                continue; // "Can be null" — the original's getIcon contract
            }
            icons.add(Identifier.fromNamespaceAndPath(
                    iconId.namespace(), iconId.path()));
        }
        return icons;
    }

    /**
     * OVERFLOW (wave 10): more icons than the U has slots, so the whole list
     * circulates through it — one slot every {@link #SCROLL_TICKS_PER_ICON}
     * ticks, continuously, clipped to the box.
     *
     * <p>Straight from the original's mechanism
     * ({@code O:client/core/TickHandlerClient.java:1683-1723}), with the two
     * substitutions 26.2 forces: the Forge <b>stencil</b> window becomes
     * {@code GuiGraphics.enableScissor} (javap: {@code GuiGraphicsExtractor
     * .enableScissor(x1,y1,x2,y2)} builds a {@code ScreenRectangle} and
     * transforms it by the CURRENT pose matrix, so passing the already-tweened
     * {@code boxLeft/boxTop} clips in exactly the strip's own space — the
     * caveat the wave-9 write-up flagged), and the original's straight vertical
     * column becomes our U path. Kept verbatim: the list is walked TWICE
     * end-to-end and indexed {@code k % n} so the ring has no seam
     * ({@code :1702}), and the phase folds in the PARTIAL TICK rather than wall
     * time ({@code :1719}).</p>
     *
     * <p>Not kept: the original scrolled only the SELECTED row and only past 3
     * icons, degrading to a two-column grid when no stencil bit was free
     * ({@code :1647-1681}, {@code :1754-1759}). Ours scrolls every row that
     * overflows, because our threshold is 6 rather than 3 — overflow is rare
     * enough that "the row that has too many" is not a wall of motion, and
     * a row is only ever showing what it cannot otherwise show. Deviation
     * D10-5.</p>
     */
    private static void drawScrollingIcons(GuiGraphicsExtractor graphics,
            List<Identifier> icons, int boxLeft, int boxTop) {
        int n = icons.size();
        float phase = (Math.floorMod(abilityScroll, SCROLL_TICKS_PER_ICON * n)
                + framePartial) / SCROLL_TICKS_PER_ICON;
        // +1 on the right/bottom keeps the 1 px drop shadow of the slots that
        // sit flush with the box edge, which the un-clipped path also draws.
        graphics.enableScissor(boxLeft, boxTop,
                boxLeft + BOX + 1, boxTop + BOX + 1);
        for (int k = 0; k < n * 2; k++) {
            float s = k - phase;
            if (s <= SCROLL_FIRST || s >= SCROLL_LAST) {
                continue;
            }
            float[] at = pathPos(s);
            drawIconAt(graphics, icons.get(k % n),
                    boxLeft + Math.round(at[0]), boxTop + Math.round(at[1]));
        }
        graphics.disableScissor();
    }

    /** Linear position along {@link #SCROLL_PATH} at path coordinate {@code s}
     *  ({@code -1} = the entry point, {@code 0..5} = the U slots). */
    private static float[] pathPos(float s) {
        float t = Math.clamp(s + 1.0f, 0.0f, SCROLL_PATH.length - 1.0f);
        int i = Math.min((int) t, SCROLL_PATH.length - 2);
        float f = t - i;
        return new float[] {
            SCROLL_PATH[i][0] + (SCROLL_PATH[i + 1][0] - SCROLL_PATH[i][0]) * f,
            SCROLL_PATH[i][1] + (SCROLL_PATH[i + 1][1] - SCROLL_PATH[i][1]) * f,
        };
    }

    /** One 12 px icon at an absolute screen position, black-shadow
     *  double-drawn (the original's white-then-black pair, {@code :1727-1751}). */
    private static void drawIconAt(GuiGraphicsExtractor graphics, Identifier tex,
            int ix, int iy) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, tex, ix + 1, iy + 1,
                0f, 0f, ICON, ICON, ICON_TEX, ICON_TEX, ICON_TEX, ICON_TEX,
                SHADOW);
        graphics.blit(RenderPipelines.GUI_TEXTURED, tex, ix, iy,
                0f, 0f, ICON, ICON, ICON_TEX, ICON_TEX, ICON_TEX, ICON_TEX,
                WHITE);
    }

    /**
     * Drops the cached preview for a player morph once that target comes online
     * (wave 5 item E). Without it a relogged viewer keeps a default-skinned
     * "wrong Steve" column for that player until the level changes.
     */
    static void invalidatePlayerPreview(java.util.UUID targetId) {
        PREVIEW.keySet().removeIf(v -> v.isPlayer()
                && v.playerId().filter(targetId::equals).isPresent());
    }

    /**
     * Per-tick sweep (wave-5 review finding 11): drops any preview that was built
     * while its target player was OFFLINE (default skin, whose captured lookup
     * future never re-resolves) once that target joins — even when NOBODY is
     * currently wearing the morph, which is the case the worn-dummy heal in
     * {@code MorphDummies.healOfflinePlayerSkin} can never see.
     */
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
        // Build the preview dummy from the full variant (type + NBT) so the box
        // shows the right sheep colour / slime size etc.; may be null
        // (name-only fallback).
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

    /**
     * A group column's label (§1.6): a player column shows the target player's
     * username (fallback "Player" if absent) — NOT {@code nameOf(minecraft:player)},
     * which resolves the generic "Player" for every player column; a mob column
     * shows the mob-type name.
     */
    private static Component columnLabel(MorphVariant variant) {
        if (variant.isPlayer()) {
            return Component.literal(variant.playerName().orElse("Player"));
        }
        return nameOf(variant.type());
    }

    // ------------------------------------------------------------------
    // input helpers
    // ------------------------------------------------------------------

    private static boolean down(Minecraft mc, int glfwKey) {
        return McCompat.keyDown(mc, glfwKey);
    }

    private static boolean shiftDown(Minecraft mc) {
        return down(mc, InputConstants.KEY_LSHIFT)
                || down(mc, InputConstants.KEY_RSHIFT);
    }

    private static boolean edge(boolean now, boolean was) {
        return now && !was;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
