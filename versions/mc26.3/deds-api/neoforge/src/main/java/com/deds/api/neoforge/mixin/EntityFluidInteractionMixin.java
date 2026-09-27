package com.deds.api.neoforge.mixin;

import com.deds.api.neoforge.transfer.DedFluidType;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.level.material.FluidState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The Minecraft 26.3 twin of the canonical {@code EntityFluidInteractionMixin}:
 * entities ignore fluids registered through Ded's API, so an entity in one is
 * not pushed, does not swim, float or drown, and falls through it as through
 * air.
 *
 * <p>26.3 rebuilt {@code EntityFluidInteraction}: it keeps one tracker per
 * fluid (not per NeoForge {@code FluidType}), made by
 * {@code getOrCreateTrackerFor(Holder<Fluid>)}, which never answers null and
 * whose result the loop in {@code update} uses unchecked. So instead of
 * answering null for a Ded's fluid type, this hides Ded's fluids from that
 * loop: its one {@code FluidState.isEmpty()} call answers true for them, and
 * no tracker or current is ever made for one. That is what the canonical
 * version achieves on 26.2.</p>
 *
 * <p>One difference from Fabric on 26.3, where vanilla now keeps a tracker
 * for every fluid: sprinting inside a Ded's fluid spawns sprint particles
 * here and not on Fabric ({@code isInAnyFluid}, their only other reader).</p>
 */
@Mixin(EntityFluidInteraction.class)
public abstract class EntityFluidInteractionMixin {

    @WrapOperation(method = "update(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FluidState;isEmpty()Z"),
            require = 1, allow = 1)
    private boolean deds_api$ignoreDedFluids(FluidState state, Operation<Boolean> original) {
        return original.call(state) || state.getType().getFluidType() instanceof DedFluidType;
    }
}
