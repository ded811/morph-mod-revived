package com.deds.morph.neoforge;

import com.deds.morph.MorphLoader;
import com.deds.morph.mixin.VillagerHostilesSensorAccessor;

import net.neoforged.neoforge.common.IShearable;
import net.neoforged.neoforge.registries.datamaps.builtin.AcceptableVillagerDistance;
import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The vanilla behaviour NeoForge rewrote, answered the way NeoForge's own
 * patched code answers it for a real mob, so a morph of that mob behaves the
 * same (found by auditing every Morph mixin target and vanilla call against
 * the NeoForge-patched Minecraft 26.2 and 26.3; both are identical here).
 */
final class NeoForgeMorphLoader implements MorphLoader {

    /**
     * NeoForge moved villager fear distances into the data map
     * {@code neoforge:acceptable_villager_distances} and patched
     * {@code VillagerHostilesSensor} to read the data map first and the
     * (now deprecated, still present) vanilla table second. The same order
     * here. NeoForge ships the map with the table's eleven entries and
     * values, so with no datapack changing it the answer equals Fabric's.
     */
    @Override
    public Float villagerFearDistance(EntityType<?> type) {
        AcceptableVillagerDistance fromDataMap = type.builtInRegistryHolder()
                .getData(NeoForgeDataMaps.ACCEPTABLE_VILLAGER_DISTANCES);
        if (fromDataMap != null) {
            return fromDataMap.distance();
        }
        return VillagerHostilesSensorAccessor.deds_morph$hostiles().get(type);
    }

    /**
     * NeoForge removed the shears branch from {@code mobInteract} of Sheep,
     * Snow Golem and Bogged ({@code if (false && ...) // Neo: Shear logic is
     * handled by IShearable}) and shears in {@code ShearsItem.interactLivingEntity}
     * instead, which vanilla reaches from {@code Player.interactOn} only after
     * {@code mobInteract} declined. Morph calls {@code mobInteract} on its
     * dummy directly, so on NeoForge shearing a sheep-shaped player did
     * nothing. When the dummy declined and the player holds vanilla shears
     * (the same {@code is(Items.SHEARS)} test the removed branch made) on an
     * {@link IShearable}, run that same item fallback, still inside Morph's
     * sandbox, so the drops, the SHEAR game event and the shears' damage all
     * happen as NeoForge shears a real one.
     *
     * <p>One more branch of the removed Sheep code: a sheep that could not be
     * sheared (a baby, or already shorn) returned CONSUME, so on Fabric Morph
     * starts its harvest cooldown and the server stops handling the click.
     * NeoForge's shears answer PASS there, which would let the click through
     * to other handlers instead; so a PASS on a Sheep becomes CONSUME. Snow
     * Golem and Bogged had no such branch.</p>
     */
    @Override
    public InteractionResult afterMobInteract(Player player, InteractionHand hand,
            Mob mob, InteractionResult result) {
        ItemStack held = player.getItemInHand(hand);
        if (result.consumesAction() || !held.is(Items.SHEARS)
                || !(mob instanceof IShearable)) {
            return result;
        }
        InteractionResult sheared = held.interactLivingEntity(player, mob, hand);
        if (sheared == InteractionResult.PASS && mob instanceof Sheep) {
            return InteractionResult.CONSUME;
        }
        return sheared;
    }
}
