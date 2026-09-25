package com.deds.api.event;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Player↔<i>block</i> interaction events (Ded's API v1.5), bridged from the
 * loader by the platform. Sibling of {@link InteractionEvents} (which covers
 * player↔entity), so mod code never imports {@code net.fabricmc.*}.
 */
public final class PlayerEvents {

    /**
     * Payload: a player started LEFT-clicking a block (the "attack block"
     * action). Fires on BOTH sides — client when the local player begins
     * destroying, server in the block-break-action handler.
     *
     * <p>Unlike vanilla's {@code Block.attack(BlockState, Level, BlockPos,
     * Player)} this carries the clicked {@link #face()}, and a listener can
     * CANCEL the attack (see {@link #ATTACK_BLOCK}). Both are required by
     * tool-driven editing blocks: Carpenter's Blocks' hammer/chisel need the
     * face to know which cover slot the player aimed at, and need the cancel
     * so a creative player holding a tool edits the block instead of
     * instantly destroying it.</p>
     */
    public record AttackBlock(Player player, Level level, InteractionHand hand,
            BlockPos pos, Direction face) {
    }

    /**
     * Fired when a player left-clicks (attacks) a block. Register with
     * {@link Event#registerReturning}; the platform bridge fires it via
     * {@link Event#invokeUntil} with a {@link InteractionResult#PASS}
     * sentinel, so the first non-{@code PASS} result CANCELS the vanilla
     * attack (no destroy progress, no block break) on that side.
     *
     * <p>Listeners run on both sides — guard {@code level.isClientSide()}
     * before mutating world state, and return the same decision on both sides
     * or the client will mispredict.</p>
     *
     * <p><b>Fires BEFORE the game-mode checks</b> (the loader bridge injects at
     * the head of the server's block-break handler, ahead of vanilla's
     * spectator/adventure test), so a listener that mutates the world MUST
     * check {@code player.isSpectator()} / {@code player.mayBuild()} itself —
     * otherwise a crafted packet lets a spectator edit blocks. Same obligation
     * as {@link InteractionEvents#USE_ENTITY}.</p>
     *
     * <p><b>Fires once per TICK while the button is held</b>, not once per
     * press. Cancelling the attack means vanilla never records that it started
     * destroying, so its own "same target" de-duplication never engages and
     * the client re-sends the start-destroy action every tick. A listener that
     * performs a discrete edit needs its own per-press guard.</p>
     */
    public static final Event<AttackBlock> ATTACK_BLOCK = new Event<>();

    /**
     * Payload: a player right-clicked a block. {@code hit} carries the clicked
     * position, face and hit vector.
     */
    public record UseBlock(Player player, Level level, InteractionHand hand,
            BlockHitResult hit) {
    }

    /**
     * Fired when a player right-clicks a block, BEFORE vanilla decides whether
     * to consult the block at all. Register with
     * {@link Event#registerReturning}; the first non-{@link
     * InteractionResult#PASS} result becomes the interaction's result and
     * vanilla processing stops.
     *
     * <p><b>Why this exists as well as {@code Block.useItemOn}:</b> vanilla
     * SKIPS {@code BlockState.useItemOn} entirely when the player is sneaking
     * with an item in either hand (server: {@code ServerPlayerGameMode
     * .useItemOn}; client: {@code MultiPlayerGameMode.performUseItemOn}), so a
     * block that wants a sneak + item right-click verb — a tool that re-shapes
     * the block rather than placing something — cannot see it from its own
     * {@code useItemOn} override. This event fires ahead of that gate.</p>
     *
     * <p>It therefore fires for EVERY right-click, sneaking or not. A listener
     * that also overrides {@code Block.useItemOn} must handle only the case
     * vanilla skips (sneaking with a non-empty hand) or it will run twice.</p>
     *
     * <p>Same two obligations as {@link #ATTACK_BLOCK}: fires on both sides
     * (guard {@code level.isClientSide()} and answer identically), and fires
     * before the spectator check.</p>
     */
    public static final Event<UseBlock> USE_BLOCK = new Event<>();

    private PlayerEvents() {
    }
}
