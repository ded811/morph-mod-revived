package com.deds.api.fluid;

import com.deds.api.registry.RegistryHandle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Fluids for one mod (Ded's API v2.1) — {@code ModContext.fluids()}.
 *
 * <p><b>Deliberately four verbs.</b> This is the smallest thing that lets a
 * Forge-era fluid machine work on 26.2: register a fluid, let the loader see
 * your tanks, see the loader's tanks, and handle a bucket. It is <b>not</b> a
 * fluid framework — there is no pipe model, no multi-tank container type, no
 * fluid-attribute registry and no rendering. Each of those waits for a real
 * consumer (docs/MOD-COOKBOOK.md §14, docs/API-COMPATIBILITY.md §1).</p>
 *
 * <p>"Let the loader see your tanks" is <b>two overloads</b> since v2.7 — the
 * v2.1 {@link #exposeTanks(RegistryHandle, Function) same-tanks-every-side}
 * form and the v2.7 {@link #exposeTanks(RegistryHandle, BiFunction) per-side}
 * one. They are one verb and one backend: the old form delegates to the new
 * with a side-ignoring function, so nothing written against v2.1 moves.</p>
 *
 * <p>Its first consumer is OpenBlocks' tank chain (Tank, XP Drain, XP Shower,
 * Sprinkler); the per-member justification — what each of these exists for
 * and which gadget demanded it — is the table in {@code docs/API-ROADMAP.md}'s
 * v2.1 section (and its v2.7 section for the per-side overload), and the
 * recipe for using them is {@code docs/MOD-COOKBOOK.md} §17 ("Fluids").</p>
 */
public interface FluidRegistrar {

    /**
     * Registers a fluid in the mod's namespace.
     *
     * <p>The {@link Fluid} subclass itself is ordinary vanilla surface
     * (docs/ARCHITECTURE.md, "Level 1 pragmatism"); what goes through the API
     * is the registration, like every other registry.</p>
     *
     * <p>A fluid with no block is fine and is the common case for a port —
     * Forge's {@code FluidRegistry} never required one either. Implement
     * {@code createLegacyBlock} as air and {@code getBucket} as your bucket
     * item (or {@code Items.AIR} for a fluid that has none).</p>
     *
     * @param name  registry path within the mod's namespace
     * @param fluid the instance
     * @return handle to the registered fluid
     */
    RegistryHandle<Fluid> register(String name, Fluid fluid);

    /**
     * Publishes a block entity's tanks to the loader's fluid transport, so
     * other mods' pipes, pumps and tanks can move fluid in and out of it —
     * <b>the same tanks on every side</b>.
     *
     * <p>This is the v2.1 form and it is not deprecated: a block whose fluid
     * connection genuinely does not depend on the face — OpenBlocks' Tank, a
     * dynamo's fuel tank — should keep using it, because it says so. It is
     * exactly {@link #exposeTanks(RegistryHandle, BiFunction)} called with a
     * function that ignores {@code side}, and that is literally how the
     * backend implements it: one code path, two spellings. A block that
     * <em>does</em> gate by face (Thermal Expansion's machines, its portable
     * tanks) wants the v2.7 overload instead.</p>
     *
     * <p>The list is the block entity's tanks <b>in transfer order</b>, and
     * the API walks it as a <b>chain, not a bag</b>. Insertion goes front to
     * back and <b>stops</b> at the first tank that will not take the fluid —
     * one already holding something else, or one whose
     * {@link FluidTankView#accepts} refuses it. Extraction goes front to back
     * and stops at the first tank that is not holding the fluid being pulled,
     * an empty one included. A tank that merely has no room, or nothing left,
     * does <em>not</em> end the walk. That is the 1.6.4 tank-column rule
     * ({@code TileEntityTank.fillColumn}'s {@code if (!accepts(resource))
     * return;} and {@code drainFromColumn}'s
     * {@code if (!containsFluid(needed)) return;}, OB-1.2.9 :359 and :381), and
     * it is what lets a foreign fluid <em>partition</em> a column instead of
     * being stepped over.</p>
     *
     * <p>So a block with one tank passes {@code List.of(view)}, and a
     * multiblock that presents several (OpenBlocks' vertical tank column)
     * passes them in the order the original filled them. <b>Do not use one
     * list for several INDEPENDENT tanks</b> — a water input and a lava output
     * on one block entity — because the chain rule lets the first block the
     * second; that case needs a seam of its own and has no consumer yet
     * (docs/MOD-COOKBOOK.md §14). The function is called on every lookup, so
     * it may compute the list freshly — but it must not load chunks.</p>
     *
     * <p>Capacity clamping, same-fluid merging, filtering via
     * {@link FluidTankView#accepts} and transaction rollback are handled by
     * the API. {@link FluidTankView#onChanged()} fires after a committed
     * transfer, once per exposed storage the transfer wrote through — see that
     * method, which must be idempotent.</p>
     *
     * @param type  the block entity type to publish
     * @param tanks the tanks of one instance, in transfer order; an empty
     *              list means "no fluid connection right now"
     * @param <T>   the block entity class
     */
    <T extends BlockEntity> void exposeTanks(
            RegistryHandle<BlockEntityType<T>> type,
            Function<T, List<FluidTankView>> tanks);

    /**
     * Publishes a block entity's tanks <b>per side</b> (Ded's API v2.7), so a
     * machine that only accepts fluid on some of its faces can say so.
     *
     * <p><b>This mirrors the energy seam exactly</b>, deliberately and down to
     * the parameter shape: read
     * {@link com.deds.api.energy.EnergyRegistrar#exposeStorage} beside it and
     * the two should read as one idea. The function is asked <b>per side</b>:
     * {@code (blockEntity, side)} with {@code side} the face being approached,
     * or {@code null} for "no particular side" — the modern spelling of
     * Forge's {@code ForgeDirection.UNKNOWN}, which the 1.6.4 originals let
     * bypass their side gating. A caller that means "everywhere" must
     * therefore answer for the {@code null} side too, not just the six faces.
     * </p>
     *
     * <p>Return an <b>empty list or {@code null}</b> for a side with no fluid
     * connection at all; the two mean the same thing and the lookup then
     * reports the block as having no tank on that face, exactly as a 1.6.4
     * {@code IFluidHandler} whose {@code getTankInfo(side)} returned nothing
     * did. Return the tanks that face may reach, in <b>transfer order</b>, for
     * every other side.</p>
     *
     * <p>Everything else is the single-argument overload's contract, unchanged
     * and worth re-reading there: the list is walked as a <b>chain, not a
     * bag</b> (a tank that refuses the fluid on identity grounds <em>ends</em>
     * the walk), one list must not mix INDEPENDENT tanks, and capacity
     * clamping, same-fluid merging, {@link FluidTankView#accepts} and
     * transaction rollback are the API's job. {@link FluidTankView#onChanged()}
     * fires after a committed transfer, once per exposed storage the transfer
     * wrote through, so it must be idempotent.</p>
     *
     * <p>The function is called <b>on every lookup</b> — once per side per
     * query, not once at registration — so it may compute the list freshly,
     * <b>but it must not load chunks</b>. Answer from the block entity's own
     * state (its side-configuration array, its facing) and nothing else.</p>
     *
     * <p><b>Still deliberately NOT here: an insert-versus-extract gate.</b>
     * Every tank in the list a side returns is both insertable and
     * extractable from that side; Forge's {@code canFill}/{@code canDrain}
     * pair has only ever had its {@code canFill} half, through
     * {@link FluidTankView#accepts}. A fill-only or drain-only <em>face</em>
     * is still inexpressible, which is why OpenBlocks' Sprinkler, XP Bottler
     * and Vacuum Hopper remain unexposed rather than wrongly exposed. That is
     * a separate additive change and it still has no consumer that a
     * refusing {@code accepts} does not already serve (docs/MOD-COOKBOOK.md
     * §14).</p>
     *
     * <p><b>One overload-resolution footnote.</b> A zero-argument method
     * reference ({@code MyBlockEntity::tanks}) selects the v2.1 form and a
     * two-parameter lambda selects this one, with no ambiguity — but a block
     * entity that declares <em>both</em> {@code tanks()} and
     * {@code tanks(Direction)} makes {@code MyBlockEntity::tanks} match both
     * overloads and javac will reject it. Give the per-side method its own
     * name, or pass an explicit {@code (be, side) -> …} lambda.</p>
     *
     * @param type  the block entity type to publish
     * @param tanks {@code (blockEntity, side) -> tanks}, with {@code side}
     *              possibly {@code null}; an empty list or {@code null} means
     *              "no fluid connection on that side"
     * @param <T>   the block entity class
     */
    <T extends BlockEntity> void exposeTanks(
            RegistryHandle<BlockEntityType<T>> type,
            BiFunction<T, Direction, List<FluidTankView>> tanks);

    /**
     * The fluid connection of the block at {@code pos}, approached from
     * {@code side}, or {@code null} if that block has none.
     *
     * <p>The replacement for {@code tile instanceof IFluidHandler}. Answers
     * for anything the loader knows about — our own blocks published through
     * {@link #exposeTanks}, other mods' machines, and vanilla blocks with
     * loader-provided storages such as cauldrons.</p>
     *
     * <p>Reads the block entity at {@code pos}, so <b>the caller must have
     * established that the position is loaded</b>. On 26.2
     * {@code Level.getBlockState} resolves through
     * {@code getChunk(x, z, ChunkStatus.FULL)}, whose {@code requireChunk}
     * argument defaults to {@code true} (javap), so an unloaded position is
     * not reported absent — the chunk is generated, on the server thread.
     * Guard with {@code Level.isLoaded(pos)}, which is
     * {@code isInValidBounds(pos) && getChunkSource().hasChunk(...)} (javap) —
     * exactly as the sponge sweep and the elevator's shaft search already do.
     * {@code docs/MOD-COOKBOOK.md} §17 ("Fluids") has the snippet.</p>
     *
     * @param level the world
     * @param pos   the block to ask
     * @param side  the face being approached, i.e. the direction from that
     *              block towards the caller; {@code null} means "no
     *              particular side"
     * @return the port, or {@code null}
     */
    FluidPort port(Level level, BlockPos pos, Direction side);

    /**
     * Runs the vanilla "click a tank with a bucket" verb against a set of
     * tanks: fills the held container from them, or empties it into them,
     * whichever applies, consuming and replacing the item as vanilla does.
     *
     * <p>This is the descendant of Forge's
     * {@code FluidContainerRegistry.getFluidForFilledItem} /
     * {@code fillFluidContainer} pair, and like it, it handles <b>every</b>
     * registered container — vanilla buckets and bottles, and other mods'
     * containers — not just the ones the calling mod knows about. There is no
     * way to reproduce that mod-side without loader imports, which is why it
     * is here with one consumer.</p>
     *
     * @param tanks  the same list {@link #exposeTanks} would produce
     * @param player whoever clicked; their hand item is read and replaced
     * @param hand   which hand
     * @return {@code true} if fluid moved
     */
    boolean useHeldContainer(List<FluidTankView> tanks, Player player,
            InteractionHand hand);
}
