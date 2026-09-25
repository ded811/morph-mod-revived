package com.deds.morph.gametest.neoforge;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;

/**
 * Builds a test's {@link TestData} from its annotation, every field passed
 * explicitly, in the shape Fabric's annotation locator builds it: vanilla's
 * own shorter constructors would default padding to 0 where Fabric's
 * annotation says 1, among others.
 *
 * <p>The one part of the registrar whose shape differs between Minecraft
 * versions, hence a class of its own: this is the canonical (26.2) record,
 * with no dimension; {@code versions/mc26.3/neoforge/src/gametest/java/...}
 * carries the 26.3 twin, whose record gained a dimension after the
 * environment.</p>
 */
final class GameTestData {

    private GameTestData() {
    }

    static TestData<Holder<TestEnvironmentDefinition<?>>> of(GameTest test,
            Holder<TestEnvironmentDefinition<?>> environment) {
        return new TestData<>(
                environment,
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
