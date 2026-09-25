package com.deds.morph.gametest;

import com.mojang.datafixers.util.Either;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;

/**
 * The gametest calls whose shape differs between Minecraft versions. This is
 * the canonical (26.2) version; {@code versions/mc26.3/} carries the same
 * class written against 26.3 (see {@code versions/README.md}).
 */
final class GameTestCompat {

    private GameTestCompat() {
    }

    /** The real bed path, as a player clicking the bed's head half reaches it. */
    static Either<Player.BedSleepingProblem, Unit> sleepInBed(ServerPlayer player,
            ServerLevel level, BlockPos head) {
        return player.startSleepInBed(head);
    }
}
