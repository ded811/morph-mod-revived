package com.deds.morph.neoforge.mixin;

import com.deds.morph.MorphAbilities;
import com.deds.morph.MorphAbility;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.neoforged.neoforge.fluids.InFluidPredicate;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the turtle helmet from giving a SWIM morph Water Breathing forever
 * underwater on NeoForge.
 *
 * <p>Vanilla {@code Player.tick} gives a turtle-helmeted player Water
 * Breathing while their eyes are NOT in water ({@code !isEyeInFluid(WATER)}).
 * NeoForge rewrote that test as "eyes not in a fluid the player can drown in"
 * ({@code !isEyeInFluidMatching(canDrownInFluidType)}), and for water its
 * {@code canDrownInFluidType} is {@code !canBreatheUnderwater()}, which
 * Morph's {@code LivingEntityBreatheMixin} makes true for a SWIM morph. So a
 * fish-shaped player underwater counted as "not in a drowning fluid" and got
 * the helmet effect, icon included, the whole time; on Fabric (vanilla) they
 * get nothing underwater.</p>
 *
 * <p>Wraps that one call (exactly one match in {@code Player.tick} in both
 * NeoForge builds, javap) and, for a player with SWIM active, also counts
 * eyes in WATER as the vanilla test did, which restores vanilla's answer for
 * that player. Everyone else gets NeoForge's answer unchanged (a modded
 * drownable fluid still counts). SWIM is read the way the breathing mixin
 * reads it. {@code isEyeInFluid(TagKey)} is deprecated on NeoForge in favour
 * of fluid types, but it is the exact vanilla call this puts back.</p>
 */
@Mixin(Player.class)
public abstract class PlayerTurtleHelmetMixin {

    @SuppressWarnings("deprecation")
    @WrapOperation(method = "tick()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/EntityFluidInteraction;"
                            + "isEyeInFluidMatching(Lnet/minecraft/world/entity/Entity;"
                            + "Lnet/neoforged/neoforge/fluids/InFluidPredicate;)Z"),
            require = 1, allow = 1)
    private boolean deds_morph$swimMorphEyesInWater(EntityFluidInteraction fluids,
            Entity entity, InFluidPredicate predicate, Operation<Boolean> original) {
        boolean inDrowningFluid = original.call(fluids, entity, predicate);
        Player self = (Player) (Object) this;
        return inDrowningFluid
                || (MorphAbilities.activeAbilities(self).contains(MorphAbility.SWIM)
                        && self.isEyeInFluid(FluidTags.WATER));
    }
}
