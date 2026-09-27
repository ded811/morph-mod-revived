package com.deds.api.menu;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Menus for one mod (Ded's API v2.2) — {@code ModContext.menus()}.
 *
 * <p><b>Deliberately two methods.</b> Register a menu type, and open one for a
 * player with the data the client needs to rebuild it. That is the whole seam.
 * There is no slot helper, no inventory adapter, no progress-bar syncing and no
 * screen layout: {@link AbstractContainerMenu} subclasses, {@code Slot},
 * {@code Container}, {@code ContainerData} and
 * {@code ContainerLevelAccess.create} are ordinary vanilla surface that mod
 * code uses directly (docs/ARCHITECTURE.md, "Level 1 pragmatism"). What goes
 * through the API is the churn-prone, loader-specific part: how a menu type is
 * <em>built and registered</em>, and how <em>extra opening data</em> reaches
 * the client.</p>
 *
 * <p><b>Why extra opening data is not optional here.</b> Vanilla's
 * {@code ClientboundOpenScreenPacket} carries a container id, a menu type and a
 * title — and nothing else (javap, 26.2). A block entity's menu therefore has
 * no way to tell the client <em>which</em> block it belongs to, so the client
 * cannot resolve the block entity it is meant to show. Every loader grows its
 * own answer to that, all of it outside vanilla, so it is here.</p>
 *
 * <p>The client half — binding a screen to a registered menu — is
 * {@link MenuScreens}, which is client-only and therefore deliberately not on
 * this interface: this one is reached through {@code ModContext}, which a
 * dedicated server holds too.</p>
 */
public interface MenuRegistrar {

    /**
     * Creates a menu instance. Runs on <b>both</b> sides: on the server when
     * {@link #open} is called, and on the client when the opening packet
     * arrives, with the same {@code data} both times.
     *
     * <p>Mirrors the shape of the vanilla menu constructor, so a constructor
     * reference like {@code XpBottlerMenu::new} works.</p>
     *
     * <p><b>The client run has no block entity to trust.</b> A block-position
     * factory must tolerate a position whose block entity is missing or of the
     * wrong type — the chunk can be unloaded, and the data is player-supplied
     * as far as the server is concerned. Build an empty menu rather than
     * throwing.</p>
     *
     * @param <T> the menu class
     * @param <D> the opening data type
     */
    @FunctionalInterface
    interface Factory<T extends AbstractContainerMenu, D> {

        /**
         * @param containerId     the vanilla sync id; pass it straight to the
         *                        menu's super constructor
         * @param playerInventory the inventory of the player the menu is for
         * @param data            the value {@link #open} was given, decoded on
         *                        the client with the registered codec
         */
        T create(int containerId, Inventory playerInventory, D data);
    }

    /**
     * Registers a menu type in the mod's namespace. Call during
     * {@code DedsMod.onInitialize} on <b>both</b> sides — registration must be
     * symmetric, like {@code ModContext.net()}'s messages, because the client
     * looks the type up by id when the opening packet arrives.
     *
     * <p>{@link StreamCodec} and {@link RegistryFriendlyByteBuf} are accepted
     * vanilla surface (docs/ARCHITECTURE.md). For the common case — a menu
     * belonging to a block entity — the codec is vanilla's own
     * {@code BlockPos.STREAM_CODEC} and {@code D} is {@code BlockPos}; a menu
     * keyed by something else (an inventory slot index, say) uses
     * {@code ByteBufCodecs.VAR_INT} and {@code Integer}.</p>
     *
     * <p><b>There is no data-free overload</b>, and that is deliberate rather
     * than an oversight: a menu with genuinely nothing to say still has to name
     * a codec, and no consumer has yet wanted one. Adding an overload later is
     * additive (docs/API-COMPATIBILITY.md §4); removing an unused one is not.
     * </p>
     *
     * @param name      registry path within the mod's namespace
     * @param dataCodec encodes the opening data for the wire, both directions
     * @param factory   builds the menu on each side (usually a constructor
     *                  reference)
     * @param <T>       the menu class
     * @param <D>       the opening data type
     * @return handle to the registered type — needed by the menu's super
     *         constructor and by {@link #open} and {@link MenuScreens#register}
     */
    <T extends AbstractContainerMenu, D> MenuHandle<T, D> register(String name,
            StreamCodec<? super RegistryFriendlyByteBuf, D> dataCodec,
            Factory<T, D> factory);

    /**
     * Opens {@code menu} for {@code player}, carrying {@code data} to their
     * client. Server side only — {@link ServerPlayer} rather than
     * {@code Player} is the signature saying so
     * (docs/CLIENT-SERVER-PLAYBOOK.md).
     *
     * <p>The usual call site is a block's use handler:
     * {@code ctx.menus().open(serverPlayer, MENU, block.getName(), pos)}. The
     * factory runs once here to build the server-side menu and once on the
     * client with the decoded copy of {@code data}; the client then shows
     * whatever screen {@link MenuScreens#register} bound to this menu, or logs
     * that none was bound and shows nothing.</p>
     *
     * <p>Closing is vanilla's job — the player's own inventory key, or
     * {@code ServerPlayer.closeContainer()}. There is no {@code close} here
     * because nothing needs one.</p>
     *
     * @param player the player to open it for
     * @param menu   a handle from {@link #register}
     * @param title  the screen title, e.g.
     *               {@code Component.translatable("container." + modId + ".xp_bottler")}
     * @param data   the opening data; <b>must not be null</b> — the loader's
     *               client drops an opening packet whose data is null, so the
     *               player would see no screen at all
     * @param <T>    the menu class
     * @param <D>    the opening data type, fixed by {@code menu}
     * @throws NullPointerException     if {@code data} is null
     * @throws IllegalArgumentException if {@code menu} did not come from
     *                                  {@link #register}
     */
    <T extends AbstractContainerMenu, D> void open(ServerPlayer player,
            MenuHandle<T, D> menu, Component title, D data);
}
