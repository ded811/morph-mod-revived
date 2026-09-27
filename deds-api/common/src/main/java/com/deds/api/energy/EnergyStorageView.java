package com.deds.api.energy;

/**
 * One energy buffer, as the loader's energy transport needs to see it
 * (Ded's API v2.4).
 *
 * <p>This is the energy twin of {@link com.deds.api.fluid.FluidTankView}: a
 * mod implements it over whatever it already stores — usually by embedding
 * {@link EnergyStorage}, which implements this interface for free — and hands
 * it to {@link EnergyRegistrar#exposeStorage}. The API then presents it to
 * the platform's energy transport: capacity clamping, the per-operation
 * receive/extract caps and <b>transaction rollback</b> are all done API-side,
 * which is why this interface has exactly one mutator and no notion of
 * "simulate".</p>
 *
 * <p>The member names are the original RF API's, not invented ones: every
 * accessor below is named as in {@code cofh.api.energy.EnergyStorage} /
 * {@code IEnergyStorage}, so energy code written against those names moves
 * onto this API without renaming. Amounts are <b>int RF</b>, exactly as they
 * were in 1.6.4; the platform side is wider, and the API widens/narrows at
 * one pinned point (see {@code docs/API-ROADMAP.md} v2.4, "the unit
 * decision").</p>
 *
 * <h2>Why {@link #setEnergyStored} and not receive/extract</h2>
 *
 * <p>Same argument as the fluid seam: modern transfer is transactional — a
 * cable may push into three machines, discover the fourth refuses, and roll
 * the whole thing back. The loader does that by snapshotting each
 * participant's state before the first write and handing it back on abort. A
 * buffer that only exposed receive/extract could not be rewound, so the seam
 * is state-shaped. The API only ever calls {@code setEnergyStored} with
 * values in {@code [0, getMaxEnergyStored()]}; implementations must store
 * such a value exactly — no filtering, no refusing — or a rollback will not
 * restore what was there.</p>
 *
 * <p><b>{@code setEnergyStored} must not notify.</b> It runs during
 * speculative work and during rollback, potentially several times per game
 * tick. Do the "mark dirty, sync, wake the neighbours" work in
 * {@link #onChanged()}, which the API calls after a transfer has actually
 * committed.</p>
 *
 * <h2>Per-side gating is the view's job</h2>
 *
 * <p>{@link EnergyRegistrar#exposeStorage} asks for a view <em>per side</em>,
 * and the two cap accessors are how a side says what it allows — the port of
 * the 1.6.4 side rules, which were never just "connected or not":
 * {@code TileEnergyCell} receives only on {@code INPUT} sides and extracts
 * only on {@code OUTPUT} sides, each capped by a user-set per-tick limit
 * ({@code receiveEnergy}/{@code extractEnergy}, TE3 3.0.0.7 decompile), and a
 * dynamo's facing answers {@code canInterface = true} while returning 0 from
 * <em>both</em> transfer methods ({@code TileDynamoBase}) — connectable but
 * passive, because dynamos push actively. So: a receive-only side returns 0
 * from {@link #getMaxExtract()}, a passive side returns 0 from both, and "no
 * connection at all" is a {@code null} view, never a view of zeros.</p>
 *
 * <p>Consumer-implemented, so this interface only ever grows by
 * {@code default} methods (docs/API-COMPATIBILITY.md §4).</p>
 */
public interface EnergyStorageView {

    /** Energy currently stored, in RF. Never negative. */
    int getEnergyStored();

    /** The buffer's maximum, in RF. */
    int getMaxEnergyStored();

    /**
     * The most RF one insert operation may move into this buffer — the RF
     * {@code maxReceive} cap, applied per operation exactly as
     * {@code EnergyStorage.receiveEnergy} applied it per call. Return 0 for
     * a side that never accepts energy.
     */
    int getMaxReceive();

    /**
     * The most RF one extract operation may pull out of this buffer.
     * Return 0 for a side that never gives energy up.
     */
    int getMaxExtract();

    /**
     * Overwrites the stored amount. Called only with values in
     * {@code [0, getMaxEnergyStored()]}; must store the value exactly and
     * must not notify — see the class Javadoc.
     */
    void setEnergyStored(int energy);

    /**
     * Called after a committed change, on the game thread. The place for
     * {@code setChanged()}, a client sync, or waking neighbouring machines.
     * Does nothing by default.
     *
     * <p><b>Make it idempotent.</b> The adapter that owns this notification
     * is created per platform lookup, so a block entity that publishes the
     * same buffer more than once can see this fire once per lookup a single
     * transfer wrote through — the same caveat as
     * {@link com.deds.api.fluid.FluidTankView#onChanged()}, stated there in
     * full. "Mark dirty, sync, wake the neighbours" is safe to repeat;
     * a sound, a particle or a throughput counter is not.</p>
     */
    default void onChanged() {
    }
}
