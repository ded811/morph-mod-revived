package com.deds.api.fluid;

/**
 * The unit a 26.2 fluid quantity is measured in, and the one conversion a
 * 1.6.4 port needs (Ded's API v2.1).
 *
 * <p>Forge measured fluids in <b>integer millibuckets</b>, 1,000 to the
 * bucket. The modern answer is <b>long droplets</b>, {@value #BUCKET} to the
 * bucket — a number chosen because it divides evenly by 2, 3, 4, 5, 6, 8, 9,
 * 10, 12, 20, 27, 81 and 100, so a third of a bucket is exact where 333 mB was
 * not. The two are not interchangeable and the ratio is not round: <b>1 mB =
 * 81 droplets</b>.</p>
 *
 * <p>This class exists so that mod code can carry the original's mB literals
 * verbatim — {@code fromMillibuckets(16_000)} reads as the tank's own
 * {@code bucketsPerTank * BUCKET_VOLUME} — without importing a loader
 * constant. The recipe for converting a whole Forge fluid mod is
 * {@code docs/MOD-COOKBOOK.md} §17 ("Fluids").</p>
 *
 * <p>Everything else about fluids that mod code needs is in
 * {@link FluidRegistrar}.</p>
 */
public final class FluidAmounts {

    /**
     * Droplets in one bucket: <b>81,000</b>. Identical to the loader's
     * {@code FluidConstants.BUCKET}; restated here so mod code never has to
     * import it (docs/ARCHITECTURE.md's boundary rule).
     */
    public static final long BUCKET = 81_000L;

    /** Droplets per Forge millibucket: {@value}. {@code BUCKET / 1000}. */
    private static final long PER_MILLIBUCKET = BUCKET / 1000L;

    private FluidAmounts() {
    }

    /**
     * Converts a 1.6.4 millibucket literal to droplets. Exact — 81 is a whole
     * number, so no 1.6.4 quantity loses precision on the way in.
     *
     * @param millibuckets the original mod's number
     * @return the same quantity in droplets
     */
    public static long fromMillibuckets(long millibuckets) {
        return millibuckets * PER_MILLIBUCKET;
    }

    /**
     * Converts droplets back to millibuckets, rounding <b>down</b>. Only for
     * display (a tooltip that still wants to say "16000 mB") and for
     * comparing against a transcribed 1.6.4 constant in a test; never as an
     * intermediate step in a transfer, because the remainder is silently lost.
     *
     * @param droplets the modern quantity
     * @return the same quantity in whole millibuckets, rounded down
     */
    public static long toMillibuckets(long droplets) {
        return droplets / PER_MILLIBUCKET;
    }
}
