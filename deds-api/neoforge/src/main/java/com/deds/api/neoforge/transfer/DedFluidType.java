package com.deds.api.neoforge.transfer;

import net.neoforged.neoforge.common.SoundAction;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;

/**
 * The NeoForge fluid type every fluid registered through Ded's API gets (one
 * per source/flowing pair, as vanilla's water and flowing water share one).
 * NeoForge requires a type for every fluid; without one, a neighbour update
 * next to the fluid, an entity touching it or a bucket emptying it throws.
 *
 * <p>It is deliberately INERT, because on Fabric (and in vanilla) a modded
 * fluid that is not tagged water or lava does nothing to entities or mobs:
 * no pushing, swimming, drowning or fall damage change, not a path type for
 * mobs, no boats, no hydration, no extinguishing. Entities are kept out of it
 * entirely by {@code EntityFluidInteractionMixin}; the properties below are
 * the same answer for any code that asks the type directly.</p>
 *
 * <p>What it does answer, Fabric's way ({@code FluidVariantAttributes}): the
 * name is the fluid block's name (or {@code block.<ns>.<path>} if the fluid
 * has no block), the light level is the fluid block's light, the fill sound is
 * the fluid's own pickup sound or the plain bucket fill, the empty sound the
 * plain bucket empty. All of it is looked up when asked, not when the type is
 * made: a mod may bind its fluid's block and bucket after registering it.</p>
 */
public final class DedFluidType extends FluidType {

    private final Fluid fluid;

    DedFluidType(Fluid fluid) {
        super(FluidType.Properties.create()
                .canPushEntity(false)
                .canSwim(false)
                .canDrown(false)
                .fallDistanceModifier(1.0F)
                .canExtinguish(false)
                .canConvertToSource(false)
                .supportsBoating(false)
                .canHydrate(false)
                .pathType(null)
                .adjacentPathType(null)
                .temperature(300)
                .viscosity(1000)
                .density(1000));
        this.fluid = fluid;
    }

    /** The source fluid of the pair, whose block names and lights it. */
    private Fluid source() {
        if (fluid instanceof FlowingFluid flowing) {
            return flowing.getSource();
        }
        return fluid;
    }

    private BlockState legacyBlock() {
        return source().defaultFluidState().createLegacyBlock();
    }

    @Override
    public String getDescriptionId() {
        Block block = legacyBlock().getBlock();
        if (block == Blocks.AIR) {
            return Util.makeDescriptionId("block",
                    BuiltInRegistries.FLUID.getKey(source()));
        }
        return block.getDescriptionId();
    }

    @Override
    public String getDescriptionId(FluidStack stack) {
        return getDescriptionId();
    }

    @Override
    public int getLightLevel() {
        return legacyBlock().getLightEmission();
    }

    @Override
    public int getLightLevel(FluidStack stack) {
        return getLightLevel();
    }

    /**
     * The sound NeoForge's transfer code plays when another mod fills or
     * empties a container with this fluid. Only this stack-aware overload is
     * overridden, so vanilla's bucket keeps its own fallback when it asks
     * without a stack.
     */
    @Override
    public SoundEvent getSound(FluidStack stack, SoundAction action) {
        if (action == SoundActions.BUCKET_FILL) {
            return stack.getFluid().getPickupSound().orElse(SoundEvents.BUCKET_FILL);
        }
        if (action == SoundActions.BUCKET_EMPTY) {
            return SoundEvents.BUCKET_EMPTY;
        }
        return null;
    }
}
