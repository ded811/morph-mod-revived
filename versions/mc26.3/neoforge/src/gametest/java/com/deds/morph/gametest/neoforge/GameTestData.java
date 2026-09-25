package com.deds.morph.gametest.neoforge;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * Builds a test's {@link TestData} from its annotation: the Minecraft 26.3
 * twin of the canonical builder (see that one). 26.3's record has a dimension
 * after the environment, filled from the annotation's {@code dimension}
 * exactly as fabric-gametest-api-v1 4.0.32 fills it.
 */
final class GameTestData {

    private GameTestData() {
    }

    static TestData<Holder<TestEnvironmentDefinition<?>>> of(GameTest test,
            Holder<TestEnvironmentDefinition<?>> environment) {
        return new TestData<>(
                environment,
                ResourceKey.create(Registries.DIMENSION, Identifier.parse(test.dimension())),
                Identifier.parse(test.structure()),
                test.maxTicks(),
                test.setupTicks(),
                test.required(),
                test.rotation(),
                test.manualOnly(),
                test.maxAttempts(),
                test.requiredSuccesses(),
                test.skyAccess(),
                test.padding());
    }
}
