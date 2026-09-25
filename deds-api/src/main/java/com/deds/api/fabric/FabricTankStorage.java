package com.deds.api.fabric;

import com.deds.api.fluid.FluidTankView;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Adapts one {@link FluidTankView} to the loader's transactional
 * {@code Storage<FluidVariant>} (Ded's API v2.1, the fluid seam's whole
 * point).
 *
 * <p>All the arithmetic a Forge {@code FluidTank} used to do lives here —
 * capacity clamp, same-fluid merge, the filter — so that mod-side tanks stay
 * two fields and a filter predicate. Rollback is a
 * {@link SnapshotParticipant} over the pair {@code (fluid, amount)}, restored
 * through {@link FluidTankView#set}, and
 * {@link FluidTankView#onChanged()} fires from {@code onFinalCommit} — once
 * per committed transaction <b>per adapter instance</b>, however many
 * speculative writes that instance took to get there.</p>
 *
 * <p><b>The unit of both rollback and notification is this INSTANCE, not the
 * tank.</b> A fresh one is minted per {@code FluidStorage.SIDED} lookup
 * ({@code FabricModContext.combine}), and the snapshot stack is a private
 * instance field on {@code SnapshotParticipant} — not state the transaction
 * owns. Rollback does not care: {@link #readSnapshot} writes an absolute
 * {@code (fluid, amount)}, so any number of participants over one tank unwind
 * to the same value in any order. Notification does: if a block entity
 * publishes the same {@link FluidTankView} from more than one position — an
 * OpenBlocks tank column does, because every tank appears in the column list
 * of every tank at or below it — and one transaction writes through two of
 * those lookups, {@code onChanged()} fires once per lookup. Implementations
 * must therefore keep it idempotent; that requirement is stated on
 * {@link FluidTankView#onChanged()}. Making it literally once-per-tank would
 * need a canonical participant per tank, i.e. a weak identity cache, which is
 * not worth it while every in-repo consumer performs exactly one lookup per
 * transaction.</p>
 */
final class FabricTankStorage extends
        SnapshotParticipant<FabricTankStorage.Contents>
        implements SingleSlotStorage<FluidVariant> {

    /** The whole of a tank's state — what a rollback has to put back. */
    record Contents(Fluid fluid, long amount) {
    }

    private final FluidTankView tank;

    FabricTankStorage(FluidTankView tank) {
        this.tank = tank;
    }

    @Override
    public long insert(FluidVariant resource, long maxAmount,
            TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maxAmount);
        // A variant carrying data components is a different resource from the
        // plain fluid and this seam has no way to store the patch, so it is
        // refused rather than silently flattened. No consumer produces one
        // yet; when one does, FluidTankView grows a components accessor.
        if (resource.hasComponents()) {
            return 0;
        }
        Fluid incoming = resource.getFluid();
        if (!tank.accepts(incoming)) {
            return 0;
        }
        Fluid held = tank.fluid();
        if (held != Fluids.EMPTY && held != incoming) {
            return 0;
        }
        long room = tank.capacity() - tank.amount();
        long inserted = Math.min(maxAmount, room);
        if (inserted <= 0) {
            return 0;
        }
        updateSnapshots(transaction);
        tank.set(incoming, tank.amount() + inserted);
        return inserted;
    }

    @Override
    public long extract(FluidVariant resource, long maxAmount,
            TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maxAmount);
        if (resource.hasComponents() || resource.getFluid() != tank.fluid()) {
            return 0;
        }
        long extracted = Math.min(maxAmount, tank.amount());
        if (extracted <= 0) {
            return 0;
        }
        updateSnapshots(transaction);
        long left = tank.amount() - extracted;
        tank.set(left == 0 ? Fluids.EMPTY : tank.fluid(), left);
        return extracted;
    }

    /**
     * Whether this tank would take {@code resource} at all, <em>ignoring how
     * much room it has</em>. {@code false} only on the identity grounds
     * {@link #insert} checks before it looks at capacity — a different fluid
     * already in the tank, a filter that says no, or a variant carrying data
     * components. A tank that is simply full answers {@code true}.
     *
     * <p>This is the port of {@code TileEntityTank.accepts:122-126}
     * (OB-1.2.9), and it is what lets a chain of tanks <b>stop</b> at a tank
     * holding something else instead of stepping over it. Only
     * {@code FabricModContext.combine}'s chain storage uses it.</p>
     *
     * @param resource a non-blank variant
     */
    boolean canHold(FluidVariant resource) {
        if (resource.hasComponents()) {
            return false;
        }
        Fluid incoming = resource.getFluid();
        Fluid held = tank.fluid();
        return (held == Fluids.EMPTY || held == incoming)
                && tank.accepts(incoming);
    }

    /**
     * Whether the tank is currently holding exactly {@code resource} — the
     * port of {@code TileEntityTank.containsFluid:128-132} (OB-1.2.9), and the
     * chain's stop condition on the extraction side. An <em>empty</em> tank
     * answers {@code false} and so ends the walk, which is what the original
     * does.
     *
     * @param resource a non-blank variant
     */
    boolean holds(FluidVariant resource) {
        return !resource.hasComponents()
                && resource.getFluid() == tank.fluid();
    }

    @Override
    public boolean isResourceBlank() {
        return tank.fluid() == Fluids.EMPTY;
    }

    @Override
    public FluidVariant getResource() {
        return FluidVariant.of(tank.fluid());
    }

    @Override
    public long getAmount() {
        return tank.amount();
    }

    @Override
    public long getCapacity() {
        return tank.capacity();
    }

    @Override
    protected Contents createSnapshot() {
        return new Contents(tank.fluid(), tank.amount());
    }

    @Override
    protected void readSnapshot(Contents snapshot) {
        tank.set(snapshot.fluid(), snapshot.amount());
    }

    @Override
    protected void onFinalCommit() {
        tank.onChanged();
    }
}
