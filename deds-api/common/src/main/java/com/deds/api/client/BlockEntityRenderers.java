package com.deds.api.client;

import com.deds.api.registry.RegistryHandle;

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds a renderer to a registered BLOCK-entity type (Ded's API v2.8) — the
 * per-block-entity counterpart of {@link com.deds.api.entity.EntityRenderers}.
 * <b>Call from a mod's CLIENT entrypoint only</b>: this class touches
 * {@code net.minecraft.client.**}, which does not exist on a dedicated server.
 *
 * <h2>Why this exists — a block a MODEL cannot draw</h2>
 *
 * <p>A blockstate JSON draws a fixed mesh. Anything whose geometry depends on
 * live block-entity data — a tank's fluid surface, a conduit's fill level, a
 * tesseract's beam — has no data-side expression at all, and 1.6.4 mods that
 * did this used a Forge {@code ISimpleBlockRenderingHandler} /
 * {@code TileEntitySpecialRenderer}. 26.2's equivalent is a
 * {@code BlockEntityRenderer}, and the registration for one is loader surface
 * ({@code fabric-rendering-v1}'s {@code BlockEntityRendererRegistry}), so it
 * belongs behind this seam and not in mod code
 * (docs/ARCHITECTURE.md's boundary rule).</p>
 *
 * <p><b>This was measured, not predicted.</b> Thermal Expansion wave 5 shipped
 * five Portable Tanks whose SPEC row promises "128 render levels with 4-step
 * update hysteresis"; the block entity kept the level and nothing ever drew
 * it, because the module had no renderer seam to reach for. The owner reported
 * it at playtest as "the tanks are missing their inside textures".</p>
 *
 * <h2>Everything ABOVE the seam stays vanilla</h2>
 *
 * <p>Only the registration crosses the boundary. The renderer itself is
 * ordinary accepted vanilla surface and mod code writes it directly:
 * {@code BlockEntityRenderer<T, S>}, its
 * {@code BlockEntityRenderState} subclass, {@code PoseStack},
 * {@code SubmitNodeCollector} and {@code TextureAtlasSprite}. The provider is
 * vanilla's own {@link BlockEntityRendererProvider} — a
 * {@code Context -> BlockEntityRenderer<T, S>} — used as it stands rather than
 * re-declared, exactly as {@code EntityRenderers} uses
 * {@code EntityRendererProvider}, so a constructor reference such as
 * {@code TankRenderer::new} fits without a wrapper.</p>
 *
 * <h2>Registration is order-independent, and that is load-bearing</h2>
 *
 * <p>Fabric does <b>not</b> order the API's client entrypoint ahead of a
 * consumer's, despite the declared dependency — that cost a client crash once
 * already (API-ROADMAP v1.5, "registration seams must be order-independent",
 * and again in {@link FluidRenderers}). So {@link #register} queues when no
 * backend is installed yet and {@link #install} replays the queue; a mod may
 * therefore call {@link #register} at any point in client init.</p>
 *
 * <p>Handles rather than resolved types are queued for the same reason
 * {@link FluidRenderers} queues them: a mod may bind a renderer before the
 * block-entity type it names has been resolved.</p>
 *
 * <h2>Bind every type that needs one, and only once</h2>
 *
 * <p>A block-entity type with no renderer is not an error and produces no log
 * line at all — the dispatcher simply has no entry for it and the block draws
 * as its blockstate model and nothing more. A SECOND binding for the same type
 * silently replaces the first (the registry underneath is a bare
 * {@code PROVIDERS.put} whose previous value is never examined — javap of
 * {@code net.minecraft.client.renderer.blockentity.BlockEntityRenderers}), so
 * a duplicate is last-one-wins with no warning. Register once.</p>
 */
public final class BlockEntityRenderers {

    /**
     * One pending or completed binding. The two type parameters are captured
     * together so {@link #applyTo} can hand the backend a matching pair out of
     * a heterogeneous list without an unchecked cast.
     */
    private record Entry<T extends BlockEntity,
            S extends BlockEntityRenderState>(
            RegistryHandle<? extends BlockEntityType<T>> type,
            BlockEntityRendererProvider<? super T, S> renderer) {

        void applyTo(Backend platform) {
            platform.register(type.get(), renderer);
        }
    }

    /**
     * Loader-side backend, installed by the platform's client entrypoint.
     *
     * <p>Takes a resolved {@code BlockEntityType} rather than a handle: the
     * queue above has already waited for the registry.</p>
     */
    public interface Backend {
        <T extends BlockEntity, S extends BlockEntityRenderState> void register(
                BlockEntityType<T> type,
                BlockEntityRendererProvider<? super T, S> renderer);
    }

    /**
     * Guards {@link #backend} and {@link #PENDING} together: the check that
     * finds no backend and the queueing are one step, and so are install's
     * assignment and replay. Otherwise a registration racing install could
     * read a null backend and join the queue after the replay had cleared it,
     * and be lost with no error (NeoForge constructs mods in parallel; on
     * Fabric the lock is never contended). Same pattern as {@link ClientKeys}.
     */
    private static final Object LOCK = new Object();

    /** Guarded by {@link #LOCK}. */
    private static final List<Entry<?, ?>> PENDING = new ArrayList<>();

    /** Written only under {@link #LOCK}. */
    private static volatile Backend backend;

    private BlockEntityRenderers() {
    }

    /**
     * Binds {@code renderer} to {@code type}.
     *
     * <p>The renderer's block-entity parameter is {@code ? super T}, so ONE
     * renderer written against a base class binds to every subclass's type
     * without a cast — which is the normal shape for a tiered block (Thermal
     * Expansion's five Portable Tanks are four
     * {@code BlockEntityType<TankBlockEntity>} plus one
     * {@code BlockEntityType<CreativeTankBlockEntity>}, and all five take the
     * same renderer). {@code BlockEntityType} is invariant, so this could not
     * be expressed on the handle.</p>
     *
     * @param type     a handle from {@code ctx.blockEntities().register}
     * @param renderer builds the renderer (usually a constructor reference)
     * @param <T>      the block-entity class the type produces
     * @param <S>      the renderer's render-state class
     */
    public static <T extends BlockEntity, S extends BlockEntityRenderState>
            void register(RegistryHandle<? extends BlockEntityType<T>> type,
                    BlockEntityRendererProvider<? super T, S> renderer) {
        Entry<T, S> entry = new Entry<>(type, renderer);
        Backend installed;
        synchronized (LOCK) {
            installed = backend;
            if (installed == null) {
                // The API's client entrypoint is not ordered ahead of a mod's
                // (Fabric does not promise that), so queue and replay — the
                // same shape FluidRenderers and BlockModelWrappers use.
                PENDING.add(entry);
                return;
            }
        }
        entry.applyTo(installed);
    }

    /**
     * Internal: installed once by the platform's client bootstrap, which then
     * replays anything a mod registered first. Not for mods.
     */
    public static void install(Backend platform) {
        synchronized (LOCK) {
            backend = platform;
            for (Entry<?, ?> entry : PENDING) {
                entry.applyTo(platform);
            }
            PENDING.clear();
        }
    }
}
