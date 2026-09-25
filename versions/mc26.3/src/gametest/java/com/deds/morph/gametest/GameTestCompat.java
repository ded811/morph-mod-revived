package com.deds.morph.gametest;

import com.mojang.datafixers.util.Either;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The 26.3 version of {@code src/gametest/java/.../GameTestCompat.java}.
 *
 * <p>26.3's {@code startSleepInBed} takes the bed block, its state and the
 * level's {@code BedRule} as well as the position. The arguments are built
 * the way vanilla's {@code AbstractBedBlock.useWithoutItem} builds them:
 * {@code getBedRule(level, pos)} then
 * {@code player.startSleepInBed(this, state, bedRule, pos)}, with the HEAD
 * half's state and position.</p>
 */
final class GameTestCompat {

    private GameTestCompat() {
    }

    static Either<Player.BedSleepingProblem, Unit> sleepInBed(ServerPlayer player,
            ServerLevel level, BlockPos head) {
        BlockState state = level.getBlockState(head);
        AbstractBedBlock bed = (AbstractBedBlock) state.getBlock();
        return player.startSleepInBed(bed, state, bed.getBedRule(level, head), head);
    }
}
