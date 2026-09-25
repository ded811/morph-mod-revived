package com.deds.morph.fabric.client.mixin;

import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches the protected {@code Entity.setSharedFlag(int, boolean)} (javap-verified
 * {@code protected void setSharedFlag(int, boolean)}) so a morph dummy can be given
 * the wearer's replicated shared flags.
 *
 * <p>Used for the ELYTRA GLIDE pose (wave 5 item A3): flag <b>7</b> backs
 * {@code LivingEntity.isFallFlying()} (javap: {@code isFallFlying} is exactly
 * {@code getSharedFlag(7)}), and {@code HumanoidRenderState.extractHumanoidRenderState}
 * reads {@code isFallFlying()} off the DUMMY — so {@code setPose(FALL_FLYING)} alone
 * never reached it and the gliding pose was invisible to every viewer, including the
 * wearer's own third person. The flag is server-replicated, so mirroring it works for
 * remote observers too.</p>
 *
 * <p>Recreation of iChun's Morph; all credit for the original design to iChun.</p>
 */
@Mixin(Entity.class)
public interface EntitySharedFlagsInvoker {

    @Invoker("setSharedFlag")
    void deds_morph$setSharedFlag(int flag, boolean value);
}
