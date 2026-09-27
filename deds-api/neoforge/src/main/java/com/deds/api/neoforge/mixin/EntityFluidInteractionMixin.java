package com.deds.api.neoforge.mixin;

import com.deds.api.neoforge.transfer.DedFluidType;

import net.neoforged.neoforge.fluids.FluidType;

import net.minecraft.world.entity.EntityFluidInteraction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes entities ignore fluids registered through Ded's API, as vanilla and
 * Fabric entities do: vanilla tracks only fluids tagged water or lava, so on
 * Fabric a modded fluid never pushes, floats, drowns, slows or cushions an
 * entity. NeoForge instead tracks every fluid TYPE, and a type's default
 * movement is "no movement at all", so without this an entity walking into a
 * Ded's fluid would freeze in place.
 *
 * <p>{@code getTrackerFor(FluidType)} is the one place a tracker is created
 * or found (its only caller null-checks the result, and null is also what
 * vanilla returns for an untracked fluid), so answering null for a
 * {@link DedFluidType} turns off every tracker-driven behaviour at once.
 * {@code Tracker} is a private class, hence the erased callback type.</p>
 *
 * <p>This is the canonical (26.2) version. NeoForge 26.3 rebuilt
 * {@code EntityFluidInteraction} (one tracker per fluid, never null), so a
 * 26.3 build of the API replaces this file with a twin that hides Ded's
 * fluids from the update loop instead (the standalone repositories keep it
 * under {@code versions/mc26.3/}).</p>
 */
@Mixin(EntityFluidInteraction.class)
public abstract class EntityFluidInteractionMixin {

    @Inject(method = "getTrackerFor(Lnet/neoforged/neoforge/fluids/FluidType;)"
            + "Lnet/minecraft/world/entity/EntityFluidInteraction$Tracker;",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void deds_api$ignoreDedFluids(FluidType type,
            CallbackInfoReturnable<Object> cir) {
        if (type instanceof DedFluidType) {
            cir.setReturnValue(null);
        }
    }
}
