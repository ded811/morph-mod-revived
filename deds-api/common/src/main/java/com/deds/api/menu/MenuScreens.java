package com.deds.api.menu;

import com.deds.api.mixin.MenuScreensInvoker;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Binds a screen to a registered menu (Ded's API v2.2) — the client half of
 * {@link MenuRegistrar}. <b>Call from a mod's CLIENT entrypoint only</b>, like
 * {@code com.deds.api.client.BlockModelWrappers}: this class touches
 * {@code net.minecraft.client.**}, which does not exist on a dedicated server.
 *
 * <p><b>Not vanilla's {@code MenuScreens}.</b> The name is deliberate — this is
 * a one-method facade over
 * {@code net.minecraft.client.gui.screens.MenuScreens.register}, the registry
 * it delegates to — but the two are different classes and only this one may
 * appear in mod code. Vanilla's {@code register} is {@code private} in a stock
 * 26.2 jar (javap), and NeoForge keeps it private; this facade reaches it
 * through a mixin invoker inside Ded's API ({@code MenuScreensInvoker}), the
 * same way on every loader. That is precisely the kind of loader-specific
 * mechanic docs/ARCHITECTURE.md's boundary table keeps out of mod code.</p>
 *
 * <p>Registration is order-independent with respect to the platform: vanilla's
 * screen table is populated in its own class initializer and this call simply
 * adds to it, so a mod's client entrypoint may run before or after the API's
 * (which it does — the loader does not order {@code deds_api}'s client
 * entrypoint ahead of its consumers').</p>
 *
 * <p>A menu opened with no screen bound is not a crash: a warning is logged
 * (Fabric: "Screen not registered for menu ...!"; NeoForge: vanilla's "Failed
 * to create screen for menu type: ...") and nothing appears. Bind every menu
 * the mod opens.</p>
 */
public final class MenuScreens {

    /**
     * Builds the screen for one menu instance, on the client.
     *
     * <p>Returns {@link AbstractContainerScreen} rather than a bare
     * {@code Screen} because that is what the underlying registry demands —
     * its screen type must be both a screen and a
     * {@code MenuAccess<T>}, and {@code AbstractContainerScreen<T>} is the
     * vanilla class that is both. A constructor reference like
     * {@code XpBottlerScreen::new} fits it.</p>
     *
     * @param <T> the menu class this screen shows
     */
    @FunctionalInterface
    public interface ScreenFactory<T extends AbstractContainerMenu> {

        /**
         * @param menu            the menu instance the client just built with
         *                        {@code MenuRegistrar.Factory}
         * @param playerInventory the viewing player's inventory
         * @param title           the title {@code MenuRegistrar.open} was given
         */
        AbstractContainerScreen<T> create(T menu, Inventory playerInventory,
                Component title);
    }

    private MenuScreens() {
    }

    /**
     * Binds {@code screen} to {@code menu}. Register <b>once</b>, from the
     * client entrypoint.
     *
     * <p>Once is not a style preference: the underlying registry is a
     * {@code put} whose previous value it then checks, and a non-null previous
     * value throws {@code IllegalStateException} naming the menu id (javap,
     * 26.2). A second binding for the same menu is a startup crash, not a
     * replacement.</p>
     *
     * @param menu   a handle from {@link MenuRegistrar#register}
     * @param screen builds the screen (usually a constructor reference)
     * @param <T>    the menu class
     * @throws IllegalStateException if a screen is already bound to
     *                               {@code menu}
     */
    public static <T extends AbstractContainerMenu> void register(
            MenuHandle<T, ?> menu, ScreenFactory<T> screen) {
        // Explicit type arguments, not inference: the vanilla method's second
        // type variable is bounded by an INTERSECTION
        // (U extends Screen & MenuAccess<M>) and is reachable only through the
        // method reference's return type. Naming AbstractContainerScreen<T> —
        // the vanilla class that satisfies both halves of that bound — keeps
        // the resolution off the compiler's guesswork.
        MenuScreensInvoker.<T, AbstractContainerScreen<T>>deds_api$register(
                menu.get(), screen::create);
    }
}
