package com.deds.api.registry;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

import java.util.List;
import java.util.function.Supplier;

/**
 * Registers creative-mode tabs for one mod.
 *
 * <p>The tab's display name uses the translation key
 * {@code itemGroup.<modid>.<name>} — provide it in the mod's lang file.</p>
 */
public interface TabRegistrar {

    /**
     * Registers a creative tab.
     *
     * @param name  tab name within the mod's namespace
     * @param icon  item shown on the tab
     * @param items tab contents, in order
     */
    void register(String name, RegistryHandle<? extends Item> icon,
            List<RegistryHandle<? extends Item>> items);

    /**
     * The VANILLA creative tabs a mod item may join (v1.3 — TrailMix, whose
     * original placed items in vanilla {@code tabFood}/{@code tabCombat}
     * instead of an own tab). Portable mirror of the vanilla tab ids.
     */
    enum VanillaTab {
        FOOD_AND_DRINKS("food_and_drinks"),
        COMBAT("combat"),
        TOOLS_AND_UTILITIES("tools_and_utilities"),
        INGREDIENTS("ingredients");

        private final String path;

        VanillaTab(String path) {
            this.path = path;
        }

        /** The vanilla registry path of this tab. */
        public String path() {
            return path;
        }
    }

    /**
     * Appends items to a VANILLA creative tab (v1.3). Creative search and
     * recipe viewers (JEI/EMI) index tab contents, so every obtainable item
     * should join a tab — either a mod tab via
     * {@link #register(String, RegistryHandle, List)} or a vanilla one here,
     * whichever the original mod did.
     *
     * @param tab   the vanilla tab
     * @param items items appended at the tab's end, in order
     */
    void addToVanilla(VanillaTab tab,
            List<RegistryHandle<? extends Item>> items);

    /**
     * Appends prepared STACKS to a vanilla creative tab (v1.3) — for items
     * whose creative entry is not the default stack (TrailMix's launchers
     * show the FULL variant, i.e. damage 1 under their inverted-ammo
     * semantics, exactly as the original's {@code getSubItems} did).
     *
     * @param tab    the vanilla tab
     * @param stacks supplier evaluated per tab rebuild (items must be
     *               registered by then), entries appended in order
     */
    void addStacksToVanilla(VanillaTab tab,
            Supplier<List<ItemStack>> stacks);

    /**
     * Inserts prepared STACKS into a vanilla creative tab immediately AFTER
     * an existing entry, instead of at the tab's end (v1.4).
     *
     * <p>Placement inside a vanilla tab is a real design choice — a launcher
     * belongs next to the other ranged weapons, not below the last arrow —
     * and the tab order is what the creative screen and recipe viewers show.
     * If {@code anchor} is not present in the tab (a datapack removed it, a
     * feature flag hid it), the stacks are APPENDED, so a missing anchor
     * degrades to {@link #addStacksToVanilla} rather than dropping items.</p>
     *
     * @param tab    the vanilla tab
     * @param anchor the existing entry to insert after (e.g. the firework
     *               rocket in {@code COMBAT})
     * @param stacks supplier evaluated per tab rebuild (items must be
     *               registered by then), entries inserted in order
     */
    void insertAfterInVanilla(VanillaTab tab, ItemLike anchor,
            Supplier<List<ItemStack>> stacks);
}
