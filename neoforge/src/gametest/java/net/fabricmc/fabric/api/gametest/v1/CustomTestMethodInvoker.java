package net.fabricmc.fabric.api.gametest.v1;

import net.minecraft.gametest.framework.GameTestHelper;

import java.lang.reflect.Method;

/**
 * A stand-in, written for this project, for Fabric API's interface of the
 * same name, so the shared server gametests compile unchanged in the NeoForge
 * test mod (see {@link GameTest}). Same single method: a test class that
 * implements it runs its own test methods, and the registrar
 * ({@code MorphNeoForgeGameTests}) then skips its signature checks, exactly as
 * Fabric's annotation locator does. Never shipped.
 */
public interface CustomTestMethodInvoker {

    /** Runs {@code method} (a {@link GameTest} method of this object) with {@code helper}. */
    void invokeTestMethod(GameTestHelper helper, Method method)
            throws ReflectiveOperationException;
}
