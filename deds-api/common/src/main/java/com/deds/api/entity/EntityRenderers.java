package com.deds.api.entity;

import com.deds.api.registry.RegistryHandle;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Binds a renderer to a registered entity type (Ded's API v2.3) — the client
 * half of {@link EntityRegistrar}. <b>Call from a mod's CLIENT entrypoint
 * only</b>, like {@code com.deds.api.menu.MenuScreens}: this class touches
 * {@code net.minecraft.client.**}, which does not exist on a dedicated
 * server.
 *
 * <p><b>Not vanilla's {@code EntityRenderers}.</b> The name is deliberate —
 * this is a one-method facade over
 * {@code net.minecraft.client.renderer.entity.EntityRenderers.register}, the
 * registry it delegates to — but the two are different classes and only this
 * one may appear in mod code. Vanilla's {@code register} is {@code private} in
 * a stock 26.2 jar (javap); it is callable at all only because each loader
 * opens it (Fabric's {@code fabric-transitive-access-wideners-v1}, NeoForge's
 * access transformer), which is the kind of loader-specific mechanic
 * docs/ARCHITECTURE.md's boundary table keeps out of mod code.</p>
 *
 * <p>Registration is order-independent with respect to the platform: vanilla's
 * provider table is a {@code Map} populated in its own class initializer and
 * this call simply adds to it, so a mod's client entrypoint may run before or
 * after the API's (which it does — the loader does not order
 * {@code deds_api}'s client entrypoint ahead of its consumers').</p>
 *
 * <p><b>Bind every type the mod registers.</b> An entity type with no renderer
 * is not a startup crash, and nothing warns about it either: vanilla's
 * {@code validateRegistrations()}, which would log
 * {@code "No renderer registered for {}"}, only runs from a self-test that
 * neither loader enables. The first time such an entity is rendered, the
 * renderer lookup returns null and the client crashes with a
 * NullPointerException.</p>
 */
public final class EntityRenderers {

    private EntityRenderers() {
    }

    /**
     * Binds {@code renderer} to {@code type}.
     *
     * <p>The provider is vanilla's {@link EntityRendererProvider} — a
     * {@code Context -> EntityRenderer<T, ?>} — used directly rather than
     * re-declared, because unlike {@code MenuScreens.ScreenFactory} (whose
     * vanilla counterpart is a non-public type with an intersection-typed
     * generic) this one is plain public vanilla surface and a constructor
     * reference such as {@code LuggageRenderer::new} fits it as it stands. The
     * {@code Context} it is handed carries the model set, the item and block
     * model resolvers and the font; {@code Context.bakeLayer} is available but
     * <b>not required</b> — {@code LayerDefinition.bakeRoot()} is public
     * (javap) and builds a {@code ModelPart} from a mesh with no layer
     * registration at all, which is how the pack's existing hand-built entity
     * models work.</p>
     *
     * <p>The signature mirrors vanilla's: {@code T} comes from the
     * <em>renderer</em>, and the handle only has to be for some subtype of it,
     * so a renderer written against a supertype (a plain
     * {@code ItemEntityRenderer} for a projectile that extends
     * {@code ItemEntity}) binds without a cast. If inference ever needs help,
     * name it — {@code EntityRenderers.<MyEntity>register(handle, ...)}.</p>
     *
     * <p><b>A second binding for the same type silently replaces the
     * first.</b> That is a real difference from {@code MenuScreens.register},
     * which throws: the registry underneath is a bare {@code PROVIDERS.put}
     * whose previous value is never examined (javap), so a duplicate is
     * last-one-wins with no log line. Register once.</p>
     *
     * @param type     a handle from {@link EntityRegistrar#register} or
     *                 {@link EntityRegistrar#registerLiving}
     * @param renderer builds the renderer (usually a constructor reference)
     * @param <T>      the entity class the renderer draws
     */
    public static <T extends Entity> void register(
            RegistryHandle<? extends EntityType<? extends T>> type,
            EntityRendererProvider<T> renderer) {
        net.minecraft.client.renderer.entity.EntityRenderers.register(
                type.get(), renderer);
    }
}
