package com.deds.morph.fabric.mixin;

import com.deds.morph.MorphAbilities;
import com.deds.morph.MorphAbility;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The {@code swim} ability's water-drag boost WITHOUT the potion effect (wave 6
 * item A). The old implementation applied a hidden {@code DOLPHINS_GRACE}
 * ({@code showIcon=false}), but {@code EffectsInInventory.extractEffects} has no
 * {@code showIcon} filter (javap-verified), so the icon leaked into the inventory
 * side panel — the effect itself has to go.
 *
 * <p>Vanilla consumes the effect at EXACTLY ONE site: {@code
 * LivingEntity.travelInWater(Vec3,double,boolean,double)}'s single
 * {@code hasEffect(MobEffects.DOLPHINS_GRACE)} check, which flattens the water
 * drag to {@code DOLPHINS_GRACE_WATER_DRAG} (0.96). This redirect ORs that check
 * with "is a player whose active morph abilities contain SWIM" — identical drag
 * math by construction (never a re-derived client clamp), on BOTH sides (the
 * client is movement-authoritative, the server ghost-runs the same method for the
 * move check — hence a COMMON mixin). A REAL dolphin-gifted/potion effect still
 * passes through the original {@code hasEffect} arm and keeps its icon; stacking
 * is a boolean OR (0.96 stays 0.96). With {@code abilities=false},
 * {@code activeAbilities} is empty and this is a no-op.</p>
 *
 * <p>Recreation of iChun's Morph; all credit for the original design to iChun.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityWaterDragMixin {

    @Redirect(method = "travelInWater(Lnet/minecraft/world/phys/Vec3;DZD)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;hasEffect(Lnet/minecraft/core/Holder;)Z"))
    private boolean deds_morph$swimDrag(LivingEntity self,
            Holder<MobEffect> effect) {
        return self.hasEffect(effect)
                || (self instanceof Player player
                        && MorphAbilities.activeAbilities(player)
                                .contains(MorphAbility.SWIM));
    }
}
