package com.deds.morph;

import com.deds.morph.mixin.MobInvoker;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production interactions (spec §3): milk / mushroom-stew / shear, run through
 * the morph mob's OWN {@code mobInteract} on a transient, sandboxed dummy so any
 * milkable/shearable mob — vanilla or modded — "just works" with zero per-mob
 * code. The dummy is created in the victim's {@link ServerLevel} at the victim's
 * position, so sounds and item drops land on the victim; it is never added to the
 * world entity list and is GC'd after the call.
 *
 * <p>Guarded by a held-item whitelist ({@link #isProductionItem}, config
 * {@code productionItems}, default empty-bucket/bowl/shears) so a player can't
 * lose a saddle/lead/dye onto the dummy, and by a per-(victim,interaction)
 * cooldown ({@code harvestCooldownTicks}) — the "wool regrows" / no-dupe analog,
 * since the rebuilt dummy has no persistent sheared/milked state.</p>
 */
public final class MorphInteractionEffects {

    /** Per-(victim, item) last-harvest game-tick, for the cooldown. */
    private static final Map<String, Long> COOLDOWN = new ConcurrentHashMap<>();

    private MorphInteractionEffects() {
    }

    /** Whether the held item is a whitelisted production container/tool. */
    public static boolean isProductionItem(ItemStack held) {
        if (held.isEmpty()) {
            return false;
        }
        String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        return Morph.config().productionItems().contains(id);
    }

    /**
     * Runs the morph's {@code mobInteract} for a whitelisted held item on a
     * sandboxed dummy. Returns the mob's result if it consumed the action (so
     * vanilla processing is cancelled and the item is transformed/drops occur),
     * {@code FAIL} while the harvest is cooling, or {@code PASS} otherwise.
     */
    public static InteractionResult run(ServerPlayer victim, MorphVariant variant,
            Player player, InteractionHand hand) {
        if (!(victim.level() instanceof ServerLevel level)) {
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);
        UUID id = victim.getUUID();
        String key = id + ":" + BuiltInRegistries.ITEM.getKey(held.getItem());
        long now = level.getGameTime();
        int cooldown = Morph.config().harvestCooldownTicks();
        Long last = COOLDOWN.get(key);
        if (cooldown > 0 && last != null && now - last < cooldown) {
            return InteractionResult.FAIL; // harvest cooling (wool-regrow analog)
        }

        LivingEntity dummy = MorphEntities.create(variant, level);
        if (!(dummy instanceof Mob mob)) {
            return InteractionResult.PASS;
        }
        if (mob instanceof net.minecraft.world.item.trading.Merchant) {
            // A villager-shaped player is not a shop: the copy opened a trade
            // screen with full stock every time.
            return InteractionResult.PASS;
        }
        if (mob instanceof net.minecraft.world.entity.Bucketable) {
            // A bucket on a sulfur-cube-shaped player filled a Sulfur Cube
            // Bucket - a REAL mob, placeable, every cooldown: mob duplication
            // out of the sandbox. Fish/axolotl/tadpole would do the same with
            // a water bucket in productionItems.
            return InteractionResult.PASS;
        }
        java.util.EnumSet<net.minecraft.world.entity.EquipmentSlot> emptySlots =
                java.util.EnumSet.noneOf(net.minecraft.world.entity.EquipmentSlot.class);
        for (net.minecraft.world.entity.EquipmentSlot slot
                : net.minecraft.world.entity.EquipmentSlot.values()) {
            if (mob.getItemBySlot(slot).isEmpty()) {
                emptySlots.add(slot);
            }
        }
        // Place the dummy on the victim so sounds/drops land there.
        dummy.snapTo(victim.getX(), victim.getY(), victim.getZ(),
                victim.getYRot(), 0.0f);
        dummy.setYBodyRot(victim.getYRot());

        InteractionResult result;
        MorphSandbox.begin(); // suppress transform/split spawns; keep item/XP drops
        try {
            result = ((MobInvoker) mob).deds_morph$mobInteract(player, hand);
            // Still inside the sandbox: a loader that moved a production
            // interaction out of mobInteract gets to run it here, with the
            // same spawn rules. Vanilla (Fabric) keeps the result unchanged;
            // NeoForge moved shearing into the shears item (see MorphLoader).
            result = MorphLoader.get().afterMobInteract(player, hand, mob, result);
        } finally {
            MorphSandbox.end();
        }
        if (handBack(mob, emptySlots, player, level)) {
            // The copy took the item (an allay does): it would vanish with the
            // copy, so it went back to the player and nothing was harvested.
            return InteractionResult.PASS;
        }
        if (result.consumesAction()) {
            COOLDOWN.put(key, now);
            return result;
        }
        return InteractionResult.PASS;
    }

    /** Returns whatever the copy put into a slot that was empty (a hand, or a
     *  sulfur cube's body) to the player; true if anything. */
    private static boolean handBack(Mob mob,
            java.util.Set<net.minecraft.world.entity.EquipmentSlot> emptySlots,
            Player player, ServerLevel level) {
        boolean any = false;
        for (net.minecraft.world.entity.EquipmentSlot slot : emptySlots) {
            ItemStack taken = mob.getItemBySlot(slot);
            if (!taken.isEmpty()) {
                ItemStack back = taken.copy();
                mob.setItemSlot(slot, ItemStack.EMPTY);
                // A creative player never lost the item: giving it back would
                // duplicate it.
                if (!player.getAbilities().instabuild
                        && !player.getInventory().add(back)) {
                    player.spawnAtLocation(level, back);
                }
                any = true;
            }
        }
        return any;
    }
}
