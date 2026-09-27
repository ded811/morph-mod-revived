package com.deds.api.neoforge.transfer;

import com.deds.api.energy.EnergyStorageView;

import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.function.BiFunction;

/**
 * A block entity's exposed energy for one face, as NeoForge's energy
 * capability. The arithmetic is the Fabric backend's
 * ({@code FabricEnergyStorage}): an insert is limited by the room left and
 * the view's receive limit, an extract by what is stored and the extract
 * limit, and 1 RF is 1 FE, so nothing is converted. Rollback and the view's
 * {@code onChanged} go through {@link DedTransferJournal}.
 *
 * <p>Lazy like {@link NeoTankHandler}: the mod's function is asked for the
 * current view on every call, so a cached handler follows the block entity.
 * If the function answers null for this face in the meantime, the handler
 * holds and moves nothing.</p>
 */
final class NeoEnergyHandler<T extends BlockEntity> implements EnergyHandler {

    private final T blockEntity;
    private final Direction side;
    private final BiFunction<T, Direction, EnergyStorageView> storage;

    NeoEnergyHandler(T blockEntity, Direction side,
            BiFunction<T, Direction, EnergyStorageView> storage) {
        this.blockEntity = blockEntity;
        this.side = side;
        this.storage = storage;
    }

    private EnergyStorageView view() {
        return storage.apply(blockEntity, side);
    }

    @Override
    public long getAmountAsLong() {
        EnergyStorageView view = view();
        return view == null ? 0 : view.getEnergyStored();
    }

    @Override
    public long getCapacityAsLong() {
        EnergyStorageView view = view();
        return view == null ? 0 : view.getMaxEnergyStored();
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        EnergyStorageView view = view();
        if (view == null) {
            return 0;
        }
        int room = view.getMaxEnergyStored() - view.getEnergyStored();
        int inserted = Math.min(amount, Math.min(room, view.getMaxReceive()));
        if (inserted <= 0) {
            return 0;
        }
        int before = view.getEnergyStored();
        DedTransferJournal.record(transaction, new EnergyUndo(view, before));
        view.setEnergyStored(before + inserted);
        return inserted;
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        EnergyStorageView view = view();
        if (view == null) {
            return 0;
        }
        int extracted = Math.min(amount,
                Math.min(view.getEnergyStored(), view.getMaxExtract()));
        if (extracted <= 0) {
            return 0;
        }
        int before = view.getEnergyStored();
        DedTransferJournal.record(transaction, new EnergyUndo(view, before));
        view.setEnergyStored(before - extracted);
        return extracted;
    }

    /** An energy view's stored amount before one change. */
    private record EnergyUndo(EnergyStorageView view, int energy)
            implements DedTransferJournal.Undo {

        @Override
        public Object target() {
            return view;
        }

        @Override
        public void undo() {
            view.setEnergyStored(energy);
        }

        @Override
        public void notifyChanged() {
            view.onChanged();
        }
    }
}
