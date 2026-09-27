package com.deds.api.mixin;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches vanilla's {@code MenuScreens.register}, which is {@code private} in
 * a stock 26.2 jar and stays private on NeoForge (its access transformer opens
 * only {@code MenuScreens.ScreenConstructor}); Fabric's transitive access
 * widener happens to open it. Calling it through this invoker is the same call
 * on both loaders, so {@link com.deds.api.menu.MenuScreens} keeps one body and
 * a duplicate binding still throws vanilla's own
 * {@code "Duplicate registration for <id>"}. Not API: mod code may never import
 * a mixin package.
 */
@Mixin(MenuScreens.class)
public interface MenuScreensInvoker {

    @Invoker("register")
    static <M extends AbstractContainerMenu, U extends Screen & MenuAccess<M>> void deds_api$register(
            MenuType<? extends M> type, MenuScreens.ScreenConstructor<M, U> factory) {
        throw new AssertionError("mixin invoker not applied");
    }
}
