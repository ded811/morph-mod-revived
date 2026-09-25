package com.deds.morph.fabric.mixin;

import com.google.common.collect.ImmutableMap;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code VillagerHostilesSensor.ACCEPTABLE_DISTANCE_FROM_HOSTILES} (the
 * private static curated fear list — drowned/evoker/husk/illusioner/pillager/
 * ravager/vex/vindicator/zoglin/zombie/zombie_villager) so the sensor mixin can
 * check a morph type against the exact vanilla set (spec §A.7).
 */
@Mixin(VillagerHostilesSensor.class)
public interface VillagerHostilesSensorAccessor {

    @Accessor("ACCEPTABLE_DISTANCE_FROM_HOSTILES")
    static ImmutableMap<EntityType<?>, Float> deds_morph$hostiles() {
        throw new AssertionError();
    }
}
