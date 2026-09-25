package com.deds.morph.gametest.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The 26.3 version of {@code src/gametest/java/.../client/ClientTestCompat.java}.
 * Fabric's client gametest module moved from 5.x (Fabric API 0.155.2+26.2)
 * to 6.x (0.161.0+26.3): {@code TestClientLevelContext} is gone and the
 * chunk waits live on the singleplayer context's {@code getConnection()}.
 */
final class ClientTestCompat {

    private ClientTestCompat() {
    }

    static void waitForChunksRender(TestSingleplayerContext world) {
        world.getConnection().waitForChunksRender();
    }
}
