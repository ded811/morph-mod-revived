package com.deds.morph.gametest.neoforge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * The NeoForge harness tests' calls whose shape differs between Minecraft
 * versions. This is the canonical (26.2) version;
 * {@code versions/mc26.3/neoforge/src/gametest/java/...} carries the same class
 * written against 26.3, where {@code getStructureManager} was renamed
 * {@code getStructureTemplateManager}.
 */
final class HarnessCompat {

    private HarnessCompat() {
    }

    /** The server's structure template manager. */
    static StructureTemplateManager structures(ServerLevel level) {
        return level.getStructureManager();
    }
}
