package com.deds.api.fabric;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * One invocation of {@code MenuRegistrar.open}, in the shape the loader wants
 * (Ded's API v2.2).
 *
 * <p>26.2 vanilla has no extra-opening-data mechanism at all —
 * {@code ClientboundOpenScreenPacket} is {@code (containerId, type, title)} and
 * nothing more (javap) — so the loader supplies one: a menu provider that also
 * answers {@code getScreenOpeningData}, which the loader's own
 * {@code ServerPlayer.openMenu} hook notices and answers with a custom payload
 * carrying the data through the registered stream codec instead of the vanilla
 * packet.</p>
 *
 * <p><b>The pairing is mandatory, not stylistic.</b> The loader throws
 * {@code IllegalArgumentException} if an {@code ExtendedMenuType} is opened
 * through a plain provider, and {@code ExtendedMenuType.create(int, Inventory)}
 * — the two-argument overload a vanilla open would reach for — is hard-coded to
 * throw {@code UnsupportedOperationException} (both bytecode-verified against
 * fabric-menu-api-v1 2.0.16, the copy inside Fabric API 0.155.2+26.2). Every
 * type this API registers is an {@code ExtendedMenuType}, so every open must
 * come through here.</p>
 *
 * @param type  the registered menu type
 * @param title the screen title
 * @param data  the opening data, already validated non-null by the caller
 * @param <T>   the menu class
 * @param <D>   the opening data type
 */
record FabricMenuOpening<T extends AbstractContainerMenu, D>(
        ExtendedMenuType<T, D> type, Component title, D data)
        implements ExtendedMenuProvider<D> {

    @Override
    public Component getDisplayName() {
        return title;
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId,
            Inventory playerInventory, Player player) {
        return type.create(containerId, playerInventory, data);
    }

    @Override
    public D getScreenOpeningData(ServerPlayer player) {
        return data;
    }
}
