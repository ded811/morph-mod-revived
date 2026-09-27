package com.deds.api.fabric;

import com.deds.api.energy.EnergyContainerItem;
import com.deds.api.energy.ItemEnergy;

import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.base.SimpleEnergyItem;

/**
 * THE energy bridge (Ded's API v2.4): the one place Ded's RF-shaped seam and
 * the wider Fabric ecosystem's de-facto energy standard — Team Reborn's
 * Energy API — are wired together. Everything TR-flavoured that is not the
 * block adapter itself ({@link FabricEnergyStorage}) lives here: the unit
 * conversion, and the item-side fallback provider.
 *
 * <p><b>The dependency was verified, not assumed</b> (2026-08-06):
 * {@code teamreborn:energy:5.0.0} resolves from maven.fabricmc.net (HTTP 200,
 * jar + pom) and its own {@code fabric.mod.json} declares
 * {@code "minecraft": ">=26.1-"}, {@code "java": ">=25"} and
 * {@code "fabric-transfer-api-v1": ">=5.1.0"} — this project ships MC 26.2,
 * JDK 25 and transfer-api 8.0.11 (Fabric API 0.155.2), so all three ranges
 * hold. MIT licensed.</p>
 *
 * <h2>The unit decision, pinned here and nowhere else</h2>
 *
 * <p><b>1 RF = 1 E ({@link #E_PER_RF}).</b> TR's Energy API deliberately
 * names no exchange rate of its own — its unit is whatever the ecosystem
 * agrees on, and the ecosystem's convention is parity with Forge Energy,
 * which is the RF API's direct descendant (RedstoneFlux-1.12's own README:
 * "use Forge Energy - it's literally the RF capability"). So RF ints pass
 * through unscaled: {@link #toE} is a widening, {@link #toRf} a clamped
 * narrowing that only the theoretical {@code >2^31} third-party transfer
 * ever clips. Every conversion in the API goes through these two methods,
 * so if the rate ever needs to change, it changes in one line.</p>
 *
 * <h2>The item bridge</h2>
 *
 * <p>Items need no lookup registration mod-side ({@code instanceof
 * EnergyContainerItem} is the discovery mechanism, as {@code instanceof
 * IEnergyContainerItem} was in 1.6.4) — but third-party chargers discover
 * chargeable items through {@code EnergyStorage.ITEM}. {@link #init}
 * registers one fallback provider that answers for every
 * {@link EnergyContainerItem}, built with TR's own
 * {@link SimpleEnergyItem#createStorage} so the transactional stack-exchange
 * plumbing is theirs, not ours. Both sides read and write the same number:
 * {@link ItemEnergy} is installed with TR's own
 * {@code ENERGY_COMPONENT} (a {@code DataComponentType<Long>}, registered as
 * {@code team_reborn_energy:energy} by TR's entrypoint — bytecode-verified),
 * so a TR machine charging our item and our Energetic Infuser charging it
 * write the identical component, and TR's "empty means no component" rule is
 * mirrored in {@code ItemEnergy.set}.</p>
 */
final class FabricEnergyBridge {

    /**
     * The pinned exchange rate. See the class Javadoc before ever changing
     * it — and note {@link #toE}/{@link #toRf} are the only two places that
     * may read it.
     */
    private static final long E_PER_RF = 1L;

    private FabricEnergyBridge() {
    }

    /** RF (mod-facing int) to E (platform-facing long). */
    static long toE(int rf) {
        return rf * E_PER_RF;
    }

    /** E to RF, clamped into int range rather than overflowing. */
    static long toRf(long e) {
        return Math.min(e / E_PER_RF, Integer.MAX_VALUE);
    }

    /**
     * Called once from {@link DedsApiFabric#onInitialize()}. TR's own
     * entrypoint has already run by then — {@code deds_api} declares
     * {@code team_reborn_energy} as a dependency, and Fabric initializes
     * dependencies first — so the component this installs is registered.
     */
    static void init() {
        ItemEnergy.install(EnergyStorage.ENERGY_COMPONENT);
        EnergyStorage.ITEM.registerFallback((stack, context) ->
                stack.getItem() instanceof EnergyContainerItem item
                        ? SimpleEnergyItem.createStorage(context,
                                toE(item.getMaxEnergyStored(stack)),
                                toE(item.getMaxReceive(stack)),
                                toE(item.getMaxExtract(stack)))
                        : null);
    }
}
