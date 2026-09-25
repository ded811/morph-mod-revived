package com.deds.api.registry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Registers block entity types for one mod (namespace fixed to the owning
 * mod's id).
 *
 * <p>Boundary note: block entity <em>classes</em> unavoidably subclass
 * vanilla {@link BlockEntity} and need their {@link BlockEntityType} for the
 * super constructor — both are accepted vanilla surface, like {@link Block}
 * subclasses (see docs/ARCHITECTURE.md, "Level 1 pragmatism"). What goes
 * through the API is the churn-prone part: how a type is <em>built and
 * registered</em> (builder classes, datafixer arguments and registration
 * mechanics have all changed across versions and differ per loader).</p>
 */
public interface BlockEntityRegistrar {

    /**
     * Creates a block entity instance. Mirrors the vanilla factory shape so
     * a constructor reference like {@code CamoBlockEntity::new} works, while
     * keeping the vanilla builder/supplier types (which have churned) out of
     * mod code.
     *
     * @param <T> the block entity class
     */
    @FunctionalInterface
    interface Factory<T extends BlockEntity> {

        /** Creates the block entity for the given position and state. */
        T create(BlockPos pos, BlockState state);
    }

    /**
     * Registers a block entity type.
     *
     * <p>Takes a {@link List} rather than varargs: generic varargs would
     * force an unchecked-array warning on every call site (critique A1).</p>
     *
     * @param name    type name within the mod's namespace
     * @param factory creates instances (usually a constructor reference)
     * @param blocks  every block allowed to host this block entity — must be
     *                non-empty (vanilla validates the set when the entity is
     *                constructed, so it must also be complete)
     * @param <T>     the block entity class
     * @return handle to the registered type (needed by the block entity's
     *         super constructor)
     * @throws IllegalArgumentException if {@code blocks} is empty
     */
    <T extends BlockEntity> RegistryHandle<BlockEntityType<T>> register(
            String name, Factory<T> factory,
            List<RegistryHandle<? extends Block>> blocks);
}
