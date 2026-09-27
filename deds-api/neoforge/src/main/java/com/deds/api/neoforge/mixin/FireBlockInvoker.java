package com.deds.api.neoforge.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FireBlock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Vanilla's {@code FireBlock.setFlammable(block, igniteOdds, burnOdds)},
 * private on NeoForge (its access transformer opens only the two getters).
 * It fills the same fire tables Fabric's {@code FlammableBlockRegistry}
 * answers from, in the same argument order, and NeoForge's
 * {@code IBlockExtension} fire methods read those tables by default. Used for
 * {@code BlockSettings.flammable}.
 */
@Mixin(FireBlock.class)
public interface FireBlockInvoker {

    @Invoker("setFlammable")
    void deds_api$setFlammable(Block block, int igniteOdds, int burnOdds);
}
