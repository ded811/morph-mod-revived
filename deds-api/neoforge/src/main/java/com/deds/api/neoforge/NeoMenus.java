package com.deds.api.neoforge;

import com.deds.api.id.BId;
import com.deds.api.menu.MenuHandle;
import com.deds.api.menu.MenuRegistrar;

import io.netty.handler.codec.DecoderException;

import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.common.util.FriendlyByteBufUtil;
import net.neoforged.neoforge.network.IContainerFactory;
import net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The NeoForge menu registrar, the counterpart of the Fabric context's
 * {@code ExtendedMenuType} path.
 *
 * <p>Every menu is a NeoForge {@code IContainerFactory} menu type, whose
 * opening packet carries extra bytes: a one-byte format marker, then the
 * data encoded with the menu's codec. The marker means the bytes are never
 * empty, so NeoForge always sends its extra-data payload and never falls back
 * to the vanilla packet, which has no room for data. As on Fabric, the server
 * builds its menu from the caller's own data object and the client from a
 * decoded copy (both loaders encode even in singleplayer).</p>
 */
final class NeoMenus implements MenuRegistrar {

    /** First byte of every Ded's menu opening; bump if the layout changes. */
    private static final byte FORMAT = 1;

    private final String modId;
    private final Consumer<String> requireWindow;

    NeoMenus(String modId, Consumer<String> requireWindow) {
        this.modId = modId;
        this.requireWindow = requireWindow;
    }

    private record NeoMenuHandle<T extends AbstractContainerMenu, D>(
            BId id, MenuType<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, D> codec,
            MenuRegistrar.Factory<T, D> factory) implements MenuHandle<T, D> {
        @Override
        public MenuType<T> get() {
            return type;
        }
    }

    /** Builds the client's menu from the opening bytes. */
    private record DedsMenuFactory<T extends AbstractContainerMenu, D>(
            StreamCodec<? super RegistryFriendlyByteBuf, D> codec,
            MenuRegistrar.Factory<T, D> factory) implements IContainerFactory<T> {

        @Override
        public T create(int containerId, Inventory inventory,
                RegistryFriendlyByteBuf buf) {
            if (buf == null) {
                // Fabric's ExtendedMenuType refuses the data-less create the
                // same way.
                throw new UnsupportedOperationException("Ded's API menus carry "
                        + "opening data; open them with ModContext.menus().open");
            }
            byte format = buf.readByte();
            if (format != FORMAT) {
                throw new DecoderException("unknown Ded's API menu data format "
                        + format);
            }
            D data = codec.decode(buf);
            if (buf.isReadable()) {
                throw new DecoderException(buf.readableBytes()
                        + " bytes left over after decoding Ded's API menu data");
            }
            return factory.create(containerId, inventory, data);
        }
    }

    @Override
    public <T extends AbstractContainerMenu, D> MenuHandle<T, D> register(
            String name, StreamCodec<? super RegistryFriendlyByteBuf, D> dataCodec,
            MenuRegistrar.Factory<T, D> factory) {
        Objects.requireNonNull(factory, "menu factory cannot be null");
        Objects.requireNonNull(dataCodec, "stream codec cannot be null");
        requireWindow.accept("menu '" + name + "'");
        MenuType<T> type = IMenuTypeExtension.create(
                new DedsMenuFactory<>(dataCodec, factory));
        Identifier id = Identifier.fromNamespaceAndPath(modId, name);
        Registry.register(BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, id), type);
        return new NeoMenuHandle<>(BId.of(modId, name), type, dataCodec, factory);
    }

    /**
     * Opens {@code menu} for {@code player}. The data is encoded BEFORE the
     * menu opens, so a codec that throws does so here, from the caller's
     * stack, before any container is closed or created (on Fabric the same
     * failure surfaces later, on the network thread).
     *
     * <p>A connection that never negotiated NeoForge's extra-data payload (a
     * gametest mock player; a vanilla client, which cannot join a server with
     * modded menus anyway) would make NeoForge throw on send. Such a player
     * gets the menu opened with no extra data instead: the server state is the
     * same, and there is no client to show a screen.</p>
     */
    @Override
    public <T extends AbstractContainerMenu, D> void open(ServerPlayer player,
            MenuHandle<T, D> menu, Component title, D data) {
        Objects.requireNonNull(data, "menu opening data must not be null");
        if (!(menu instanceof NeoMenuHandle<T, D> handle)) {
            throw new IllegalArgumentException("menu handle " + menu.id()
                    + " did not come from ModContext.menus().register()");
        }
        MenuProvider provider = new SimpleMenuProvider(
                (containerId, inventory, p) ->
                        handle.factory().create(containerId, inventory, data),
                title);
        if (player.connection != null
                && player.connection.hasChannel(AdvancedOpenScreenPayload.TYPE)) {
            byte[] bytes = FriendlyByteBufUtil.writeCustomData(buf -> {
                buf.writeByte(FORMAT);
                handle.codec().encode(buf, data);
            }, player.registryAccess());
            player.openMenu(provider, buf -> buf.writeBytes(bytes));
        } else {
            player.openMenu(provider);
        }
    }
}
