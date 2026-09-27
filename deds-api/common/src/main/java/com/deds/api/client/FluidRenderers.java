package com.deds.api.client;

import com.deds.api.id.BId;
import com.deds.api.registry.RegistryHandle;

import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * How a mod's fluid is DRAWN IN THE WORLD (Ded's API v2.6).
 *
 * <h2>Why this exists — a registered fluid is invisible without it</h2>
 *
 * <p>A fluid needs two independent registrations on 26.2 and only one of them
 * is data. The still/flowing {@code Fluid} pair, its block, its bucket and its
 * behaviour are all ordinary registry work that {@code ModContext} already
 * covers. The <b>textures</b> are not: 26.2 resolves a fluid's sprites through
 * a code-side model registration, and a fluid that never makes it has no
 * fallback worth the name.</p>
 *
 * <p><b>This was measured, not predicted.</b> Thermal Expansion wave 3
 * registered six fluids correctly in every other respect and every one of them
 * rendered as the magenta-and-black MISSING TEXTURE checkerboard in the world,
 * while their GUI tank gauges — which read the baked particle sprite by a
 * different route — looked perfect. Vanilla water in the same screenshot was
 * fine, which is what proved the gap was ours. See
 * {@code misc/layer3-archive/thermalexpansion-2026-08-08-wave3/}, shot 13.</p>
 *
 * <p>Call {@link #register} from a mod's CLIENT entrypoint, once per fluid
 * PAIR. Registering is idempotent per pair in the sense that the last
 * registration wins; registering only the source and not the flowing half
 * leaves the flowing half untextured, which is why both handles are required
 * rather than optional.</p>
 *
 * <h2>Textures</h2>
 *
 * <p>The two ids are ordinary sprite ids — {@code <ns>:block/<name>} style,
 * the same paths a block model would name — and they must exist in the block
 * atlas, which they do automatically if they live under
 * {@code assets/<ns>/textures/block/}. Animated sprites work: ship the
 * {@code .png.mcmeta} beside the {@code .png} as usual and the animation is
 * picked up by the atlas, not by anything here.</p>
 *
 * <p>The tint is a plain ARGB multiply applied to both sprites, matching what
 * vanilla does for water (its blue is a tint over a greyscale sprite, not a
 * blue texture). Fluids whose art is already the right colour — every Thermal
 * Expansion fluid — pass {@link #NO_TINT}.</p>
 *
 * <h2>Looking a fluid's appearance back up (v2.8)</h2>
 *
 * <p>{@link #stillTexture} and {@link #tint} answer, for any fluid, the pair
 * that was registered for it. They exist because a fluid is drawn in more
 * places than the world: Thermal Expansion's Portable Tank renderer has to
 * paint the fluid INSIDE the tank with the same still sprite the world uses,
 * and before v2.8 the only way to know which sprite that was would have been
 * to keep a second copy of the table in the mod. Both answer for EITHER half
 * of a pair, and both have a documented answer for a fluid that was never
 * registered here — see their javadoc, and note that vanilla water and lava
 * are always that case.</p>
 *
 * <p>Client-only surface: this class is safe to reference from a mod's client
 * entrypoint and must never be touched from common code, exactly like
 * {@link ClientKeys} and {@link BlockTints}.</p>
 */
public final class FluidRenderers {

    /**
     * White — the sprite's own colours, unmodified. What every fluid whose
     * texture is already coloured should pass.
     */
    public static final int NO_TINT = 0xFFFFFFFF;

    /** One pending or completed registration. */
    private record Entry(RegistryHandle<Fluid> still,
            RegistryHandle<Fluid> flowing, BId stillTexture,
            BId flowingTexture, int tintArgb) {
    }

    /**
     * Loader-side backend, installed by the platform's client entrypoint.
     *
     * <p>Handles are passed rather than {@code Fluid}s because a mod may
     * register its renderers before the fluids themselves have been
     * resolved.</p>
     */
    public interface Backend {
        void register(Fluid still, Fluid flowing, BId stillTexture,
                BId flowingTexture, int tintArgb);
    }

    private static final List<Entry> PENDING = new ArrayList<>();

    /**
     * Every registration ever made, kept for {@link #stillTexture} and
     * {@link #tint} (v2.8). {@link #PENDING} cannot serve: it is drained by
     * {@link #install}, and a registration made AFTER the backend is installed
     * never enters it at all.
     */
    private static final List<Entry> REGISTERED = new CopyOnWriteArrayList<>();

    /**
     * {@link #REGISTERED} indexed by fluid, both halves of each pair, built on
     * first lookup and dropped whenever a new pair is registered. Lazy because
     * a {@link RegistryHandle} cannot be resolved at registration time (that is
     * the whole reason {@link Entry} stores handles); by the time anything
     * looks a sprite up, the registries are long frozen.
     */
    private static volatile Map<Fluid, Entry> index;

    /**
     * Guards {@link #backend} and {@link #PENDING} together, as in
     * {@link BlockEntityRenderers}: the no-backend check and the queueing are
     * one step, and so are install's assignment and replay, so a registration
     * racing install can neither be stranded in the queue nor lost.
     */
    private static final Object LOCK = new Object();

    /** Written only under {@link #LOCK}. */
    private static volatile Backend backend;

    private FluidRenderers() {
    }

    /**
     * Draws {@code still}/{@code flowing} with the given sprites, untinted.
     *
     * @param still         the source fluid, as returned by
     *                      {@code ctx.fluids().register}
     * @param flowing       its flowing twin — a {@code FlowingFluid} is always
     *                      a pair, and both halves need textures
     * @param stillTexture  the still sprite, e.g. {@code block/fluid_ender_still}
     * @param flowingTexture the flowing sprite
     */
    public static void register(RegistryHandle<Fluid> still,
            RegistryHandle<Fluid> flowing, BId stillTexture,
            BId flowingTexture) {
        register(still, flowing, stillTexture, flowingTexture, NO_TINT);
    }

    /**
     * As {@link #register(RegistryHandle, RegistryHandle, BId, BId)}, with an
     * ARGB multiply over both sprites ({@link #NO_TINT} for none).
     */
    public static void register(RegistryHandle<Fluid> still,
            RegistryHandle<Fluid> flowing, BId stillTexture,
            BId flowingTexture, int tintArgb) {
        Entry entry = new Entry(still, flowing, stillTexture, flowingTexture,
                tintArgb);
        REGISTERED.add(entry);
        index = null;
        Backend installed;
        synchronized (LOCK) {
            installed = backend;
            if (installed == null) {
                // The API's client entrypoint is not ordered ahead of a mod's
                // (Fabric does not promise that), so queue and replay — the
                // same shape BlockModelWrappers uses for its wrapper list.
                PENDING.add(entry);
                return;
            }
        }
        apply(installed, entry);
    }

    /**
     * Internal: installed once by the platform's client bootstrap, which then
     * replays anything a mod registered first. Not for mods.
     */
    public static void install(Backend platform) {
        synchronized (LOCK) {
            backend = platform;
            for (Entry entry : PENDING) {
                apply(platform, entry);
            }
            PENDING.clear();
        }
    }

    private static void apply(Backend platform, Entry entry) {
        platform.register(entry.still().get(), entry.flowing().get(),
                entry.stillTexture(), entry.flowingTexture(),
                entry.tintArgb());
    }

    // =====================================================================
    // v2.8 — the LOOKUP half. Registration already carries a fluid's still
    // sprite and its tint; before v2.8 that pair was forwarded to the backend
    // and forgotten, so a second consumer (a block-entity renderer drawing the
    // fluid INSIDE a tank) had to duplicate the table to find out what a fluid
    // looks like. It asks here instead.
    // =====================================================================

    /**
     * The STILL sprite registered for {@code fluid}, or {@code null} when no
     * mod registered it here.
     *
     * <p>Answers for BOTH halves of a pair — hand it either the source fluid
     * or its flowing twin and you get the still sprite, because a tank or a
     * pipe can legitimately be holding either and they must draw the same.</p>
     *
     * <p><b>{@code null} is the normal answer for vanilla water and lava</b>,
     * which have their sprites through vanilla's own path and never come
     * through {@link #register}. A caller that must draw ANY fluid needs a
     * fallback, and the one that works for vanilla and for any third-party
     * fluid alike is the fluid block's baked-model particle material —
     * {@code BlockFaceSampler.modelOf(fluid.defaultFluidState()
     * .createLegacyBlock()).particleMaterial().sprite()} — which is the still
     * sprite for every fluid block in the game (Thermal Expansion's GUI tank
     * gauges have drawn water that way since wave 3). This method is
     * deliberately NOT given that fallback itself: it returns a {@link BId},
     * and the particle route yields a baked sprite that only exists on the
     * client after an atlas exists, so folding it in here would make the
     * method's contract depend on load timing.</p>
     *
     * @param fluid may be {@code null}, which answers {@code null}
     */
    public static BId stillTexture(Fluid fluid) {
        Entry entry = entryFor(fluid);
        return entry == null ? null : entry.stillTexture();
    }

    /**
     * The ARGB multiply registered for {@code fluid}, or {@link #NO_TINT} when
     * no mod registered it here (so an unknown fluid draws in its sprite's own
     * colours, which is the safe answer). Answers for both halves of a pair,
     * like {@link #stillTexture}.
     *
     * <p>Note that {@link #NO_TINT} is also the honest answer for a fluid that
     * WAS registered untinted, so this cannot be used to test membership — use
     * {@link #stillTexture}{@code  != null} for that.</p>
     */
    public static int tint(Fluid fluid) {
        Entry entry = entryFor(fluid);
        return entry == null ? NO_TINT : entry.tintArgb();
    }

    private static Entry entryFor(Fluid fluid) {
        if (fluid == null) {
            return null;
        }
        Map<Fluid, Entry> table = index;
        if (table == null) {
            table = new HashMap<>();
            for (Entry entry : REGISTERED) {
                table.put(entry.still().get(), entry);
                table.put(entry.flowing().get(), entry);
            }
            // A benign race can build this twice; both copies are equal and
            // the field is volatile, so either is correct to publish.
            index = table;
        }
        return table.get(fluid);
    }
}
