package com.deds.api.energy;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * An in-memory RF buffer: one {@code int} amount of stored energy, a maximum
 * (the capacity), and two separate per-call limits, one for putting energy in
 * and one for taking it out.
 *
 * <p>This class already implements {@link EnergyStorageView}. A block whose
 * sides all behave the same way can hand an instance straight to
 * {@link EnergyRegistrar#exposeStorage}. A block with different rules per
 * side should keep one instance as its real storage and expose a small view
 * per side that delegates to it, reporting 0 from {@link #getMaxReceive()} or
 * {@link #getMaxExtract()} on a side that must not accept or give up
 * energy.</p>
 *
 * <p><b>This class never notifies anyone.</b> No method here calls
 * {@link #onChanged()}, marks a block entity dirty, syncs clients or touches
 * the world. The owner does that work itself after it changes the buffer. If
 * the raw object is exposed to the energy transport, the inherited
 * {@code onChanged()} does nothing, so nothing marks the owner dirty after an
 * external transfer. Expose a wrapping view whose {@code onChanged()} does
 * that work when it matters.</p>
 *
 * <p>The four protected fields ({@link #energy}, {@link #capacity},
 * {@link #maxReceive} and {@link #maxExtract}) are the whole state of the
 * object. Subclasses may read and write them directly, and every getter and
 * every method sees such a write immediately. A direct write bypasses all of
 * the clamping described on the methods below.</p>
 *
 * <p>All amounts are {@code int} RF, and all arithmetic is plain 32-bit
 * arithmetic that wraps on overflow. The methods below say where that can be
 * observed. With non-negative constructor arguments, non-negative amounts and
 * non-negative capacities, the stored amount always stays within
 * {@code [0, capacity]}.</p>
 *
 * <p>Instances are not thread-safe. Use them from the game thread only.</p>
 *
 * <p>The member names follow the long-established RF naming conventions, so
 * existing energy code written against those names can move onto this class
 * without renaming anything.</p>
 */
public class EnergyStorage implements EnergyStorageView {

    /** The stored amount, in RF. */
    protected int energy;

    /** The maximum amount the buffer holds, in RF. */
    protected int capacity;

    /** The most RF one {@link #receiveEnergy} call may move in. */
    protected int maxReceive;

    /** The most RF one {@link #extractEnergy} call may move out. */
    protected int maxExtract;

    /**
     * Creates an empty buffer whose receive limit and extract limit both
     * equal its capacity.
     *
     * <p>The argument is not validated. Zero, negative and very large values
     * are kept exactly as given.</p>
     *
     * @param capacity the maximum amount of RF the buffer holds, also used as
     *                 both per-call limits
     */
    public EnergyStorage(int capacity) {
        this(capacity, capacity, capacity);
    }

    /**
     * Creates an empty buffer with one transfer limit used for both
     * receiving and extracting.
     *
     * <p>The arguments are not validated. Zero, negative and very large
     * values are kept exactly as given.</p>
     *
     * @param capacity      the maximum amount of RF the buffer holds
     * @param transferLimit the most RF one receive call, and one extract
     *                      call, may move
     */
    public EnergyStorage(int capacity, int transferLimit) {
        this(capacity, transferLimit, transferLimit);
    }

    /**
     * Creates an empty buffer with separate receive and extract limits.
     *
     * <p>The second argument is the receive limit and the third is the
     * extract limit. The arguments are not validated. Zero, negative and very
     * large values are kept exactly as given.</p>
     *
     * @param capacity     the maximum amount of RF the buffer holds
     * @param receiveLimit the most RF one {@link #receiveEnergy} call may
     *                     move in
     * @param extractLimit the most RF one {@link #extractEnergy} call may
     *                     move out
     */
    public EnergyStorage(int capacity, int receiveLimit, int extractLimit) {
        this.energy = 0;
        this.capacity = capacity;
        this.maxReceive = receiveLimit;
        this.maxExtract = extractLimit;
    }

    /**
     * Moves energy into the buffer and returns how much moved.
     *
     * <p>The amount moved is the smallest of three numbers: the room left
     * ({@code capacity - energy}), this buffer's receive limit, and
     * {@code amount}. When {@code simulate} is {@code false}, the stored
     * amount grows by exactly that much. When it is {@code true}, nothing
     * changes, and the result is what a real call made now would return.</p>
     *
     * <p>The amount is expected to be non-negative, and callers must not
     * pass a negative one. A negative amount is not rejected: it runs the
     * transfer backwards, so the result is negative and a real call lowers
     * the stored amount, possibly below zero and without regard to the
     * extract limit. A negative receive limit, or a stored amount above
     * capacity, has the same effect.</p>
     *
     * <p>This method does not notify.</p>
     *
     * @param amount   the most RF the caller offers
     * @param simulate {@code true} to compute the result without changing
     *                 anything
     * @return the RF moved in, or the RF that would move in when simulating
     */
    public int receiveEnergy(int amount, boolean simulate) {
        int received = Math.min(Math.min(capacity - energy, maxReceive), amount);
        if (!simulate) {
            energy += received;
        }
        return received;
    }

    /**
     * Moves energy out of the buffer and returns how much moved.
     *
     * <p>The amount moved is the smallest of three numbers: the stored
     * amount, this buffer's extract limit, and {@code amount}. When
     * {@code simulate} is {@code false}, the stored amount shrinks by exactly
     * that much. When it is {@code true}, nothing changes, and the result is
     * what a real call made now would return.</p>
     *
     * <p>The amount is expected to be non-negative, and callers must not
     * pass a negative one. A negative amount is not rejected: it runs the
     * transfer backwards, so the result is negative and a real call raises
     * the stored amount with no capacity check, possibly past capacity or
     * past the int range. A negative extract limit has the same effect.</p>
     *
     * <p>This method does not notify.</p>
     *
     * @param amount   the most RF the caller wants
     * @param simulate {@code true} to compute the result without changing
     *                 anything
     * @return the RF moved out, or the RF that would move out when simulating
     */
    public int extractEnergy(int amount, boolean simulate) {
        int extracted = Math.min(Math.min(energy, maxExtract), amount);
        if (!simulate) {
            energy -= extracted;
        }
        return extracted;
    }

    /**
     * Sets the stored amount, clamped into {@code [0, capacity]}.
     *
     * <p>A value inside that range is stored exactly. A value above capacity
     * is stored as capacity, and a value below zero is stored as zero. If
     * capacity is negative, the stored amount always becomes zero. Capacity
     * and both limits are left alone.</p>
     *
     * <p>This is the single mutator of {@link EnergyStorageView}. The energy
     * transport calls it during speculative transfers and to roll a cancelled
     * transfer back to an earlier value, which is why in-range values must
     * be kept exactly. It never notifies, and callers must not rely on it to
     * do so.</p>
     *
     * @param energy the new stored amount, in RF
     */
    @Override
    public void setEnergyStored(int energy) {
        this.energy = Math.max(0, Math.min(capacity, energy));
    }

    /**
     * Adds a signed amount to the stored energy, ignoring both rate limits.
     *
     * <p>A positive {@code delta} adds and a negative one removes. The sum is
     * then clamped into {@code [0, capacity]}, and whatever falls above
     * capacity or below zero is silently discarded. Nothing is returned and
     * nothing is notified.</p>
     *
     * <p><b>Overflow:</b> the sum is plain {@code int} arithmetic. If
     * {@code energy + delta} passes {@link Integer#MAX_VALUE}, it wraps to a
     * negative number and the clamp turns that into zero. So a positive delta
     * large enough to leave the int range <em>empties</em> a non-empty buffer
     * instead of filling it. Starting from exactly zero, the same delta fills
     * the buffer, because the sum does not wrap.</p>
     *
     * @param delta the RF to add, or to remove when negative
     */
    public void modifyEnergyStored(int delta) {
        this.energy = Math.max(0, Math.min(capacity, energy + delta));
    }

    /**
     * Sets the capacity to exactly the given value.
     *
     * <p>If the stored amount is larger than the new capacity, it is cut down
     * to the new capacity. Otherwise it is left exactly as it was: it never
     * grows, and it is not floored at zero here. Both limits are unchanged,
     * and nothing is notified.</p>
     *
     * <p>The value is not validated. A negative capacity is accepted as it
     * is, and shrinking to one makes the stored amount that same negative
     * number.</p>
     *
     * @param capacity the new maximum, in RF
     */
    public void setCapacity(int capacity) {
        this.capacity = capacity;
        if (this.energy > capacity) {
            this.energy = capacity;
        }
    }

    /**
     * Returns the stored amount, in RF, exactly as held in {@link #energy}.
     *
     * <p>The value is not clamped. It lies outside {@code [0, capacity]} only
     * if a negative argument, a negative capacity or a direct field write put
     * it there.</p>
     *
     * @return the current stored amount
     */
    @Override
    public int getEnergyStored() {
        return energy;
    }

    /**
     * Returns the capacity, in RF, exactly as held in {@link #capacity}.
     *
     * @return the current maximum
     */
    @Override
    public int getMaxEnergyStored() {
        return capacity;
    }

    /**
     * Returns the receive limit, in RF, exactly as held in
     * {@link #maxReceive}.
     *
     * @return the most RF one receive call may move in
     */
    @Override
    public int getMaxReceive() {
        return maxReceive;
    }

    /**
     * Returns the extract limit, in RF, exactly as held in
     * {@link #maxExtract}.
     *
     * @return the most RF one extract call may move out
     */
    @Override
    public int getMaxExtract() {
        return maxExtract;
    }

    /**
     * Loads the stored amount from the int saved under the key
     * {@code "Energy"} at the top level of {@code input}.
     *
     * <p>A missing key loads as zero, replacing whatever was stored before.
     * The value is read the way {@link ValueInput#getIntOr} reads an int with
     * a default: other numeric tags are converted to an int, and a
     * non-numeric value loads as zero after the input reports a problem.</p>
     *
     * <p>The loaded value is clamped into {@code [0, capacity]} using the
     * capacity this object has at the moment of the call. Settle the capacity
     * (from the block's tier, configuration and so on) before calling this
     * method.</p>
     *
     * <p>Only the stored amount is restored. Capacity and both limits are left
     * untouched, and nothing is notified.</p>
     *
     * @param input the value input holding the owner's saved data
     * @return this same instance, so calls can be chained
     */
    public EnergyStorage read(ValueInput input) {
        int saved = input.getIntOr("Energy", 0);
        this.energy = Math.max(0, Math.min(capacity, saved));
        return this;
    }

    /**
     * Saves the stored amount as an int under the key {@code "Energy"} at the
     * top level of {@code output}.
     *
     * <p>The key is always written, even when the amount is zero. A negative
     * stored amount is written as zero. A stored amount above capacity, which
     * only a negative argument or a direct field write can produce, is
     * written as it is.</p>
     *
     * <p>Capacity and the two limits are not saved. The owner rebuilds them
     * when it constructs the object, before it calls {@link #read}.</p>
     *
     * <p>The key shares its level with the owner's own keys, so the owner must
     * not use {@code "Energy"} for anything else. Writing does not change the
     * object.</p>
     *
     * @param output the value output receiving the owner's saved data
     */
    public void write(ValueOutput output) {
        output.putInt("Energy", Math.max(0, energy));
    }
}
