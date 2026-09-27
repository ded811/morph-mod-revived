package com.deds.api.neoforge.transfer;

import com.deds.api.fluid.FluidPort;
import com.deds.api.fluid.FluidRegistrar;
import com.deds.api.fluid.FluidTankView;
import com.deds.api.id.BId;
import com.deds.api.neoforge.mixin.FluidAccessor;
import com.deds.api.registry.RegistryHandle;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.BucketResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The NeoForge fluid registrar, the counterpart of the Fabric context's fluid
 * half: fluids register immediately (and get their {@link DedFluidType}),
 * tanks are published through NeoForge's fluid capability, ports and held
 * containers work in droplets as on Fabric (see {@link TankOps} for the unit
 * conversion at NeoForge's edge).
 */
public final class NeoFluids implements FluidRegistrar {

    /** Every Ded's fluid's type, both halves of a pair mapped to one type. */
    private static final Map<Fluid, DedFluidType> TYPES = new ConcurrentHashMap<>();

    private record PendingType(Identifier id, DedFluidType type) {
    }

    private static final List<PendingType> PENDING_TYPES = new ArrayList<>();

    private record Exposure<T extends BlockEntity>(
            RegistryHandle<BlockEntityType<T>> type,
            BiFunction<T, Direction, List<FluidTankView>> tanks) {
    }

    private static final List<Exposure<?>> EXPOSURES = new ArrayList<>();

    /** Every item registered through Ded's API, for the bucket capability. */
    private static final List<Item> DED_ITEMS = new ArrayList<>();

    private static volatile boolean capabilitiesRegistered;

    private final String modId;
    private final Consumer<String> requireWindow;

    public NeoFluids(String modId, Consumer<String> requireWindow) {
        this.modId = modId;
        this.requireWindow = requireWindow;
    }

    /** From the item registrar: remembers a Ded's item. */
    public static void trackItem(Item item) {
        synchronized (DED_ITEMS) {
            DED_ITEMS.add(item);
        }
    }

    /** Whether {@code item} was registered through Ded's API. */
    static boolean isDedItem(Item item) {
        synchronized (DED_ITEMS) {
            return DED_ITEMS.contains(item);
        }
    }

    // --- registration ---

    @Override
    public RegistryHandle<Fluid> register(String name, Fluid fluid) {
        requireWindow.accept("fluid '" + name + "'");
        Identifier id = Identifier.fromNamespaceAndPath(modId, name);
        Registry.register(BuiltInRegistries.FLUID,
                ResourceKey.create(Registries.FLUID, id), fluid);
        DedFluidType type = counterpartType(fluid);
        if (type == null) {
            type = new DedFluidType(fluid);
            synchronized (PENDING_TYPES) {
                PENDING_TYPES.add(new PendingType(id, type));
            }
        }
        TYPES.put(fluid, type);
        ((FluidAccessor) fluid).deds_api$setFluidType(type);
        return new RegistryHandleImpl(BId.of(modId, name), fluid);
    }

    private record RegistryHandleImpl(BId id, Fluid value)
            implements RegistryHandle<Fluid> {
        @Override
        public Fluid get() {
            return value;
        }
    }

    /**
     * The type of the other half of a source/flowing pair, if that half was
     * already registered through Ded's API. Looked up in this API's own
     * table (never the counterpart's {@code getFluidType()}, which is not
     * safe during registration), and a pair whose halves cannot be asked yet
     * simply gets a type per half.
     */
    private static DedFluidType counterpartType(Fluid fluid) {
        if (!(fluid instanceof FlowingFluid flowing)) {
            return null;
        }
        try {
            Fluid other = flowing.getSource();
            if (other == fluid) {
                other = flowing.getFlowing();
            }
            return other == null ? null : TYPES.get(other);
        } catch (RuntimeException notBoundYet) {
            return null;
        }
    }

    /** From DedsApiNeoForge, LOWEST of the fluid-type registry event. */
    public static void registerFluidTypes(RegisterEvent event) {
        if (!event.getRegistryKey().equals(NeoForgeRegistries.Keys.FLUID_TYPES)) {
            return;
        }
        synchronized (PENDING_TYPES) {
            for (PendingType pending : PENDING_TYPES) {
                event.register(NeoForgeRegistries.Keys.FLUID_TYPES, pending.id(),
                        pending::type);
            }
            PENDING_TYPES.clear();
        }
    }

    // --- tanks ---

    @Override
    public <T extends BlockEntity> void exposeTanks(
            RegistryHandle<BlockEntityType<T>> type,
            Function<T, List<FluidTankView>> tanks) {
        exposeTanks(type, (blockEntity, side) -> tanks.apply(blockEntity));
    }

    /**
     * Queued, and published through NeoForge's fluid capability when NeoForge
     * collects capabilities (after the registries are complete). The mod's
     * function answers every lookup, as on Fabric: null or an empty list is
     * "no fluid connection on that side" (the capability answers null);
     * anything else is a lazy {@link NeoTankHandler} that asks the function
     * again on each call.
     */
    @Override
    public <T extends BlockEntity> void exposeTanks(
            RegistryHandle<BlockEntityType<T>> type,
            BiFunction<T, Direction, List<FluidTankView>> tanks) {
        synchronized (EXPOSURES) {
            if (capabilitiesRegistered) {
                throw new IllegalStateException("Ded's API: exposeTanks for "
                        + type.id() + " after NeoForge collected capabilities; "
                        + "call it from DedsMod.onInitialize");
            }
            EXPOSURES.add(new Exposure<>(type, tanks));
        }
    }

    /** From DedsApiNeoForge: publishes every queued exposure. */
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
        BiFunction<T, Direction, List<FluidTankView>> tanks = exposure.tanks();
        CapabilityRefresh.track(type, CapabilityRefresh.Kind.FLUID,
                (blockEntity, side) -> connected(tanks.apply(cast(blockEntity), side)));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, type, (blockEntity, side) -> {
            boolean connected = connected(tanks.apply(blockEntity, side));
            CapabilityRefresh.answered(blockEntity, CapabilityRefresh.Kind.FLUID,
                    side, connected);
            return connected ? new NeoTankHandler<>(blockEntity, side, tanks) : null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends BlockEntity> T cast(BlockEntity blockEntity) {
        return (T) blockEntity;
    }

    private static boolean connected(List<FluidTankView> tanks) {
        return tanks != null && !tanks.isEmpty();
    }

    /**
     * From DedsApiNeoForge, LOW priority of the capability event: NeoForge's
     * own bucket handler for every bucket item SUBCLASS registered through
     * Ded's API whose fluid maps back to it. NeoForge covers only the plain
     * {@code BucketItem} class; Fabric's bucket support covers subclasses, so
     * without this another mod's tank could fill a vanilla bucket from a Ded's
     * fluid but not the mod's own bucket. Only Ded's items: other mods decide
     * for their own.
     */
    public static void registerBucketCapabilities(RegisterCapabilitiesEvent event) {
        List<Item> items;
        synchronized (DED_ITEMS) {
            items = List.copyOf(DED_ITEMS);
        }
        for (Item item : items) {
            if (item instanceof BucketItem bucket
                    && bucket.getClass() != BucketItem.class
                    && bucket.content != Fluids.EMPTY
                    && bucket.content.getBucket() == bucket
                    && !event.isItemRegistered(Capabilities.Fluid.ITEM, item)) {
                event.registerItem(Capabilities.Fluid.ITEM,
                        (stack, access) -> new BucketResourceHandler(access), item);
            }
        }
    }

    // --- ports and held containers ---

    @Override
    public FluidPort port(Level level, BlockPos pos, Direction side) {
        ResourceHandler<FluidResource> handler =
                level.getCapability(Capabilities.Fluid.BLOCK, pos, side);
        if (handler == null) {
            return null;
        }
        return handler instanceof NeoTankHandler<?> own
                ? new FluidPorts.Own(own) : new FluidPorts.Foreign(handler);
    }

    @Override
    public boolean useHeldContainer(List<FluidTankView> tanks, Player player,
            InteractionHand hand) {
        return HeldContainer.interact(tanks, player, hand);
    }
}
