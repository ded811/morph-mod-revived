package com.deds.api.fluid;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * One single-fluid tank, as the loader's fluid transport needs to see it
 * (Ded's API v2.1).
 *
 * <p>A mod implements this over whatever it already stores — two fields on a
 * block entity is the normal case — and hands it to
 * {@link FluidRegistrar#exposeTanks}. The API then presents it to the loader
 * as a transferable storage: capacity clamping, same-fluid merging and
 * <b>transaction rollback</b> are all done API-side, which is why this
 * interface has exactly one mutator and no notion of "simulate".</p>
 *
 * <h2>Why {@link #set} and not insert/extract</h2>
 *
 * <p>26.2 fluid transport is transactional: a pipe may insert into three
 * tanks, discover the fourth refuses, and roll the whole thing back. The
 * loader does that by asking each participant for a snapshot before the first
 * write and handing it back on abort. A tank that only exposed
 * insert/extract could not be rewound, so the seam is state-shaped:
 * {@code (fluid, amount)} out, {@code (fluid, amount)} in. Implementations
 * must make {@code set} unconditional — no clamping, no filtering, no
 * refusing — or a rollback will not restore what was there.</p>
 *
 * <p><b>{@code set} must not notify.</b> It is called during speculative work
 * and during rollback, potentially several times per game tick. Do the
 * "mark dirty, sync to clients, wake the neighbours" work in
 * {@link #onChanged()}, which the API calls after a transfer has actually
 * committed — and which <b>must be idempotent</b>, for the reason given on
 * that method.</p>
 */
public interface FluidTankView {

    /**
     * The fluid currently held, or {@link Fluids#EMPTY} when the tank is
     * empty. Never {@code null}.
     */
    Fluid fluid();

    /** How much is in the tank, in {@linkplain FluidAmounts droplets}. */
    long amount();

    /** The tank's maximum, in droplets. */
    long capacity();

    /**
     * Whether this tank would ever hold that fluid — the port of a Forge
     * tank's acceptable-fluid filter. Called before any capacity or
     * same-fluid test, so a filtered tank need not repeat those.
     *
     * @param fluid the candidate; never {@link Fluids#EMPTY}
     */
    boolean accepts(Fluid fluid);

    /**
     * Overwrites the tank's contents. Unconditional — see the class Javadoc.
     *
     * @param fluid  {@link Fluids#EMPTY} when {@code amount} is 0
     * @param amount droplets, never negative and never above
     *               {@link #capacity()} when called by the API
     */
    void set(Fluid fluid, long amount);

    /**
     * Called after a committed change, on the game thread. The place for
     * {@code setChanged()}, a client sync, or waking neighbouring machines.
     * Does nothing by default.
     *
     * <p><b>Make it idempotent.</b> The API fires this once per committed
     * transaction <em>per storage the transfer went through</em>, not once per
     * tank: the adapter that owns the notification is created per
     * {@code FluidStorage.SIDED} lookup, so a block entity that publishes the
     * same tank from more than one position — as a tank column does, where
     * every tank appears in the list of every tank at or below it — can see
     * this fire once for each of those positions that a single transfer wrote
     * through. "Mark dirty, sync, wake the neighbours" is safe to repeat;
     * playing a sound, spawning a particle, ejecting an item or bumping a
     * throughput counter is not, and must not go here without its own
     * once-per-tick guard.</p>
     */
    default void onChanged() {
    }
}
