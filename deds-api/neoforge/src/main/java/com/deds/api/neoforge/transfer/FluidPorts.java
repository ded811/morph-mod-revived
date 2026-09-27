package com.deds.api.neoforge.transfer;

import com.deds.api.fluid.FluidPort;
import com.deds.api.fluid.FluidTankView;

import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.List;

/**
 * The two {@link FluidPort}s {@code FluidRegistrar.port} hands out on
 * NeoForge. Both mirror {@code FabricFluidPort}: an empty fluid or a
 * non-positive amount moves nothing without a transaction, every call is its
 * own root transaction, a simulation is rolled back, and the fluid is
 * normalised like Fabric's {@code FluidVariant.of} (a flowing fluid means its
 * source; a fluid that is neither throws {@link IllegalArgumentException}).
 */
final class FluidPorts {

    private FluidPorts() {
    }

    /**
     * A Ded's API tank on the other side: the chain is driven directly, in
     * droplets, so Ded-to-Ded transfers are exact (100 droplets move 100),
     * exactly as on Fabric.
     */
    record Own(NeoTankHandler<?> handler) implements FluidPort {

        @Override
        public long insert(Fluid fluid, long droplets, boolean simulate) {
            if (fluid == Fluids.EMPTY || droplets <= 0) {
                return 0;
            }
            Fluid normalized = TankOps.normalize(fluid);
            try (Transaction transaction = Transaction.openRoot()) {
                long moved = TankOps.chainInsert(handler.tanks(), normalized,
                        droplets, 1, transaction);
                if (!simulate) {
                    transaction.commit();
                }
                return moved;
            }
        }

        @Override
        public long extract(Fluid fluid, long droplets, boolean simulate) {
            if (fluid == Fluids.EMPTY || droplets <= 0) {
                return 0;
            }
            Fluid normalized = TankOps.normalize(fluid);
            try (Transaction transaction = Transaction.openRoot()) {
                long moved = TankOps.chainExtract(handler.tanks(), normalized,
                        droplets, 1, transaction);
                if (!simulate) {
                    transaction.commit();
                }
                return moved;
            }
        }

        /**
         * What Fabric's {@code StorageUtil.findExtractableResource} answers
         * for these tanks: the first tank, in list order, that holds any
         * fluid. (So for {@code [empty, water]} it is water, although an
         * extract of water then moves nothing, because the empty first tank
         * ends the chain. Fabric does the same.)
         */
        @Override
        public Fluid storedFluid() {
            for (FluidTankView tank : handler.tanks()) {
                if (tank.fluid() != Fluids.EMPTY && tank.amount() > 0) {
                    Fluid fluid = TankOps.normalizeOrNull(tank.fluid());
                    if (fluid != null) {
                        return fluid;
                    }
                }
            }
            return Fluids.EMPTY;
        }
    }

    /**
     * Any other NeoForge fluid handler (another mod's tank, a vanilla
     * cauldron): whole millibuckets only. A request is rounded DOWN to whole
     * millibuckets before it is offered (1 to 80 droplets move nothing; 100
     * move 81), and what moved is reported back in droplets.
     */
    record Foreign(ResourceHandler<FluidResource> handler) implements FluidPort {

        private static int toMb(long droplets) {
            return (int) Math.min(droplets / TankOps.DROPLETS_PER_MB,
                    Integer.MAX_VALUE);
        }

        @Override
        public long insert(Fluid fluid, long droplets, boolean simulate) {
            if (fluid == Fluids.EMPTY || droplets <= 0) {
                return 0;
            }
            FluidResource resource = FluidResource.of(TankOps.normalize(fluid));
            int mb = toMb(droplets);
            if (mb == 0) {
                return 0;
            }
            try (Transaction transaction = Transaction.openRoot()) {
                long moved = handler.insert(resource, mb, transaction);
                if (!simulate) {
                    transaction.commit();
                }
                return moved * TankOps.DROPLETS_PER_MB;
            }
        }

        @Override
        public long extract(Fluid fluid, long droplets, boolean simulate) {
            if (fluid == Fluids.EMPTY || droplets <= 0) {
                return 0;
            }
            FluidResource resource = FluidResource.of(TankOps.normalize(fluid));
            int mb = toMb(droplets);
            if (mb == 0) {
                return 0;
            }
            try (Transaction transaction = Transaction.openRoot()) {
                long moved = handler.extract(resource, mb, transaction);
                if (!simulate) {
                    transaction.commit();
                }
                return moved * TankOps.DROPLETS_PER_MB;
            }
        }

        @Override
        public Fluid storedFluid() {
            FluidResource found = ResourceHandlerUtil.findExtractableResource(
                    handler, resource -> true, null);
            return found == null ? Fluids.EMPTY : found.getFluid();
        }
    }
}
