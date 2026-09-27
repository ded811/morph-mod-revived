package com.deds.api.neoforge.transfer;

import com.deds.api.fluid.FluidTankView;

import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.List;
import java.util.function.BiFunction;

/**
 * A block entity's exposed tanks, as NeoForge's fluid capability
 * ({@code ResourceHandler<FluidResource>}, amounts in millibuckets).
 *
 * <p><b>Lazy.</b> NeoForge's capability caches keep the handler a lookup
 * returned; Fabric's lookups call the mod's function again every time. So
 * this handler asks the mod's function for the current tank list on every
 * call, and a cached handler sees a column that grew or a capacity that
 * changed, exactly as a fresh Fabric lookup would.</p>
 *
 * <p><b>The chain.</b> Insert and extract by resource walk the list as a
 * chain (see the Fabric context's {@code TankChain}). By index: an extract
 * reaches that one tank directly, as a Fabric storage view does; an insert
 * into a tank is refused unless every tank before it could hold the fluid,
 * because NeoForge's own movers insert index by index and would otherwise
 * step over a tank that holds a different fluid.</p>
 *
 * <p>Fluid a NeoForge caller offers is normalised like Fabric's
 * {@code FluidVariant.of} (flowing becomes source); a fluid carrying data
 * components is refused, as on Fabric.</p>
 */
public final class NeoTankHandler<T extends BlockEntity>
        implements ResourceHandler<FluidResource> {

    private static final int MB = TankOps.DROPLETS_PER_MB;

    private final T blockEntity;
    private final Direction side;
    private final BiFunction<T, Direction, List<FluidTankView>> tanks;

    NeoTankHandler(T blockEntity, Direction side,
            BiFunction<T, Direction, List<FluidTankView>> tanks) {
        this.blockEntity = blockEntity;
        this.side = side;
        this.tanks = tanks;
    }

    /** The mod's current tank list for this face; never null. */
    List<FluidTankView> tanks() {
        List<FluidTankView> list = tanks.apply(blockEntity, side);
        return list == null ? List.of() : list;
    }

    private FluidTankView tank(List<FluidTankView> list, int index) {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    /** The plain fluid a caller's resource stands for, or null if refused. */
    private static Fluid incoming(FluidResource resource) {
        if (!resource.isComponentsPatchEmpty()) {
            return null;
        }
        return TankOps.normalizeOrNull(resource.getFluid());
    }

    @Override
    public int size() {
        return tanks().size();
    }

    @Override
    public FluidResource getResource(int index) {
        FluidTankView tank = tank(tanks(), index);
        if (tank == null) {
            return FluidResource.EMPTY;
        }
        Fluid fluid = TankOps.normalizeOrNull(tank.fluid());
        return fluid == null ? FluidResource.EMPTY : FluidResource.of(fluid);
    }

    /**
     * Whole millibuckets, and 0 whenever {@link #getResource} is empty (a
     * tank holding nothing, or a fluid a Ded tank should never store), as
     * NeoForge's contract requires.
     */
    @Override
    public long getAmountAsLong(int index) {
        FluidTankView tank = tank(tanks(), index);
        if (tank == null) {
            return 0;
        }
        Fluid fluid = TankOps.normalizeOrNull(tank.fluid());
        return fluid == null || fluid == Fluids.EMPTY ? 0 : tank.amount() / MB;
    }

    @Override
    public long getCapacityAsLong(int index, FluidResource resource) {
        FluidTankView tank = tank(tanks(), index);
        if (tank == null) {
            return 0;
        }
        return resource.isEmpty() || isValid(index, resource)
                ? tank.capacity() / MB : 0;
    }

    @Override
    public boolean isValid(int index, FluidResource resource) {
        FluidTankView tank = tank(tanks(), index);
        Fluid fluid = incoming(resource);
        return tank != null && fluid != null && tank.accepts(fluid);
    }

    @Override
    public int insert(int index, FluidResource resource, int amount,
            TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        Fluid fluid = incoming(resource);
        List<FluidTankView> list = tanks();
        FluidTankView tank = tank(list, index);
        if (fluid == null || tank == null) {
            return 0;
        }
        for (int i = 0; i < index; i++) {
            if (!TankOps.canHold(list.get(i), fluid)) {
                return 0;
            }
        }
        return (int) (TankOps.insert(tank, fluid, (long) amount * MB, MB,
                transaction) / MB);
    }

    @Override
    public int extract(int index, FluidResource resource, int amount,
            TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        Fluid fluid = incoming(resource);
        FluidTankView tank = tank(tanks(), index);
        if (fluid == null || tank == null) {
            return 0;
        }
        return (int) (TankOps.extract(tank, fluid, (long) amount * MB, MB,
                transaction) / MB);
    }

    @Override
    public int insert(FluidResource resource, int amount,
            TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        Fluid fluid = incoming(resource);
        List<FluidTankView> list = tanks();
        if (fluid == null || list.isEmpty()) {
            return 0;
        }
        return (int) (TankOps.chainInsert(list, fluid, (long) amount * MB, MB,
                transaction) / MB);
    }

    @Override
    public int extract(FluidResource resource, int amount,
            TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        Fluid fluid = incoming(resource);
        List<FluidTankView> list = tanks();
        if (fluid == null || list.isEmpty()) {
            return 0;
        }
        return (int) (TankOps.chainExtract(list, fluid, (long) amount * MB, MB,
                transaction) / MB);
    }
}
