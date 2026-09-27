package com.deds.api.neoforge.transfer;

import com.deds.api.fluid.FluidAmounts;
import com.deds.api.fluid.FluidTankView;

import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.List;

/**
 * The tank arithmetic of the Fabric backend ({@code FabricTankStorage} and
 * its chain), shared by every NeoForge fluid adapter, with one extra
 * parameter: the STEP an amount must be a multiple of.
 *
 * <p>Ded's API counts fluid in droplets (81,000 a bucket); NeoForge counts in
 * millibuckets (1,000 a bucket), so one millibucket is exactly
 * {@link #DROPLETS_PER_MB} droplets. Transfers between Ded's own tanks run with
 * step 1 and are droplet-exact, as on Fabric. Transfers offered by a NeoForge
 * caller run with step {@code DROPLETS_PER_MB}: what moves is rounded DOWN to
 * whole millibuckets, so no droplet is ever created or destroyed; a
 * remainder of 1 to 80 droplets simply stays in the tank, invisible to
 * NeoForge callers and still exact through Ded's own ports.</p>
 */
final class TankOps {

    /** Droplets per NeoForge millibucket: 81,000 / 1,000. */
    static final int DROPLETS_PER_MB =
            (int) (FluidAmounts.BUCKET / FluidType.BUCKET_VOLUME);

    static {
        if (FluidAmounts.BUCKET % FluidType.BUCKET_VOLUME != 0) {
            throw new IllegalStateException("Ded's API: a bucket ("
                    + FluidAmounts.BUCKET + " droplets) is not a whole number of "
                    + "NeoForge millibuckets (" + FluidType.BUCKET_VOLUME + ")");
        }
    }

    private TankOps() {
    }

    /**
     * The fluid Fabric's {@code FluidVariant.of} would store: a flowing fluid
     * becomes its source. A fluid that is neither a source nor a
     * {@code FlowingFluid} cannot be expressed, and throws
     * {@link IllegalArgumentException} as Fabric does.
     */
    static Fluid normalize(Fluid fluid) {
        if (fluid == Fluids.EMPTY || fluid.isSource(fluid.defaultFluidState())) {
            return fluid;
        }
        if (fluid instanceof FlowingFluid flowing) {
            return flowing.getSource();
        }
        throw new IllegalArgumentException("Cannot convert flowing fluid "
                + fluid + " into a still fluid.");
    }

    /** {@link #normalize}, or {@code null} for a fluid it would refuse. */
    static Fluid normalizeOrNull(Fluid fluid) {
        try {
            return normalize(fluid);
        } catch (IllegalArgumentException refused) {
            return null;
        }
    }

    /** Rounds {@code amount} down to a multiple of {@code step}. */
    private static long floor(long amount, int step) {
        return step == 1 ? amount : amount - amount % step;
    }

    /** Fabric's per-tank insert, in multiples of {@code step}. */
    static long insert(FluidTankView tank, Fluid incoming, long maxAmount,
            int step, TransactionContext transaction) {
        if (!tank.accepts(incoming)) {
            return 0;
        }
        Fluid held = tank.fluid();
        if (held != Fluids.EMPTY && held != incoming) {
            return 0;
        }
        long inserted = floor(Math.min(maxAmount, tank.capacity() - tank.amount()), step);
        if (inserted <= 0) {
            return 0;
        }
        long before = tank.amount();
        DedTransferJournal.record(transaction, new TankUndo(tank, held, before));
        tank.set(incoming, before + inserted);
        return inserted;
    }

    /** Fabric's per-tank extract, in multiples of {@code step}. */
    static long extract(FluidTankView tank, Fluid fluid, long maxAmount,
            int step, TransactionContext transaction) {
        Fluid held = tank.fluid();
        if (fluid != held || held == Fluids.EMPTY) {
            return 0;
        }
        long before = tank.amount();
        long extracted = floor(Math.min(maxAmount, before), step);
        if (extracted <= 0) {
            return 0;
        }
        DedTransferJournal.record(transaction, new TankUndo(tank, held, before));
        long left = before - extracted;
        tank.set(left == 0 ? Fluids.EMPTY : held, left);
        return extracted;
    }

    /** Fabric's {@code canHold}: identity only, room ignored. */
    static boolean canHold(FluidTankView tank, Fluid incoming) {
        Fluid held = tank.fluid();
        return (held == Fluids.EMPTY || held == incoming) && tank.accepts(incoming);
    }

    /** Fabric's {@code holds}: an empty tank holds nothing. */
    static boolean holds(FluidTankView tank, Fluid fluid) {
        return fluid != Fluids.EMPTY && fluid == tank.fluid();
    }

    /** Fabric's chain insert: a tank that refuses on identity ends the walk. */
    static long chainInsert(List<FluidTankView> tanks, Fluid incoming,
            long maxAmount, int step, TransactionContext transaction) {
        if (tanks.size() == 1) {
            return insert(tanks.get(0), incoming, maxAmount, step, transaction);
        }
        long moved = 0;
        for (FluidTankView tank : tanks) {
            if (!canHold(tank, incoming)) {
                break;
            }
            moved += insert(tank, incoming, maxAmount - moved, step, transaction);
            if (moved >= maxAmount) {
                break;
            }
        }
        return moved;
    }

    /** Fabric's chain extract: a tank not holding the fluid ends the walk. */
    static long chainExtract(List<FluidTankView> tanks, Fluid fluid,
            long maxAmount, int step, TransactionContext transaction) {
        if (tanks.size() == 1) {
            return extract(tanks.get(0), fluid, maxAmount, step, transaction);
        }
        long moved = 0;
        for (FluidTankView tank : tanks) {
            if (!holds(tank, fluid)) {
                break;
            }
            moved += extract(tank, fluid, maxAmount - moved, step, transaction);
            if (moved >= maxAmount) {
                break;
            }
        }
        return moved;
    }

    /** A tank's whole state before one change: what an abort puts back. */
    private record TankUndo(FluidTankView tank, Fluid fluid, long amount)
            implements DedTransferJournal.Undo {

        @Override
        public Object target() {
            return tank;
        }

        @Override
        public void undo() {
            tank.set(fluid, amount);
        }

        @Override
        public void notifyChanged() {
            tank.onChanged();
        }
    }
}
