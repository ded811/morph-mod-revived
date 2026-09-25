package com.deds.morph.gametest.neoforge.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * The NeoForge test mod's CLIENT entry class: starts the client test driver
 * ({@link ClientTestDriver}) when the game was launched with
 * {@code -Dmorph.clientTest=true}, which only the {@code clientGameTest}
 * run sets. Without it nothing is registered.
 *
 * <p>A separate {@code dist = CLIENT} class for the same mod id as the server
 * gametest registrar ({@code MorphNeoForgeGameTests}), which never names it:
 * the dedicated-server gametest run loads the same test mod, and NeoForge's
 * dev launcher masks client classes there, so a reference from the common
 * class would break the server run. FML constructs this class after the
 * common one.</p>
 */
@Mod(value = "deds_morph_test", dist = Dist.CLIENT)
public final class MorphNeoForgeClientTests {

    public MorphNeoForgeClientTests(IEventBus modBus) {
        if (!ClientTestInput.ENABLED) {
            return;
        }
        new ClientTestDriver().register(modBus);
    }
}
