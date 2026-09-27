package com.deds.api.neoforge.client;

import com.deds.api.client.BlockTints;

import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import it.unimi.dsi.fastutil.ints.IntList;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link BlockTints} on NeoForge: NeoForge's own dynamic-tint hook,
 * {@link IClientBlockExtensions#collectDynamicTintValues}, which its block
 * renderer asks at exactly the point Fabric's {@code BlockColorRegistry}
 * factory is asked (the first tinted quad of a block whose static tint list is
 * empty), with the same arguments.
 *
 * <p>NeoForge takes client extensions only during
 * {@link RegisterClientExtensionsEvent}, posted once while the game window is
 * being created. Registrations made before it (from client init) are queued
 * by the {@link BlockTints} facade and replayed when Ded's API installs this
 * backend at the end of that event; a block registered for the first time
 * after it throws, naming the block. Re-registering a block already handled
 * here replaces its source (last wins, as on Fabric). NeoForge allows one
 * client extension per block, so a block another mod already extended
 * cannot delegate its tint: that registration throws, naming the block,
 * and changes nothing.</p>
 *
 * <p>{@link #collect} asks in Fabric's order: a block with a delegating
 * registration made here answers from it, even if it also has vanilla static
 * tint sources (Fabric's backend asks its factory registry first); any other
 * block answers from vanilla's static sources, then from its NeoForge client
 * extension. The one difference left: a block another mod gave BOTH static
 * sources and its own dynamic tint answers from the static ones here and from
 * the other mod's factory on Fabric. What the world RENDERS is the same on
 * both, because both renderers use static sources first.</p>
 */
final class NeoForgeBlockTints implements BlockTints.Backend {

    private final Map<Block, BlockTints.AppearanceSource> sources = new ConcurrentHashMap<>();

    /** Non-null only while install replays inside the event. */
    private volatile RegisterClientExtensionsEvent window;

    private final IClientBlockExtensions extension = new IClientBlockExtensions() {
        @Override
        public void collectDynamicTintValues(BlockState state,
                BlockAndTintGetter level, BlockPos pos, IntList tintValues) {
            if (level == null || pos == null) {
                return;
            }
            BlockTints.AppearanceSource source = sources.get(state.getBlock());
            if (source == null) {
                return;
            }
            BlockState appearance = source.appearanceAt(level, pos, state);
            if (appearance != null) {
                collect(appearance, level, pos, tintValues);
            }
        }
    };

    /** From DedsApiNeoForgeClient, LOWEST of the client extensions event. */
    void install(RegisterClientExtensionsEvent event) {
        window = event;
        try {
            BlockTints.install(this);
        } finally {
            window = null;
        }
    }

    @Override
    public void collect(BlockState state, BlockAndTintGetter level, BlockPos pos,
            IntList output) {
        if (sources.containsKey(state.getBlock())) {
            extension.collectDynamicTintValues(state, level, pos, output);
            return;
        }
        List<BlockTintSource> statics =
                Minecraft.getInstance().getBlockColors().getTintSources(state);
        if (!statics.isEmpty()) {
            for (BlockTintSource source : statics) {
                output.add(source.colorInWorld(state, level, pos));
            }
            return;
        }
        IClientBlockExtensions.of(state).collectDynamicTintValues(state, level, pos, output);
    }

    /**
     * Checks every block first and changes nothing if one is refused: a block
     * new to this backend needs the client-extension event to still be open,
     * and NeoForge allows ONE client extension per block, so a block another
     * mod already extended cannot be delegated (NeoForge itself would throw a
     * duplicate-registration error from inside the event and stop the game).
     */
    @Override
    public void registerDelegating(BlockTints.AppearanceSource source,
            List<Block> blocks) {
        RegisterClientExtensionsEvent event = window;
        for (Block block : blocks) {
            if (sources.containsKey(block)) {
                continue;
            }
            if (event == null) {
                throw new IllegalStateException("BlockTints.registerDelegating for "
                        + block + " after NeoForge's RegisterClientExtensionsEvent; "
                        + "register from Deds.initClient");
            }
            if (event.isBlockRegistered(block)) {
                throw new IllegalStateException("BlockTints.registerDelegating for "
                        + block + ": another mod already registered client extensions "
                        + "for this block, and NeoForge allows one per block, so Ded's "
                        + "API cannot delegate its tint");
            }
        }
        for (Block block : blocks) {
            if (sources.put(block, source) == null) {
                event.registerBlock(extension, block);
            }
        }
    }
}
