package com.deds.morph;

import com.deds.morph.fabric.mixin.MobInvoker;

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
        // Place the dummy on the victim so sounds/drops land there.
        dummy.snapTo(victim.getX(), victim.getY(), victim.getZ(),
                victim.getYRot(), 0.0f);
        dummy.setYBodyRot(victim.getYRot());

        InteractionResult result;
        MorphSandbox.begin(); // suppress transform/split spawns; keep item/XP drops
        try {
            result = ((MobInvoker) mob).deds_morph$mobInteract(player, hand);
        } finally {
            MorphSandbox.end();
        }
        if (result.consumesAction()) {
            COOLDOWN.put(key, now);
            return result;
        }
        return InteractionResult.PASS;
    }
}
