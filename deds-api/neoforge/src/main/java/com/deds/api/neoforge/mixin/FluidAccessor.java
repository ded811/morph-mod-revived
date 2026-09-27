package com.deds.api.neoforge.mixin;

import net.neoforged.neoforge.fluids.FluidType;

import net.minecraft.world.level.material.Fluid;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * NeoForge's cached fluid type field on {@link Fluid}. The patched
 * {@code Fluid.getFluidType()} returns this field once it is set and only
 * otherwise asks {@code CommonHooks.getVanillaFluidType}, which throws
 * "Mod fluids must override getFluidType" for any fluid that is not water,
 * lava, milk or empty. Setting it at registration gives every fluid
 * registered through Ded's API its type without a NeoForge class in mod code.
 */
@Mixin(Fluid.class)
public interface FluidAccessor {

    @Accessor("forgeFluidType")
    void deds_api$setFluidType(FluidType type);
}
