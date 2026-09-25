package com.deds.morph.fabric.mixin;

import com.deds.morph.MorphSleep;

import com.mojang.datafixers.util.Either;

import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The {@code canSleepMorphed} gate (wave 10).
 *
 * <p><b>Original.</b> {@code O:morph/common/core/EventHandler.java:651-668} —
 * a Forge {@code PlayerSleepInBedEvent} listener that, when
 * {@code SessionState.canSleepMorphed} is false and the player has a
 * {@code MorphInfo}, sets {@code event.result = EnumStatus.OTHER_PROBLEM} and
 * (server side) chats {@code "You may not rest now, you are in morph"}.</p>
 *
 * <p><b>26.2 shape.</b> {@code Player.startSleepInBed(BlockPos)} returns
 * {@code Either<BedSleepingProblem, Unit>} and {@code ServerPlayer}'s override
 * runs its own gates before delegating here with {@code super}, so a HEAD
 * inject on {@code Player.startSleepInBed} is the whole feature and needs no
 * client half. {@code BedSleepingProblem} is a RECORD carrying its own
 * {@code Component message} (javap), and {@code BedBlock.useWithoutItem}'s
 * {@code ifLeft} shows any non-null message with
 * {@code Player.sendOverlayMessage} — so the refusal string travels with the
 * refusal instead of needing a separate chat send. It lands on the ACTION BAR
 * rather than in chat, which is where every 26.2 bed refusal goes
 * (deviation D10-3, with the default flip).</p>
 *
 * <p>Our default is {@code canSleepMorphed = true} — see {@link MorphSleep}.</p>
 */
@Mixin(Player.class)
public abstract class PlayerSleepInBedMixin {

    // By NAME with no argument list, and a handler that takes only the
    // CallbackInfoReturnable: Player has exactly one startSleepInBed on every
    // version this mod builds for, but it takes (BlockPos) on 26.2 and
    // (AbstractBedBlock, BlockState, BedRule, BlockPos) on 26.3. The handler
    // reads only `this`, so it needs none of the arguments.
    @Inject(method = "startSleepInBed", at = @At("HEAD"), cancellable = true)
    private void deds_morph$refuseBedWhileMorphed(
            CallbackInfoReturnable<Either<Player.BedSleepingProblem, Unit>> cir) {
        Player player = (Player) (Object) this;
        if (MorphSleep.refuses(player)) {
            cir.setReturnValue(Either.left(MorphSleep.PROBLEM));
        }
    }
}
