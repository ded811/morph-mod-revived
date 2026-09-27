package com.deds.api.energy;

import net.minecraft.world.item.ItemStack;

/**
 * An item that stores RF (Ded's API v2.4) — the port of
 * {@code cofh.api.energy.IEnergyContainerItem} with
 * {@code ItemEnergyContainer}'s arithmetic supplied as defaults (both
 * CoFHCore 2.0.0.5 decompile; design credit Team CoFH). Implement it on the
 * {@code Item} subclass, exactly as 1.6.4 did.
 *
 * <p>The 1.6.4 pattern this preserves, verbatim from the TE3 decompile: the
 * Energetic Infuser ({@code TileCharger}) does
 * {@code ((IEnergyContainerItem) stack.getItem()).receiveEnergy(stack,
 * energy, false)} on whatever sits in its slot, capacitors drain themselves
 * into the held item ({@code ItemCapacitor:91-92}), and
 * {@code EnergyHelper.isEnergyContainerItem} is a plain {@code instanceof}
 * on the item — which is why this seam needs <b>no registration</b>: the
 * interface itself is the discovery mechanism, for our mods and for the
 * platform bridge alike.</p>
 *
 * <p>Where 1.6.4 kept the charge in the stack's NBT {@code "Energy"} tag,
 * 26.2 keeps it in a data component; the defaults below read and write it
 * through {@link ItemEnergy}, and third-party chargers see the very same
 * number (docs/API-ROADMAP.md v2.4, "the item bridge"). Two stacks with
 * different charge do not stack — the component is part of stack identity —
 * matching the old NBT behaviour.</p>
 *
 * <p><b>Energy is per ITEM.</b> The charge a stack carries is what each of its
 * items holds, and the three limits below are per item too. So on a stack of
 * several items the defaults divide an offer by the count, move the same
 * amount into or out of every item and report the total, exactly as the
 * loader's item energy lookup does for the same stack (since v2.9; before,
 * the defaults charged or drained a whole stack for the price of one item).
 * On a single item nothing changes.</p>
 *
 * <p>The three abstract members are {@code ItemEnergyContainer}'s three
 * fields turned per-stack, because TE3 already needed them per-stack: a
 * capacitor's capacity and transfer rates depend on its tier, which 1.6.4
 * encoded in the damage value ({@code ItemCapacitor}). A fixed-size item
 * returns constants.</p>
 *
 * <p>Consumer-implemented, so this interface only ever grows by
 * {@code default} methods (docs/API-COMPATIBILITY.md §4).</p>
 */
public interface EnergyContainerItem {

    /** The most RF {@code stack} can hold — {@code getMaxEnergyStored}'s answer. */
    int getMaxEnergyStored(ItemStack stack);

    /** The most RF one {@link #receiveEnergy} call may accept. */
    int getMaxReceive(ItemStack stack);

    /** The most RF one {@link #extractEnergy} call may give up. */
    int getMaxExtract(ItemStack stack);

    /**
     * Charges the stack — {@code ItemEnergyContainer.receiveEnergy}'s
     * arithmetic, per item: each of the stack's items takes
     * {@code min(room, min(getMaxReceive(stack), maxReceive / count))}.
     *
     * @param stack      the stack being charged; written through when not
     *                   simulating
     * @param maxReceive the most the charger offers, for the whole stack
     * @param simulate   {@code true} to measure without changing anything
     * @return how much was (or would be) accepted by the whole stack: the
     *         per-item amount times the count
     */
    default int receiveEnergy(ItemStack stack, int maxReceive,
            boolean simulate) {
        int count = stack.getCount();
        if (count <= 0) {
            return 0;
        }
        int energy = ItemEnergy.get(stack);
        int perItem = Math.max(0, Math.min(getMaxEnergyStored(stack) - energy,
                Math.min(getMaxReceive(stack), maxReceive / count)));
        if (!simulate && perItem > 0) {
            ItemEnergy.set(stack, energy + perItem);
        }
        return perItem * count;
    }

    /**
     * Drains the stack — {@code ItemEnergyContainer.extractEnergy}'s
     * arithmetic, per item: each of the stack's items gives up
     * {@code min(stored, min(getMaxExtract(stack), maxExtract / count))}.
     *
     * @param stack      the stack being drained; written through when not
     *                   simulating
     * @param maxExtract the most the caller wants, from the whole stack
     * @param simulate   {@code true} to measure without changing anything
     * @return how much was (or would be) removed from the whole stack: the
     *         per-item amount times the count
     */
    default int extractEnergy(ItemStack stack, int maxExtract,
            boolean simulate) {
        int count = stack.getCount();
        if (count <= 0) {
            return 0;
        }
        int energy = ItemEnergy.get(stack);
        int perItem = Math.max(0, Math.min(energy,
                Math.min(getMaxExtract(stack), maxExtract / count)));
        if (!simulate && perItem > 0) {
            ItemEnergy.set(stack, energy - perItem);
        }
        return perItem * count;
    }

    /**
     * RF currently stored in EACH item of {@code stack} (compare it with
     * {@link #getMaxEnergyStored}, also per item). A stack that has never
     * been charged answers 0, exactly as the tag-less 1.6.4 stack did.
     */
    default int getEnergyStored(ItemStack stack) {
        return ItemEnergy.get(stack);
    }
}
