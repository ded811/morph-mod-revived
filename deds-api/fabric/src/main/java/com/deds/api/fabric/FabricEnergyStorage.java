package com.deds.api.fabric;

import com.deds.api.energy.EnergyStorageView;

import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

/**
 * Adapts one {@link EnergyStorageView} to Team Reborn's transactional
 * {@code EnergyStorage} (Ded's API v2.4) — the energy twin of
 * {@link FabricTankStorage}, and the block half of THE bridge: our seam and
 * the de-facto Fabric energy standard meet here and nowhere else, so a
 * third-party cable inserting into this adapter and a mod's own
 * {@code EnergyPort} push are the same code path.
 *
 * <p>All the arithmetic RF's {@code EnergyStorage.receiveEnergy} /
 * {@code extractEnergy} used to do lives here — the room clamp and the
 * per-operation caps — so mod-side buffers stay state-shaped. Rollback is a
 * {@link SnapshotParticipant} over the stored amount, restored through
 * {@link EnergyStorageView#setEnergyStored}, and
 * {@link EnergyStorageView#onChanged()} fires from {@code onFinalCommit} —
 * once per committed transaction <b>per adapter instance</b>, with the same
 * caveat as the fluid adapter: a fresh instance is minted per lookup
 * ({@code FabricModContext.exposeStorage}), so {@code onChanged} must be
 * idempotent. Rollback does not care — snapshots are absolute values.</p>
 */
final class FabricEnergyStorage extends SnapshotParticipant<Integer>
        implements team.reborn.energy.api.EnergyStorage {

    private final EnergyStorageView view;

    FabricEnergyStorage(EnergyStorageView view) {
        this.view = view;
    }

    @Override
    public boolean supportsInsertion() {
        return view.getMaxReceive() > 0;
    }

    @Override
    public long insert(long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notNegative(maxAmount);
        int room = view.getMaxEnergyStored() - view.getEnergyStored();
        int inserted = (int) Math.min(FabricEnergyBridge.toRf(maxAmount),
                Math.min(room, view.getMaxReceive()));
        if (inserted <= 0) {
            return 0;
        }
        updateSnapshots(transaction);
        view.setEnergyStored(view.getEnergyStored() + inserted);
        return FabricEnergyBridge.toE(inserted);
    }

    @Override
    public boolean supportsExtraction() {
        return view.getMaxExtract() > 0;
    }

    @Override
    public long extract(long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notNegative(maxAmount);
        int extracted = (int) Math.min(FabricEnergyBridge.toRf(maxAmount),
                Math.min(view.getEnergyStored(), view.getMaxExtract()));
        if (extracted <= 0) {
            return 0;
        }
        updateSnapshots(transaction);
        view.setEnergyStored(view.getEnergyStored() - extracted);
        return FabricEnergyBridge.toE(extracted);
    }

    @Override
    public long getAmount() {
        return FabricEnergyBridge.toE(view.getEnergyStored());
    }

    @Override
    public long getCapacity() {
        return FabricEnergyBridge.toE(view.getMaxEnergyStored());
    }

    @Override
    protected Integer createSnapshot() {
        return view.getEnergyStored();
    }

    @Override
    protected void readSnapshot(Integer snapshot) {
        view.setEnergyStored(snapshot);
    }

    @Override
    protected void onFinalCommit() {
        view.onChanged();
    }
}
