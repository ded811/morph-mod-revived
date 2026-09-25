package com.deds.api.fabric;

import com.deds.api.fluid.FluidPort;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Turns the loader's transactional {@code Storage<FluidVariant>} back into
 * the simulate-then-commit shape a 1.6.4 port is written against (Ded's API
 * v2.1).
 *
 * <p>Every call — including the read-only {@link #storedFluid()} — opens its
 * own outer transaction and either commits it or lets {@code close()} abort
 * it, which is the documented way to perform a standalone transfer. The
 * consequence — no cross-call atomicity, and never call this from inside
 * another transaction — is stated on {@link FluidPort}.</p>
 */
record FabricFluidPort(Storage<FluidVariant> storage) implements FluidPort {

    @Override
    public long insert(Fluid fluid, long droplets, boolean simulate) {
        if (fluid == Fluids.EMPTY || droplets <= 0) {
            return 0;
        }
        try (Transaction transaction = Transaction.openOuter()) {
            long moved = storage.insert(FluidVariant.of(fluid), droplets,
                    transaction);
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
        try (Transaction transaction = Transaction.openOuter()) {
            long moved = storage.extract(FluidVariant.of(fluid), droplets,
                    transaction);
            if (!simulate) {
                transaction.commit();
            }
            return moved;
        }
    }

    @Override
    public Fluid storedFluid() {
        // NOT findStoredResource: that overload takes no transaction and opens
        // none (it only reads getResource() off every non-blank view), so it
        // answers "what is STORED", never "what can be pulled out" — an
        // insert-only neighbour would name its contents and the caller would
        // then spend a real extract that returns 0. FluidPort.storedFluid
        // promises "nothing EXTRACTABLE", so probe with
        // findExtractableResource, which trial-extracts each view. It needs a
        // transaction to do that in (it opens and aborts a nested one of its
        // own inside this one); this outer transaction is never committed, so
        // close() rolls the probe back either way.
        try (Transaction transaction = Transaction.openOuter()) {
            FluidVariant found =
                    StorageUtil.findExtractableResource(storage, transaction);
            return found == null ? Fluids.EMPTY : found.getFluid();
        }
    }
}
