package com.deds.api.energy;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;

/**
 * The charge on an {@link ItemStack} (Ded's API v2.4) — where 1.6.4 read and
 * wrote the stack's NBT {@code "Energy"} tag, 26.2 reads and writes a data
 * component, and this class is the one place that knows which one.
 *
 * <p>{@link #set} fills the role {@code cofh.util.EnergyHelper}'s
 * {@code setDefaultEnergyTag} had in 1.6.4: TE3 uses it to mint pre-charged
 * stacks ({@code ItemCharmRF}, the creative-tab charged variants). It is not
 * a port of it (that wrote an NBT tag; this writes a data component and
 * removes it at 0). {@link #get} is its read half, and what
 * {@link EnergyContainerItem}'s defaults are defined in terms of; mod code
 * normally goes through those RF-shaped methods rather than coming here.</p>
 *
 * <p>The component itself belongs to the platform bridge, not to any one
 * mod, so a third-party charger and an {@link EnergyContainerItem} read the
 * same number — and so an <b>empty</b> stack carries no component at all
 * ({@link #set} with 0 removes it), which keeps a drained item stackable
 * with a factory-fresh one, exactly as the bridged standard does
 * (docs/API-ROADMAP.md v2.4, "the item bridge").</p>
 */
public final class ItemEnergy {

    private static volatile DataComponentType<Long> component;

    private ItemEnergy() {
    }

    /**
     * RF stored on the stack; 0 for a stack that has never been charged.
     * (The component is wider than RF's int; a value beyond int range —
     * impossible through this API, whose capacities are ints — reads as
     * {@code Integer.MAX_VALUE} rather than overflowing.)
     */
    public static int get(ItemStack stack) {
        long stored = stack.getOrDefault(componentOrThrow(), 0L);
        return (int) Math.min(stored, Integer.MAX_VALUE);
    }

    /**
     * Sets the RF stored on the stack, unconditionally — no capacity check,
     * exactly like the {@code setDefaultEnergyTag} it ports; the checked
     * path is {@link EnergyContainerItem#receiveEnergy}. Zero or negative
     * removes the component entirely (see the class Javadoc for why).
     */
    public static void set(ItemStack stack, int energy) {
        if (energy <= 0) {
            stack.remove(componentOrThrow());
        } else {
            stack.set(componentOrThrow(), (long) energy);
        }
    }

    /**
     * Internal: installed once by the platform bootstrap, before any mod
     * initializes (the API is a declared dependency of every mod, so its
     * entrypoint runs first). Not for mods — the same standing as
     * {@code ClientKeys.install}.
     */
    public static void install(DataComponentType<Long> energyComponent) {
        component = energyComponent;
    }

    private static DataComponentType<Long> componentOrThrow() {
        DataComponentType<Long> c = component;
        if (c == null) {
            throw new IllegalStateException("ItemEnergy used before the "
                    + "platform installed the energy component - is Ded's API "
                    + "initialized?");
        }
        return c;
    }
}
