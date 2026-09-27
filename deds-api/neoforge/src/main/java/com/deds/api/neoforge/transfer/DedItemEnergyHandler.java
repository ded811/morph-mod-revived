package com.deds.api.neoforge.transfer;

import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.Item;

/**
 * The item energy capability of an {@code EnergyContainerItem} on NeoForge:
 * Team Reborn Energy's {@code SimpleEnergyItem} storage, which is what the
 * Fabric backend hands out for the same items, rule for rule:
 * <ul>
 * <li>energy is per ITEM: a stack of {@code count} items stores
 *     {@code count} times the per-item amount, and every transfer moves the
 *     same amount into or out of each item, so an offer is divided by the
 *     count first;</li>
 * <li>a transfer converts exactly {@code count} items to their new energy or
 *     nothing at all;</li>
 * <li>zero stored removes the component, so a drained item stacks with a
 *     fresh one;</li>
 * <li>the handler answers only while the accessed slot still holds the item
 *     it was created for, and at least one of it.</li>
 * </ul>
 * NeoForge's own {@code ItemAccessEnergyHandler} is not used: it needs an
 * int component and keeps a zero in the item.
 */
final class DedItemEnergyHandler implements EnergyHandler {

    private final ItemAccess access;
    private final Item item;
    private final long capacity;
    private final long maxInsert;
    private final long maxExtract;

    private DedItemEnergyHandler(ItemAccess access, Item item, long capacity,
            long maxInsert, long maxExtract) {
        this.access = access;
        this.item = item;
        this.capacity = capacity;
        this.maxInsert = maxInsert;
        this.maxExtract = maxExtract;
    }

    static EnergyHandler create(ItemAccess access, int capacity, int maxInsert,
            int maxExtract) {
        TransferPreconditions.checkNonNegative(capacity);
        TransferPreconditions.checkNonNegative(maxInsert);
        TransferPreconditions.checkNonNegative(maxExtract);
        return new DedItemEnergyHandler(access, access.getResource().getItem(),
                capacity, maxInsert, maxExtract);
    }

    private boolean valid() {
        return access.getResource().is(item) && access.getAmount() > 0;
    }

    private static long stored(ItemResource resource) {
        return resource.getComponents().getOrDefault(NeoEnergy.component(), 0L);
    }

    @Override
    public long getAmountAsLong() {
        return valid() ? access.getAmount() * stored(access.getResource()) : 0;
    }

    @Override
    public long getCapacityAsLong() {
        return valid() ? access.getAmount() * capacity : 0;
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        if (!valid()) {
            return 0;
        }
        long count = access.getAmount();
        long current = stored(access.getResource());
        long perItem = Math.min(maxInsert, Math.min(amount / count, capacity - current));
        if (perItem > 0 && trySet(current + perItem, count, transaction)) {
            return (int) (perItem * count);
        }
        return 0;
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        if (!valid()) {
            return 0;
        }
        long count = access.getAmount();
        long current = stored(access.getResource());
        long perItem = Math.min(maxExtract, Math.min(amount / count, current));
        if (perItem > 0 && trySet(current - perItem, count, transaction)) {
            return (int) (perItem * count);
        }
        return 0;
    }

    /** Converts exactly {@code count} items to {@code perItem} energy each. */
    private boolean trySet(long perItem, long count, TransactionContext transaction) {
        DataComponentType<Long> component = NeoEnergy.component();
        ItemResource current = access.getResource();
        ItemResource updated = perItem <= 0 ? current.without(component)
                : current.with(component, perItem);
        int n = (int) count;
        try (Transaction nested = Transaction.open(transaction)) {
            if (access.extract(current, n, nested) == n
                    && access.insert(updated, n, nested) == n) {
                nested.commit();
                return true;
            }
        }
        return false;
    }
}
