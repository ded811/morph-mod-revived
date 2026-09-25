package com.deds.api.registry;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Function;

/**
 * Registers blocks for one mod (namespace fixed to the owning mod's id).
 *
 * <p>Boundary note: plain blocks need no vanilla types at all. Custom block
 * classes unavoidably subclass vanilla {@link Block}, and their constructor
 * receives the vanilla properties object — that single constructor line is
 * the accepted vanilla surface in mod code (see docs/ARCHITECTURE.md,
 * "Level 1 pragmatism").</p>
 */
public interface BlockRegistrar {

    /** Registers a plain {@link Block} with the given settings. */
    RegistryHandle<Block> register(String name, BlockSettings settings);

    /**
     * Registers a custom block. The factory receives fully-prepared vanilla
     * properties (id wiring included — the platform handles the modern
     * "properties must know their registry key" requirement).
     */
    RegistryHandle<Block> register(String name, BlockSettings settings,
            Function<BlockBehaviour.Properties, ? extends Block> factory);
}
