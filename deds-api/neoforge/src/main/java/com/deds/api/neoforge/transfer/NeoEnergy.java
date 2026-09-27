package com.deds.api.neoforge.transfer;

import com.deds.api.energy.EnergyContainerItem;
import com.deds.api.energy.EnergyPort;
import com.deds.api.energy.EnergyRegistrar;
import com.deds.api.energy.EnergyStorageView;
import com.deds.api.energy.ItemEnergy;
import com.deds.api.registry.RegistryHandle;

import com.mojang.serialization.Codec;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The NeoForge energy registrar, the counterpart of the Fabric context's Team
 * Reborn Energy bridge, over NeoForge's own energy capability (so every
 * NeoForge mod that speaks energy sees Ded's machines, and Ded's ports see
 * theirs). 1 RF is 1 FE: both are ints, nothing is converted.
 *
 * <p>Items: {@link ItemEnergy} stores a chargeable item's energy in the data
 * component {@code deds_api:energy} (a non-negative long, removed at zero so a
 * drained item stacks with a fresh one, as Team Reborn's component does), and
 * every {@link EnergyContainerItem} answers NeoForge's item energy capability
 * with Team Reborn's per-item arithmetic ({@link DedItemEnergyHandler}).
 * Fabric keeps Team Reborn's own component id; item data does not move between
 * loaders, and neither do worlds.</p>
 */
public final class NeoEnergy implements EnergyRegistrar {

    public static final Identifier COMPONENT_ID =
            Identifier.fromNamespaceAndPath("deds_api", "energy");

    private record Exposure<T extends BlockEntity>(
            RegistryHandle<BlockEntityType<T>> type,
            BiFunction<T, Direction, EnergyStorageView> storage) {
    }

    private static final List<Exposure<?>> EXPOSURES = new ArrayList<>();

    private static volatile boolean capabilitiesRegistered;

    private static volatile DataComponentType<Long> component;

    /** The installed item energy component (null before the window). */
    static DataComponentType<Long> component() {
        return component;
    }

    /**
     * From DedsApiNeoForge, at the start of the registry window, before any
     * mod's {@code onInitialize}: registers the item energy component and
     * installs it into {@link ItemEnergy}.
     */
    public static void registerComponent() {
        if (component != null) {
            return;
        }
        DataComponentType<Long> type = DataComponentType.<Long>builder()
                .persistent(Codec.LONG.validate(value -> value >= 0
                        ? com.mojang.serialization.DataResult.success(value)
                        : com.mojang.serialization.DataResult.error(
                                () -> "energy must not be negative: " + value)))
                .networkSynchronized(ByteBufCodecs.VAR_LONG)
                .build();
        Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, COMPONENT_ID, type);
        component = type;
        ItemEnergy.install(type);
    }

    @Override
    public <T extends BlockEntity> void exposeStorage(
            RegistryHandle<BlockEntityType<T>> type,
            BiFunction<T, Direction, EnergyStorageView> storage) {
        synchronized (EXPOSURES) {
            if (capabilitiesRegistered) {
                throw new IllegalStateException("Ded's API: exposeStorage for "
                        + type.id() + " after NeoForge collected capabilities; "
                        + "call it from DedsMod.onInitialize");
            }
            EXPOSURES.add(new Exposure<>(type, storage));
        }
    }

    /** From DedsApiNeoForge: publishes every queued block exposure. */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        synchronized (EXPOSURES) {
            capabilitiesRegistered = true;
            for (Exposure<?> exposure : EXPOSURES) {
                register(event, exposure);
            }
        }
    }

    private static <T extends BlockEntity> void register(
            RegisterCapabilitiesEvent event, Exposure<T> exposure) {
        BlockEntityType<T> type = exposure.type().get();
        BiFunction<T, Direction, EnergyStorageView> storage = exposure.storage();
        CapabilityRefresh.track(type, CapabilityRefresh.Kind.ENERGY,
                (blockEntity, side) -> storage.apply(cast(blockEntity), side) != null);
        event.registerBlockEntity(Capabilities.Energy.BLOCK, type, (blockEntity, side) -> {
            boolean connected = storage.apply(blockEntity, side) != null;
            CapabilityRefresh.answered(blockEntity, CapabilityRefresh.Kind.ENERGY,
                    side, connected);
            return connected ? new NeoEnergyHandler<>(blockEntity, side, storage) : null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends BlockEntity> T cast(BlockEntity blockEntity) {
        return (T) blockEntity;
    }

    /**
     * From DedsApiNeoForge, LOWEST priority of the capability event: every
     * {@link EnergyContainerItem} in the item registry (any mod's, as Fabric's
     * fallback provider covers any mod's) answers the item energy capability,
     * registered last so a mod's own handler for its item is asked first.
     */
    public static void registerItemCapabilities(RegisterCapabilitiesEvent event) {
        for (Item item : BuiltInRegistries.ITEM) {
            if (item instanceof EnergyContainerItem container) {
                event.registerItem(Capabilities.Energy.ITEM,
                        (stack, access) -> DedItemEnergyHandler.create(access,
                                container.getMaxEnergyStored(stack),
                                container.getMaxReceive(stack),
                                container.getMaxExtract(stack)),
                        item);
            }
        }
    }

    @Override
    public EnergyPort port(Level level, BlockPos pos, Direction side) {
        EnergyHandler handler = level.getCapability(Capabilities.Energy.BLOCK, pos, side);
        return handler == null ? null : new Port(handler);
    }

    /**
     * {@code FabricEnergyPort} over a NeoForge energy handler: a non-positive
     * amount moves nothing, every call is its own root transaction, a
     * simulation is rolled back.
     */
    private record Port(EnergyHandler handler) implements EnergyPort {

        @Override
        public int insert(int amount, boolean simulate) {
            if (amount <= 0) {
                return 0;
            }
            try (Transaction transaction = Transaction.openRoot()) {
                int moved = handler.insert(amount, transaction);
                if (!simulate) {
                    transaction.commit();
                }
                return moved;
            }
        }

        @Override
        public int extract(int amount, boolean simulate) {
            if (amount <= 0) {
                return 0;
            }
            try (Transaction transaction = Transaction.openRoot()) {
                int moved = handler.extract(amount, transaction);
                if (!simulate) {
                    transaction.commit();
                }
                return moved;
            }
        }
    }
}
