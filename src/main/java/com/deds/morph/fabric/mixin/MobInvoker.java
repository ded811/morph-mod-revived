package com.deds.morph.fabric.mixin;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code Mob.mobInteract(Player, InteractionHand)} is {@code protected} (javap-
 * verified), so this {@link Invoker} exposes it to the morph interaction router,
 * which runs a morph's OWN interaction logic on a transient sandboxed dummy to
 * yield milk / stew / shear drops generically (spec §3).
 */
@Mixin(Mob.class)
public interface MobInvoker {

    @Invoker("mobInteract")
    InteractionResult deds_morph$mobInteract(Player player, InteractionHand hand);
}
