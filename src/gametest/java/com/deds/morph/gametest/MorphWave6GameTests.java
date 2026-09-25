package com.deds.morph.gametest;

import com.deds.api.id.BId;
import com.deds.morph.Morph;
import com.deds.morph.MorphAbilities;
import com.deds.morph.MorphAbility;
import com.deds.morph.MorphState;
import com.deds.morph.MorphVariant;

import com.mojang.authlib.GameProfile;

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server gametests for Morph wave 6 — polish round (feature id
 * {@code morph/ability/swim-drag}; the B/C/D items are client-render
 * <b>[manual]</b>). Source of truth: {@code docs/specs/morph/wave6/polish-round.md}.
 * Mod id {@code deds_morph}. Recreation of iChun's Morph; all credit to iChun.
 */
public final class MorphWave6GameTests implements CustomTestMethodInvoker {

    /** A SURVIVAL mock server player (creative mocks break several paths). */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "test-morph-player");
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, level, profile,
                ClientInformation.createDefault()) {
            @Override
            public GameType gameMode() {
                return GameType.SURVIVAL;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(new ChannelHandler[] {connection});
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        helper.runBeforeTestEnd(() -> server.getPlayerList().remove(player));
        return player;
    }

    /** Builds a stone basin holding a 3x1 column of water (x2..4, y2..3, z=2)
     *  so a mock placed at (3.5, 2, 2.5) is genuinely eye-in-water. */
    private static void waterBasin(GameTestHelper helper) {
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 3; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                for (int y = 2; y <= 3; y++) {
                    boolean wall = x == 1 || x == 5 || z == 1 || z == 3;
                    helper.setBlock(new BlockPos(x, y, z),
                            wall ? Blocks.STONE : Blocks.WATER);
                }
            }
        }
    }

    /**
     * Puts the mock into the basin's water column with live health. A mock over an
     * {@code EmbeddedChannel} never runs {@code baseTick}, so its fluid state would
     * stay stale forever — refresh it explicitly through the protected
     * {@code Entity.updateFluidInteraction()} (the 26.2 method {@code baseTick}
     * uses to fill {@code wasTouchingWater}/{@code wasEyeInWater}; javap-verified).
     */
    private static void placeInWater(GameTestHelper helper, ServerPlayer player) {
        Vec3 pos = helper.absoluteVec(new Vec3(3.5, 2.0, 2.5));
        player.snapTo(pos.x, pos.y, pos.z, 0.0f, 0.0f);
        player.setHealth(20.0f);
        try {
            java.lang.reflect.Method m = net.minecraft.world.entity.Entity.class
                    .getDeclaredMethod("updateFluidInteraction");
            m.setAccessible(true);
            m.invoke(player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "reflective updateFluidInteraction failed", e);
        }
    }

    /**
     * Invokes the protected {@code LivingEntity.travelInWater(Vec3,double,boolean,
     * double)} — the method the {@code LivingEntityWaterDragMixin} redirect lives
     * in — with a zero travel vector, zero gravity and the player's own Y (the
     * javap-verified caller shape: travelVector, effectiveGravity, isFalling,
     * yBefore). With no input acceleration and no gravity, the only change to the
     * horizontal velocity is the END-OF-METHOD drag multiply
     * {@code dm.multiply(f7, 0.8, f7)} — so the resulting x/startX ratio IS f7.
     */
    private static void invokeTravelInWater(ServerPlayer player) {
        try {
            java.lang.reflect.Method m = net.minecraft.world.entity.LivingEntity.class
                    .getDeclaredMethod("travelInWater", Vec3.class, double.class,
                            boolean.class, double.class);
            m.setAccessible(true);
            m.invoke(player, Vec3.ZERO, 0.0d, false, player.getY());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "reflective travelInWater invocation failed", e);
        }
    }

    /**
     * {@code morph/ability/swim-drag} (wave 6 item A, de-vacuized per round-2
     * review R2+R3). The swim boost is the {@code LivingEntityWaterDragMixin}
     * OR-gate on vanilla's single dolphin's-grace drag check — NOT a hidden potion
     * effect (which leaked into the inventory panel: {@code EffectsInInventory
     * .extractEffects} has no {@code showIcon} filter).
     *
     * <p>Non-vacuous by construction: (a) the no-effect assertion runs with the
     * mock genuinely IN WATER — the deleted {@code applyHidden} lived inside
     * {@code swimTick}'s {@code isInWater()} branch, so a dry-land assert never
     * exercised it; (b) the DRAG assertion invokes the real (mixin-transformed)
     * {@code travelInWater} reflectively on a SWIM morph vs an unmorphed control
     * and asserts the 0.96-vs-0.8 horizontal decay — deleting the mixin fails it.</p>
     */
    @GameTest(maxTicks = 300)
    public void swimMorphDragWithoutEffect(GameTestHelper helper) {
        waterBasin(helper);
        ServerPlayer player = mockPlayer(helper);
        ServerPlayer control = mockPlayer(helper);
        MorphVariant cod = MorphVariant.ofType(BId.of("minecraft", "cod"));
        Morph.STATE.set(player, new MorphState(Optional.of(cod), List.of(cod)));

        helper.startSequence()
                .thenExecuteAfter(5, () -> {
                    placeInWater(helper, player);
                    placeInWater(helper, control);
                })
                .thenExecuteAfter(20, () -> {
                    helper.assertTrue(MorphAbilities.activeAbilities(player)
                                    .contains(MorphAbility.SWIM),
                            "precondition: a cod morph must grant the SWIM ability");
                    helper.assertTrue(player.isInWater(),
                            "precondition: the mock must be IN WATER (the deleted "
                                    + "applyHidden path only ran in-water)");
                    // (a) IN WATER, SWIM committed → still no effect instance.
                    helper.assertTrue(
                            player.getEffect(MobEffects.DOLPHINS_GRACE) == null,
                            "an in-water SWIM morph must carry NO dolphin's-grace "
                                    + "effect instance (the boost is the drag mixin)");

                    // (b) THE DRAG: morphed decays at 0.96, control at 0.8.
                    Vec3 start = new Vec3(0.4, 0.0, 0.0);
                    player.setDeltaMovement(start);
                    control.setDeltaMovement(start);
                    invokeTravelInWater(player);
                    invokeTravelInWater(control);
                    double morphedFactor = player.getDeltaMovement().x / start.x;
                    double controlFactor = control.getDeltaMovement().x / start.x;
                    helper.assertTrue(Math.abs(morphedFactor - 0.96) < 0.005,
                            "a SWIM morph's water drag must be the dolphin's-grace "
                                    + "0.96 but the decay factor was " + morphedFactor);
                    helper.assertTrue(Math.abs(controlFactor - 0.8) < 0.005,
                            "an unmorphed player's water drag must stay the vanilla "
                                    + "0.8 but the decay factor was " + controlFactor);

                    // External-effect survival across demorph.
                    player.addEffect(new MobEffectInstance(
                            MobEffects.DOLPHINS_GRACE, 1200));
                    helper.assertTrue(Morph.demorph(player),
                            "demorph must succeed for the morphed player");
                })
                .thenExecuteAfter(Morph.TRANSITION_TICKS + 30, () -> {
                    helper.assertTrue(MorphAbilities.activeAbilities(player)
                                    .isEmpty(),
                            "after demorph the ability set must be empty");
                    helper.assertTrue(
                            player.getEffect(MobEffects.DOLPHINS_GRACE) != null,
                            "an EXTERNAL dolphin's-grace effect must SURVIVE the "
                                    + "demorph (kill(SWIM) must not eat it)");
                })
                .thenSucceed();
    }

    @Override
    public void invokeTestMethod(GameTestHelper context, Method method)
            throws ReflectiveOperationException {
        method.invoke(this, context);
    }
}
