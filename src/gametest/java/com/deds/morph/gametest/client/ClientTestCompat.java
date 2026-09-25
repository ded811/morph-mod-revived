package com.deds.morph.gametest.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The client-gametest calls whose shape differs between Minecraft / Fabric
 * API versions. This is the canonical (26.2, Fabric API 0.155) version;
 * {@code versions/mc26.3/} carries the same class for 26.3.
 */
final class ClientTestCompat {

    private ClientTestCompat() {
    }

    static void waitForChunksRender(TestSingleplayerContext world) {
        world.getClientLevel().waitForChunksRender();
    }
}
