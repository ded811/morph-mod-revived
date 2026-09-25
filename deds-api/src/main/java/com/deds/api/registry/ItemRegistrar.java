package com.deds.api.registry;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.function.Function;

/**
 * Registers items for one mod (namespace fixed to the owning mod's id).
 * Same boundary rules as {@link BlockRegistrar}.
 */
public interface ItemRegistrar {

    /** Registers a plain {@link Item}. */
    RegistryHandle<Item> register(String name, ItemSettings settings);

    /** Registers a custom item; factory receives prepared vanilla properties. */
    RegistryHandle<Item> register(String name, ItemSettings settings,
            Function<Item.Properties, ? extends Item> factory);

    /**
     * Registers the standard placer item for an already-registered block,
     * under the same name. Wires the vanilla block-item plumbing the modern
     * registry demands: block-prefixed display name and the block→item map
     * that powers {@code Block.asItem()}/pick-block (critique F1,
     * 2026-07-19).
     */
    RegistryHandle<Item> registerBlockItem(RegistryHandle<? extends Block> block,
            ItemSettings settings);

    /**
     * Like {@link #registerBlockItem(RegistryHandle, ItemSettings)} but with
     * a custom item factory (e.g. a two-block-tall door placer). The same
     * block-item plumbing is applied around the factory.
     */
    RegistryHandle<Item> registerBlockItem(RegistryHandle<? extends Block> block,
            ItemSettings settings,
            Function<Item.Properties, ? extends Item> factory);

    /**
     * A placer item for {@code block} registered under its OWN {@code name}
     * rather than the block's — for the case where several items place the
     * same block (v1.6, first consumer: Carpenter's five slope placers, which
     * replace the original's one item with damage 0-4).
     *
     * <p>All the block-item plumbing still happens here rather than in mod
     * code: the block-prefixed display name, and {@code Item.BY_BLOCK} for
     * {@code Block.asItem()}/pick-block. The FIRST item registered for a block
     * wins that mapping, so register the canonical placer first — with N items
     * over one block, pick-block necessarily resolves to one of them.</p>
     *
     * <p><b>The "first wins" rule needs an explicit put, not
     * {@code putIfAbsent}</b> (fixed 2026-07-26 after a wave-2 review; the
     * assertion that caught it is
     * {@code ShapeEditorGameTests.everySlopePlacerSeedsItsOwnFamily}). Fabric's
     * {@code BlockItemTracker} already {@code put}s every {@code BlockItem}
     * into {@code Item.BY_BLOCK} from a registry-entry-added callback, so a
     * {@code putIfAbsent} afterwards is always a no-op and the LAST
     * registration silently wins. See the implementation note in
     * {@code FabricModContext.registerBlockItem}.</p>
     */
    RegistryHandle<Item> registerBlockItem(String name,
            RegistryHandle<? extends Block> block, ItemSettings settings,
            Function<Item.Properties, ? extends Item> factory);
}
