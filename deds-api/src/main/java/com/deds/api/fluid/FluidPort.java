package com.deds.api.fluid;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * A neighbouring block's fluid connection, seen from the outside (Ded's API
 * v2.1). Obtained from {@link FluidRegistrar#port}.
 *
 * <p>This is the modern replacement for holding a Forge {@code IFluidHandler}
 * reference. It keeps the shape a 1.6.4 port is written against —
 * <b>simulate, then commit</b> — instead of exposing the loader's
 * transactions, because that is what every OpenBlocks/Thermal-era tile
 * actually does:</p>
 *
 * <pre>{@code
 * long room = port.insert(fluid, wanted, true);   // doFill = false
 * long done = port.insert(fluid, room,   false);  // doFill = true
 * }</pre>
 *
 * <p>Each call is its own top-level transaction, committed or discarded
 * before it returns. That means a port operation is <b>not</b> composable
 * with another one atomically, and it must not be called from inside somebody
 * else's transaction — i.e. not from inside a {@link FluidTankView} method.
 * Call it from tick code and interaction handlers, which is where every
 * consumer so far lives. If a future consumer genuinely needs multi-step
 * atomicity, that is the point at which this seam grows a transaction type,
 * not before (docs/MOD-COOKBOOK.md §14).</p>
 */
public interface FluidPort {

    /**
     * Pushes fluid in.
     *
     * @param fluid    what to insert; {@link Fluids#EMPTY} inserts nothing
     * @param droplets the most to insert, in {@linkplain FluidAmounts
     *                 droplets}; zero or negative inserts nothing
     * @param simulate {@code true} to measure without changing anything
     * @return how much was (or would be) accepted
     */
    long insert(Fluid fluid, long droplets, boolean simulate);

    /**
     * Pulls fluid out.
     *
     * @param fluid    what to extract; {@link Fluids#EMPTY} extracts nothing
     * @param droplets the most to extract
     * @param simulate {@code true} to measure without changing anything
     * @return how much was (or would be) removed
     */
    long extract(Fluid fluid, long droplets, boolean simulate);

    /**
     * A fluid this port currently offers, or {@link Fluids#EMPTY} if it holds
     * nothing extractable. The replacement for scanning a Forge
     * {@code getTankInfo()} array to find out what a neighbour is holding
     * before asking for it; a multi-tank neighbour answers with one of them,
     * unspecified which.
     *
     * <p><b>Extractable, not merely stored</b> — the answer comes from a
     * trial extraction that is rolled back, so a neighbour that holds water
     * but refuses to give any up (a machine's input tank) answers
     * {@link Fluids#EMPTY} rather than sending the caller off to spend a
     * pointless {@link #extract}. Like the other two methods this is its own
     * top-level transaction, so it is subject to the same rule: do not call it
     * from inside somebody else's.</p>
     */
    Fluid storedFluid();
}
