package com.deds.morph.gametest.neoforge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * The NeoForge harness tests' calls whose shape differs between Minecraft
 * versions. This is the Minecraft 26.3 twin of the
 * canonical class: 26.3 renamed {@code getStructureManager} to
 * {@code getStructureTemplateManager}.
 */
final class HarnessCompat {

    private HarnessCompat() {
    }

    /** The server's structure template manager. */
    static StructureTemplateManager structures(ServerLevel level) {
        return level.getStructureTemplateManager();
    }
}
