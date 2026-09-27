package com.deds.api.energy;

import com.deds.api.registry.RegistryHandle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.BiFunction;

/**
 * Energy for one mod (Ded's API v2.4) — {@code ModContext.energy()}.
 *
 * <p><b>Deliberately two methods.</b> This is the smallest thing that lets an
 * RF-era machine work on 26.2: let the platform see your buffer, and see the
 * platform's buffers. It is <b>not</b> an energy framework — there is no
 * cable model, no per-tick scheduler and no GUI meter type. Each of those
 * waits for a real consumer (docs/MOD-COOKBOOK.md §14,
 * docs/API-COMPATIBILITY.md §1). Items that hold a charge are the separate
 * {@link EnergyContainerItem}, which needs no registration at all.</p>
 *
 * <p>Its first consumer is the Thermal Expansion revival — every RF shape
 * here is taken from the original 1.6.4 RF API by Team CoFH
 * ({@code cofh.api.energy}, CoFHCore 2.0.0.5 decompile), with the later
 * RedstoneFlux-1.12 split consulted and deliberately NOT taken; the
 * reasoning and the per-member justification live in
 * {@code docs/API-ROADMAP.md}'s v2.4 section.</p>
 *
 * <p><b>The wider ecosystem sees these buffers too.</b> The platform side of
 * this seam is bridged once to the de-facto Fabric energy standard (Team
 * Reborn's Energy API, at 1 RF = 1 E), so a third-party mod's cables can
 * power a machine exposed here and vice versa — the mod itself never sees
 * that; the bridge is a backend detail (docs/API-ROADMAP.md v2.4).</p>
 */
public interface EnergyRegistrar {

    /**
     * Publishes a block entity's energy buffer to the platform's energy
     * transport, so this mod's own machines — via {@link #port} — and other
     * mods' cables can move energy in and out of it.
     *
     * <p>The function is asked <b>per side</b>: {@code (blockEntity, side)}
     * with {@code side} the face being approached, or {@code null} for "no
     * particular side" — the modern spelling of RF's
     * {@code ForgeDirection.UNKNOWN}, which the 1.6.4 originals let bypass
     * their side gating ({@code TileEnergyCell.receiveEnergy}, TE3 decompile).
     * Return {@code null} for a side with no connection at all
     * ({@code canInterface == false}); return a view whose caps are 0 for a
     * side that is connectable but passive — the distinction
     * {@link EnergyStorageView}'s class Javadoc walks through, with the cell
     * and the dynamo as the two worked examples.</p>
     *
     * <p>The function is called on every lookup, so it may compute the view
     * freshly — but it must not load chunks. Capacity clamping, the
     * per-operation caps and transaction rollback are handled by the API;
     * {@link EnergyStorageView#onChanged()} fires after a committed
     * transfer.</p>
     *
     * @param type    the block entity type to publish
     * @param storage {@code (blockEntity, side) -> view}, with {@code side}
     *                possibly {@code null}; a {@code null} view means "no
     *                energy connection on that side"
     * @param <T>     the block entity class
     */
    <T extends BlockEntity> void exposeStorage(
            RegistryHandle<BlockEntityType<T>> type,
            BiFunction<T, Direction, EnergyStorageView> storage);

    /**
     * The energy connection of the block at {@code pos}, approached from
     * {@code side}, or {@code null} if that block has none.
     *
     * <p>The replacement for {@code tile instanceof IEnergyHandler} plus
     * {@code canInterface(side)}. Answers for anything the platform knows
     * about — this mod's blocks published through {@link #exposeStorage},
     * and any other mod's machines that speak the bridged standard.</p>
     *
     * <p>Reads the block entity at {@code pos}, so <b>the caller must have
     * established that the position is loaded</b> — the same 26.2 trap as
     * {@link com.deds.api.fluid.FluidRegistrar#port}, documented there with
     * the {@code Level.isLoaded} guard to copy.</p>
     *
     * @param level the world
     * @param pos   the block to ask
     * @param side  the face being approached, i.e. the direction from that
     *              block towards the caller; {@code null} means "no
     *              particular side"
     * @return the port, or {@code null}
     */
    EnergyPort port(Level level, BlockPos pos, Direction side);
}
