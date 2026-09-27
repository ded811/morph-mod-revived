package com.deds.api.energy;

/**
 * A neighbouring block's energy connection, seen from the outside (Ded's API
 * v2.4). Obtained from {@link EnergyRegistrar#port}.
 *
 * <p>This is the modern replacement for holding an RF {@code IEnergyHandler}
 * reference — the shape behind every 1.6.4 push loop:
 * {@code adjacentHandler.receiveEnergy(side, amount, false)} in
 * {@code TileEnergyCell.transferEnergy}, {@code TileDynamoBase} and
 * {@code ConduitEnergy.transfer}, and both directions in
 * {@code cofh.util.EnergyHelper}'s adjacent-handler helpers (all TE3/CoFHCore
 * decompile). A non-null port is the replacement for
 * {@code EnergyHelper.isEnergyHandlerFromSide}, i.e. for
 * {@code canInterface(from)} — note that 1.6.4 machines could be connectable
 * yet passive (a dynamo's facing answers {@code canInterface = true} and
 * still returns 0 from both transfer methods), so a non-null port promises a
 * <em>connection</em>, never that energy will move.</p>
 *
 * <p>Like {@link com.deds.api.fluid.FluidPort}, it keeps the shape a 1.6.4
 * port is written against — <b>simulate, then commit</b> — instead of
 * exposing the platform's transactions. Each call is its own top-level
 * transaction, committed or discarded before it returns; the fluid port's
 * rules apply verbatim: not composable atomically with another call, and
 * never call it from inside somebody else's transaction — i.e. not from
 * inside an {@link EnergyStorageView} or
 * {@link com.deds.api.fluid.FluidTankView} method. Tick code and interaction
 * handlers are where every consumer lives.</p>
 */
public interface EnergyPort {

    /**
     * Pushes energy in — the caller-side twin of RF's
     * {@code receiveEnergy(from, maxReceive, !doReceive)}.
     *
     * @param amount   the most RF to insert; zero or negative inserts nothing
     * @param simulate {@code true} to measure without changing anything
     * @return how much was (or would be) accepted
     */
    int insert(int amount, boolean simulate);

    /**
     * Pulls energy out — the caller-side twin of RF's
     * {@code extractEnergy(from, maxExtract, !doExtract)}.
     *
     * @param amount   the most RF to extract
     * @param simulate {@code true} to measure without changing anything
     * @return how much was (or would be) removed
     */
    int extract(int amount, boolean simulate);
}
