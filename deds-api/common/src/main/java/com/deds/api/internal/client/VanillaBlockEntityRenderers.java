package com.deds.api.internal.client;

import com.deds.api.client.BlockEntityRenderers;

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * The {@link BlockEntityRenderers} backend both loaders install: vanilla's own
 * writer, {@code net.minecraft.client.renderer.blockentity
 * .BlockEntityRenderers.register}.
 *
 * <p>That method is {@code private} in a stock 26.2 jar (javap). Each loader
 * opens it: Fabric through {@code fabric-transitive-access-wideners-v1},
 * NeoForge through its access transformer, and NeoForge's own
 * {@code EntityRenderersEvent.RegisterRenderers.registerBlockEntityRenderer}
 * is literally a call to it. Fabric's {@code BlockEntityRendererRegistry} is
 * {@code @Deprecated} in the Fabric API this project builds against, in favour
 * of the same vanilla method.</p>
 *
 * <p>Vanilla's writer is a plain {@code PROVIDERS.put} into a static map that
 * is not read until {@code BlockEntityRenderDispatcher.onResourceManagerReload}
 * (javap), i.e. at the first resource reload, after every client entrypoint
 * has run; so registering from client init is in time. A later registration
 * still takes effect at the next reload. Not API: mod code may never import
 * {@code com.deds.api.internal}.</p>
 */
public final class VanillaBlockEntityRenderers
        implements BlockEntityRenderers.Backend {

    @Override
    public <T extends BlockEntity, S extends BlockEntityRenderState> void
            register(BlockEntityType<T> type,
                    BlockEntityRendererProvider<? super T, S> renderer) {
        net.minecraft.client.renderer.blockentity.BlockEntityRenderers
                .register(type, renderer);
    }
}
