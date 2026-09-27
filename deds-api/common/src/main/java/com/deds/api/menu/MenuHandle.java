package com.deds.api.menu;

import com.deds.api.registry.RegistryHandle;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * A registered menu type (Ded's API v2.2) — what
 * {@link MenuRegistrar#register} hands back.
 *
 * <p>It is an ordinary {@link RegistryHandle}, so {@link #get()} yields the
 * {@link MenuType} the menu class needs for its
 * {@code AbstractContainerMenu(MenuType, int)} super constructor, exactly as
 * {@code BlockEntityRegistrar.register}'s handle serves a block entity's super
 * constructor. Hold it in a {@code static final} field.</p>
 *
 * <p><b>Why the second type parameter.</b> {@code D} is the opening data the
 * menu was registered with. It carries no members of its own and exists only
 * so the compiler can pair {@link MenuRegistrar#open} with the right payload:
 * without it, handing a menu registered for a {@code BlockPos} an
 * {@code Integer} would compile and then fail inside the loader's stream
 * codec, on the wire, at the moment a player right-clicks. With it, the
 * mistake is a compile error.</p>
 *
 * <p><b>Do not implement this interface.</b> Only handles produced by
 * {@link MenuRegistrar#register} are accepted by {@link MenuRegistrar#open},
 * which rejects any other implementation with an
 * {@link IllegalArgumentException}.</p>
 *
 * @param <T> the menu class
 * @param <D> the opening data type this menu is registered with
 */
public interface MenuHandle<T extends AbstractContainerMenu, D>
        extends RegistryHandle<MenuType<T>> {
}
