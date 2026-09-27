package com.deds.api.neoforge.client;

import com.deds.api.client.FluidRenderers;
import com.deds.api.id.BId;

import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent;
import net.neoforged.neoforge.client.fluid.FluidTintSources;

import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link FluidRenderers} on NeoForge. Fabric registers a fluid's model with
 * {@code FluidRenderingRegistry} and bakes it on every resource reload; NeoForge
 * asks for fluid models through {@link RegisterFluidModelsEvent}, posted on
 * every reload from a worker thread. So registrations are kept here (last one
 * wins for a fluid, as on Fabric) and handed to each reload's event.
 *
 * <p>The model is the Fabric backend's: the still and flowing sprites, the
 * flowing sprite again as the overlay drawn beside glass and leaves, and a
 * constant ARGB tint. A pair registered together is baked once for both
 * halves. NeoForge refuses a second model for one fluid in the same reload,
 * which only matters for a fluid that already has one (vanilla water and
 * lava): registering one for those makes the event throw, so EVERY resource
 * reload fails, the first one included. Fabric would let a mod replace
 * them. Register only your own fluids.</p>
 */
final class NeoForgeFluidRenderers implements FluidRenderers.Backend {

    private final Map<Fluid, FluidModel.Unbaked> models = new ConcurrentHashMap<>();

    @Override
    public void register(Fluid still, Fluid flowing, BId stillTexture,
            BId flowingTexture, int tintArgb) {
        Material stillMaterial = new Material(Identifier.fromNamespaceAndPath(
                stillTexture.namespace(), stillTexture.path()));
        Material flowingMaterial = new Material(Identifier.fromNamespaceAndPath(
                flowingTexture.namespace(), flowingTexture.path()));
        FluidModel.Unbaked model = new FluidModel.Unbaked(stillMaterial,
                flowingMaterial, flowingMaterial, FluidTintSources.constant(tintArgb));
        models.put(still, model);
        models.put(flowing, model);
    }

    /** Every reload, worker thread: one registration per distinct model. */
    void onRegisterFluidModels(RegisterFluidModelsEvent event) {
        Map<FluidModel.Unbaked, List<Fluid>> byModel = new IdentityHashMap<>();
        models.forEach((fluid, model) ->
                byModel.computeIfAbsent(model, m -> new ArrayList<>()).add(fluid));
        byModel.forEach((model, fluids) -> {
            if (fluids.size() == 2) {
                event.register(model, fluids.get(0), fluids.get(1));
            } else {
                for (Fluid fluid : fluids) {
                    event.register(model, fluid);
                }
            }
        });
    }
}
