package com.deds.morph.gametest.client;

import com.deds.api.id.BId;

import com.deds.morph.Morph;
import com.deds.morph.api.Ability;
import com.deds.morph.api.AbilityRegistry;
import com.deds.morph.fabric.client.MorphDummies;
import com.deds.morph.fabric.client.MorphSelector;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * <b>Layer 3 — Morph's render harness</b> (docs/TESTING.md; the shape of
 * TrailMix's {@code LauncherRenderClientTest} and Carpenter's
 * {@code CarpentersRenderClientTest}). Stood up for the wave-8
 * sleeping-in-a-bed fix, whose entire symptom — "you dont lay down at all you
 * just stand there on the pillow part" — is invisible to a headless build.
 *
 * <p><b>The SCREENSHOTS are not coverage; the two assertions in
 * {@link #assertSleepRenderState} are.</b> They read the only two fields
 * vanilla's lie-down branch consults ({@code LivingEntityRenderState.pose} and
 * {@code .bedOrientation} — javap of {@code LivingEntityRenderer.submit} +
 * {@code setupRotations}, 26.2) straight off the render state the morph
 * extraction really produces, for a humanoid morph AND for a non-humanoid one
 * (which must stay upright — the user's explicit call). A screenshot alone
 * would prove nothing and cannot go red.</p>
 *
 * <p>Everything is driven server-side through {@code MinecraftServer.execute}
 * rather than through commands, so nothing depends on cheats being enabled or
 * on the console command source having a player. Sleeping is started with the
 * low-level {@code LivingEntity.startSleeping(BlockPos)}: it sets the same
 * synced pose + sleeping position a real bed click does — which is all the
 * RENDER reads — while skipping the time-of-day/monster checks and, because the
 * client's own {@code LocalPlayer.startSleeping} never runs, the in-bed screen
 * and the sleep fade-to-black never appear over the screenshots.</p>
 *
 * <p>Screenshots land in {@code build/run/clientGameTest/screenshots} — a
 * build directory, wiped by the next run: copy them out in the same session.</p>
 *
 * <p>Recreation of iChun's Morph; all credit for the original to iChun.</p>
 */
public final class MorphRenderClientTest implements FabricClientGameTest {

    // Deliberately LARGER than the dev client's real window (854x480).
    // TestScreenshotOptions.withSize re-renders the WORLD at this size but
    // leaves every SCREEN/overlay laid out for the real window
    // (docs/TESTING.md), and while the player sleeps two of those are
    // unavoidable — Gui.tick RE-OPENS InBedChatScreen on any tick where the
    // screen is null and the player is sleeping (javap, offsets 77-112), and
    // the sleep fade darkens with sleepCounter. Shooting bigger parks both in
    // the top-left ~44% of the frame and leaves the subject in the clear.
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    /** Flat-world ground surface (docs/TESTING.md). */
    private static final int GROUND = -60;

    /** East-facing bed: foot at x=2, head at x=3. */
    private static final BlockPos EAST_BED = new BlockPos(2, GROUND, 0);
    private static final BlockPos EAST_BED_HEAD = new BlockPos(3, GROUND, 0);
    /** South-facing bed: foot at z=0, head at z=1 (the second camera angle). */
    private static final BlockPos SOUTH_BED = new BlockPos(8, GROUND, 0);
    private static final BlockPos SOUTH_BED_HEAD = new BlockPos(8, GROUND, 1);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(settings -> settings.setGameMode(
                        WorldCreationUiState.SelectedGameMode.CREATIVE))
                .create()) {
            ClientTestCompat.waitForChunksRender(singleplayer);
            context.runOnClient(client -> {
                client.options.fov().set(70);
                client.options.bobView().set(false);
                client.options.chatVisibility().set(ChatVisiblity.HIDDEN);
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            });
            // NIGHT, pinned. Player.tick force-wakes any sleeper whose
            // position fails the BED_RULE environment attribute — WHEN_DARK
            // in the overworld, i.e. Level.isDarkOutside() (javap; there is
            // no Level.isDay() in 26.2, which is what this comment used to
            // claim). A noon harness cannot hold a sleep for even one tick —
            // that is exactly how the first run of this harness failed, and
            // the layer-2 sleep test hit the same wall on 2026-07-29 because
            // it pinned nothing at all. Night vision replaces the light we
            // lose so the screenshots stay legible.
            // NB 26.2 renamed EVERY gamerule to snake_case: doDaylightCycle is
            // `advance_time` and doMobGriefing is `mob_griefing`. The camelCase
            // spellings are rejected — and `runCommand` only LOGS that and
            // carries on (docs/TESTING.md), so the first version of this
            // harness silently ran with neither rule set.
            run(singleplayer, "gamerule advance_time false");
            run(singleplayer, "time set midnight");
            run(singleplayer, "weather clear");
            // A single sleeping player in a singleplayer world would skip the
            // night and be woken mid-shoot; 101 % can never be reached.
            run(singleplayer, "gamerule players_sleeping_percentage 101");
            // startSleepInBed refuses with "there are monsters nearby"
            run(singleplayer, "gamerule spawn_monsters false");
            run(singleplayer, "kill @e[type=!player]");
            run(singleplayer,
                    "effect give @a minecraft:night_vision 99999 0 true");
            // Two beds, two orientations. A SLEEPING player's view is pinned
            // to the bed direction by vanilla, so the only way to photograph
            // the lying body from a second angle is to lie in a bed that faces
            // a different way.
            run(singleplayer, "setblock 2 " + GROUND + " 0 "
                    + "minecraft:red_bed[facing=east,part=foot]");
            run(singleplayer, "setblock 3 " + GROUND + " 0 "
                    + "minecraft:red_bed[facing=east,part=head]");
            run(singleplayer, "setblock 8 " + GROUND + " 0 "
                    + "minecraft:blue_bed[facing=south,part=foot]");
            run(singleplayer, "setblock 8 " + GROUND + " 1 "
                    + "minecraft:blue_bed[facing=south,part=head]");
            context.waitTicks(10);

            // ---------------------------------------------------------------
            // 1. HUMANOID morph (zombie) - must lie down
            // ---------------------------------------------------------------
            morphInto(context, EntityTypes.ZOMBIE);
            context.waitTicks(Morph.TRANSITION_TICKS + 25);
            standBeside(singleplayer, context, EAST_BED);
            look(context, 90.0f, 15.0f);
            shot(context, "01_zombie_morph_awake_beside_bed");

            sleep(context, EAST_BED_HEAD);
            context.waitTicks(6);
            shot(context, "02_zombie_morph_asleep_east_bed");
            assertSleepRenderState(context, /*expectLyingDown=*/true, "zombie");
            // WAVE 10 — the sleeping JITTER. Six frames a tick apart first (a
            // vibration needs consecutive frames; one screenshot cannot show
            // it), then the numeric probe, then the assertion — in that order,
            // so a FAILING run still leaves its evidence on disk.
            jitterBurst(context, "02b_zombie_morph_asleep_jitter_frame");
            assertNoSleepJitter(context, "zombie");
            wake(context);

            standBeside(singleplayer, context, SOUTH_BED);
            sleep(context, SOUTH_BED_HEAD);
            context.waitTicks(6);
            shot(context, "03_zombie_morph_asleep_south_bed");
            assertSleepRenderState(context, true, "zombie (south bed)");
            wake(context);

            // ---------------------------------------------------------------
            // 2. VILLAGER morph - a hand-rolled (non-HumanoidModel) body, i.e.
            //    exactly the case MorphHumanoid's explicit family list exists
            //    for. Must lie down too.
            // ---------------------------------------------------------------
            morphInto(context, EntityTypes.VILLAGER);
            context.waitTicks(Morph.TRANSITION_TICKS + 25);
            standBeside(singleplayer, context, EAST_BED);
            sleep(context, EAST_BED_HEAD);
            context.waitTicks(6);
            shot(context, "04_villager_morph_asleep_east_bed");
            assertSleepRenderState(context, true, "villager");
            wake(context);

            // ---------------------------------------------------------------
            // 3. NON-humanoid control (chicken) - must STAY UPRIGHT in the bed
            //    ("it wouldn't make sense for a chicken to lay on its back")
            // ---------------------------------------------------------------
            morphInto(context, EntityTypes.CHICKEN);
            context.waitTicks(Morph.TRANSITION_TICKS + 25);
            standBeside(singleplayer, context, EAST_BED);
            sleep(context, EAST_BED_HEAD);
            context.waitTicks(6);
            shot(context, "05_chicken_morph_in_bed_stays_upright");
            assertSleepRenderState(context, /*expectLyingDown=*/false,
                    "chicken");
            wake(context);

            // ---------------------------------------------------------------
            // 4. Un-morphed reference from the SAME camera and fixture - the
            //    shot the morph shots are judged against.
            // ---------------------------------------------------------------
            demorph(context);
            context.waitTicks(Morph.TRANSITION_TICKS + 25);
            standBeside(singleplayer, context, EAST_BED);
            sleep(context, EAST_BED_HEAD);
            context.waitTicks(6);
            shot(context, "06_unmorphed_player_asleep_reference_east_bed");
            wake(context);

            // ---------------------------------------------------------------
            // 5. WAVE 9 item 6 — sortMorphs. The strip's ORDER is the only
            //    part of that item a human has to look at (the ordering
            //    FUNCTION is pinned headlessly by
            //    MorphWave9GameTests.sortMorphsHasFourModes). Sections 1-4
            //    have already acquired Zombie, Villager and Chicken; two more
            //    make acquisition order and alphabetical order maximally
            //    different:
            //      acquisition  = Zombie, Villager, Chicken, Cow, Axolotl
            //      alphabetical = Axolotl, Chicken, Cow, Villager, Zombie
            //    i.e. exactly reversed, so a wrong mode cannot photograph the
            //    same picture as a right one.
            // ---------------------------------------------------------------
            morphInto(context, EntityTypes.COW);
            context.waitTicks(Morph.TRANSITION_TICKS + 25);
            morphInto(context, EntityTypes.AXOLOTL);
            context.waitTicks(Morph.TRANSITION_TICKS + 25);
            // Wear the COW last so mode 3 has something to float that is
            // neither first nor last in acquisition order.
            wear(context, EntityTypes.COW);
            context.waitTicks(Morph.TRANSITION_TICKS + 25);
            // ...then DEMORPH, so the strip opens with the OWN FORM selected
            // (row 0) and all five acquired rows are visible BELOW it. Opening
            // on a worn morph centres that row and pushes the rows above it off
            // the top of an 854x480 window, which photographs two of five (run 1
            // of this section, observed). Mode 3's "recently used" key is set by
            // the last morph WORN, so demorphing does not clear it.
            demorph(context);
            context.waitTicks(Morph.TRANSITION_TICKS + 25);
            run(singleplayer, "tp @a 0 " + (GROUND + 1) + " 0 0 0");
            context.waitTicks(5);

            selectorShot(context, 0, "07_sort_mode0_order_of_acquisition");
            selectorShot(context, 1, "08_sort_mode1_alphabetical");
            selectorShot(context, 2, "09_sort_mode2_alphabetical_deep");
            selectorShot(context, 3, "10_sort_mode3_recently_used");
            setSortMorphs(context, 0); // leave the shipped default behind

            // ---------------------------------------------------------------
            // 6. WAVE 10a — allowMorphSelection. Client-only by construction
            //    (MorphSelector is @Environment(CLIENT) and cannot even be
            //    loaded in the dedicated-server gametest), so this is the only
            //    layer that can assert it. HARD assertions, no screenshot.
            // ---------------------------------------------------------------
            assertSelectionGate(context);

            // ---------------------------------------------------------------
            // 7. WAVE 10b — ability-icon OVERFLOW. Six built-in slots is
            //    enough for every vanilla morph (the wither, at 5, is the
            //    worst case), so overflow is only reachable through the wave-9
            //    third-party API — which is exactly how a player would hit it.
            //    Register eight extra abilities that apply to everything: the
            //    zombie row goes from 4 icons to 12, i.e. double the U.
            //
            //    Five frames, 15 ticks apart. The cadence is 30 ticks per
            //    icon-slot (the original's), so 15 ticks is HALF a slot: over
            //    the five shots every icon travels two full slots and the
            //    sequence in each slot must visibly advance.
            // ---------------------------------------------------------------
            registerOverflowAbilities(context);
            context.runOnClient(client -> {
                client.gui.setScreen(null);
                MorphSelector.prev();
            });
            context.waitTicks(15); // slide-in done
            for (int i = 0; i < 5; i++) {
                context.takeScreenshot(TestScreenshotOptions.of(
                        "11_icon_overflow_scroll_t" + (i * 15)));
                context.waitTicks(15);
            }
            context.runOnClient(client -> MorphSelector.cancelKey());
            context.waitTicks(3);

            // ---------------------------------------------------------------
            // 8. BABY morphs, on by default since 2026-09-24 (childMorphs
            //    true). The original shipped them off "due to improper morph
            //    transitions", so photograph a baby mid-change and after it:
            //    a quadruped (cow) and a humanoid (zombie). morphInto uses the
            //    shipped config, so it also fails if the default is off.
            // ---------------------------------------------------------------
            standBeside(singleplayer, context, EAST_BED);
            look(context, 90.0f, 15.0f);
            morphInto(context, EntityTypes.COW, true);
            context.waitTicks(Morph.TRANSITION_TICKS / 2);
            shot(context, "12_baby_cow_mid_change");
            context.waitTicks(Morph.TRANSITION_TICKS / 2 + 25);
            shot(context, "12b_baby_cow_after_change");
            morphInto(context, EntityTypes.ZOMBIE, true);
            context.waitTicks(Morph.TRANSITION_TICKS / 2);
            shot(context, "13_baby_zombie_mid_change");
            context.waitTicks(Morph.TRANSITION_TICKS / 2 + 25);
            shot(context, "13b_baby_zombie_after_change");

            // ---------------------------------------------------------------
            // 9. Clicks with the selector open act on the selector ONLY. A
            //    right-click used to close the strip AND use the held item.
            //    Survival, so a thrown snowball is really used up; the control
            //    click at the end proves the simulated clicks reach the game.
            // ---------------------------------------------------------------
            run(singleplayer, "gamemode survival @a");
            run(singleplayer, "clear @a");
            run(singleplayer, "give @a minecraft:snowball 16");
            context.waitTicks(5);
            context.runOnClient(client -> {
                client.gui.setScreen(null);
                MorphSelector.prev(); // opens the strip
            });
            context.waitTicks(15);
            context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
            context.waitTicks(5);
            context.runOnClient(client -> {
                if (MorphSelector.isOpen()) {
                    throw new AssertionError("a right-click must close the selector");
                }
                int left = client.player.getMainHandItem().getCount();
                if (left != 16) {
                    throw new AssertionError("the right-click that closed the selector "
                            + "must not also throw a snowball, but " + left + " are left");
                }
                MorphSelector.prev(); // open again for the left-click
            });
            context.waitTicks(15);
            context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
            context.waitTicks(5);
            context.runOnClient(client -> {
                if (MorphSelector.isOpen()) {
                    throw new AssertionError("a left-click must pick and close the selector");
                }
            });
            // Control: with the selector closed, the same right-click throws.
            context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
            context.waitTicks(5);
            context.runOnClient(client -> {
                int left = client.player.getMainHandItem().getCount();
                if (left != 15) {
                    throw new AssertionError("control: a right-click with the selector "
                            + "closed must throw one snowball (15 left), but " + left
                            + " are left - the simulated clicks are not reaching the game");
                }
            });
        }
    }

    /**
     * {@code allowMorphSelection = 0} must (a) refuse to OPEN the strip and
     * (b) force-close one that is already open — the original did the second
     * from its own client tick every tick
     * ({@code O:client/core/TickHandlerClient.java:954-958}) and we keep that
     * shape so a config reload while the strip is up self-heals.
     */
    private static void assertSelectionGate(ClientGameTestContext context) {
        setAllowSelection(context, false);
        context.runOnClient(client -> {
            client.gui.setScreen(null);
            MorphSelector.prev(); // the open key
        });
        context.waitTicks(5);
        context.runOnClient(client -> {
            if (MorphSelector.isOpen()) {
                throw new AssertionError("allowMorphSelection=false must keep "
                        + "the selector strip CLOSED, but the open key opened "
                        + "it");
            }
        });

        setAllowSelection(context, true);
        context.runOnClient(client -> MorphSelector.prev());
        context.waitTicks(5);
        context.runOnClient(client -> {
            if (!MorphSelector.isOpen()) {
                throw new AssertionError("allowMorphSelection=true must let "
                        + "the strip open (control case)");
            }
        });

        // ...and flipping it off UNDER an open strip closes it.
        setAllowSelection(context, false);
        context.waitTicks(5);
        context.runOnClient(client -> {
            if (MorphSelector.isOpen()) {
                throw new AssertionError("allowMorphSelection=false must "
                        + "force-close a strip that is already open (the "
                        + "original's per-tick close)");
            }
        });
        setAllowSelection(context, true); // leave the shipped default behind
        context.waitTicks(2);
    }

    private static void setAllowSelection(ClientGameTestContext context,
            boolean allowed) {
        context.runOnClient(client -> Morph.CONFIG.set(
                Morph.CONFIG.get().withAllowMorphSelection(allowed)));
        context.waitTicks(2);
    }

    /**
     * Eight third-party abilities that apply to every morph, so the six-slot U
     * overflows. They reuse the built-in icon textures (a missing texture would
     * photograph as the magenta placeholder and prove nothing about layout), and
     * their instances are the interface's no-op default, so nothing but the icon
     * pass is affected.
     */
    private static void registerOverflowAbilities(ClientGameTestContext context) {
        context.runOnClient(client -> {
            String[] icons = {"climb", "fly", "float", "fall_negate",
                "fire_immunity", "hostile", "poison_resistance", "step"};
            for (int i = 0; i < icons.length; i++) {
                BId id = BId.of("deds_morph_test", "overflow" + i);
                BId texture = BId.of(Morph.MOD_ID,
                        "textures/icon/" + icons[i] + ".png");
                AbilityRegistry.register(new Ability() {
                    @Override
                    public BId id() {
                        return id;
                    }

                    @Override
                    public boolean appliesTo(net.minecraft.world.entity
                            .LivingEntity dummy) {
                        return true;
                    }

                    @Override
                    public BId icon() {
                        return texture;
                    }
                });
            }
        });
        context.waitTicks(2);
    }

    /**
     * Opens the selector strip under one {@code sortMorphs} mode and photographs
     * it, then closes it again.
     *
     * <p>No {@code withSize}: the strip is a HUD element and
     * {@code TestScreenshotOptions.withSize} re-renders only the WORLD at that
     * size while leaving every overlay laid out for the real window
     * (docs/TESTING.md) — for a GUI subject the un-resized shot is the honest
     * one. {@code cancelKey()} is the strip's own Esc action, so closing goes
     * through exactly the path a player uses.</p>
     */
    private static void selectorShot(ClientGameTestContext context, int mode,
            String name) {
        setSortMorphs(context, mode);
        context.runOnClient(client -> {
            client.gui.setScreen(null);
            MorphSelector.prev(); // first press opens the strip
        });
        context.waitTicks(15);    // let the 10-tick slide-in finish
        context.takeScreenshot(TestScreenshotOptions.of(name));
        context.runOnClient(client -> MorphSelector.cancelKey());
        context.waitTicks(3);
    }

    private static void setSortMorphs(ClientGameTestContext context, int mode) {
        context.runOnClient(client ->
                Morph.CONFIG.set(Morph.CONFIG.get().withSortMorphs(mode)));
        context.waitTicks(2);
    }

    /** Wears an ALREADY-acquired morph of {@code type} (no new acquisition), so
     *  mode 3 has a "most recently used" column that is not the newest one. */
    private static void wear(ClientGameTestContext context,
            EntityType<? extends LivingEntity> type) {
        onServer(context, (server, player) -> {
            Morph.clearTransitionLock(player);
            Morph.STATE.get(player).acquired().stream()
                    .filter(v -> v.type().toString()
                            .equals(EntityType.getKey(type).toString()))
                    .findFirst()
                    .ifPresent(v -> Morph.select(player,
                            java.util.Optional.of(v)));
        });
    }

    /** Aims the player (and, in third person, the orbiting camera). MC yaw 0
     *  faces SOUTH (+z), 90 WEST (Playbook §7). Only meaningful while AWAKE:
     *  vanilla pins a sleeper's view to the bed direction, which is why the
     *  sleeping shots come from two differently-facing BEDS instead. */
    private static void look(ClientGameTestContext context, float yaw,
            float pitch) {
        context.runOnClient(client -> {
            client.player.setYRot(yaw);
            client.player.setXRot(pitch);
            client.player.yRotO = yaw;
            client.player.xRotO = pitch;
            client.player.setYHeadRot(yaw);
            client.player.yHeadRotO = yaw;
            client.player.yBodyRot = yaw;
            client.player.yBodyRotO = yaw;
        });
        context.waitTicks(3);
    }

    // ------------------------------------------------------------------
    // wave 10 — the sleeping JITTER (measurement first, assertion second)
    // ------------------------------------------------------------------

    /**
     * Six frames of the sleeping morph, one tick apart, at the play size.
     *
     * <p>A vibration is invisible in a single photograph by definition, and the
     * jitter under test is a 20 Hz sawtooth — the render lerps the morph from
     * {@code yOld} to {@code y} across a tick's partial ticks and snaps back at
     * the boundary. Frames a tick apart land at effectively random points on
     * that ramp, so the SET of them either moves or does not, and the pixel diff
     * between consecutive frames is the amplitude in screen space.</p>
     */
    private static void jitterBurst(ClientGameTestContext context, String stem) {
        for (int i = 0; i < 6; i++) {
            context.takeScreenshot(TestScreenshotOptions.of(stem + "_" + i)
                    .withSize(WIDTH, HEIGHT));
            context.waitTicks(1);
        }
    }

    /**
     * The numeric half: the morph dummy's per-tick render-interpolation SPAN
     * must be zero while the player is asleep and stationary.
     *
     * <p>{@code MorphDummies.poseSpan()} is {@code (x-xOld, y-yOld, z-zOld)} of
     * the dummy as the last posed frame left it — exactly the vector the render
     * pipeline lerps across a tick, i.e. the peak-to-peak world-space
     * displacement of the morph within one tick. For a player who is not moving
     * it must be {@code (0,0,0)}; the wave-8 sleeping code left {@code y}
     * pinned at the bed's nominal {@code bedY + 0.6875} (because
     * {@code setSleepingPos} re-fires
     * {@code LivingEntity.onSyncedDataUpdated -> setPosToBed} on a CLIENT level
     * every frame) while {@code yOld} tracked where the sleeper actually
     * settled, so the span was the gap between the two.</p>
     *
     * <p>Sampled over 20 ticks rather than once: the value is a per-frame
     * product, and a single sample could catch a lucky frame.</p>
     */
    private static void assertNoSleepJitter(ClientGameTestContext context,
            String what) {
        double[] worst = new double[] {0.0, 0.0, 0.0};
        double[] swept = new double[] {0.0};
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            context.runOnClient(client -> {
                if (client.player == null || !client.player.isSleeping()) {
                    throw new AssertionError("harness precondition: the local "
                            + "player must still be asleep for the " + what
                            + " jitter probe");
                }
                double[] span = MorphDummies.poseSpan();
                for (int a = 0; a < 3; a++) {
                    if (Math.abs(span[a]) > Math.abs(worst[a])) {
                        worst[a] = span[a];
                    }
                }
                trace.append(String.format("%.6f ", span[1]));
                // Second, INDEPENDENT measurement, through the real pipeline
                // rather than our own diagnostic field: extract the morph's
                // render state at partial tick 0 and at partial tick 1 and
                // compare the y the renderer would place it at. Their
                // difference IS the distance the morph sweeps between two tick
                // boundaries — the vibration, in world units.
                double y0 = client.getEntityRenderDispatcher()
                        .extractEntity(client.player, 0.0f).y;
                double y1 = client.getEntityRenderDispatcher()
                        .extractEntity(client.player, 1.0f).y;
                if (Math.abs(y1 - y0) > Math.abs(swept[0])) {
                    swept[0] = y1 - y0;
                }
            });
            context.waitTicks(1);
        }
        // Reported unconditionally: these lines ARE the before/after measurement.
        System.out.println("[deds_morph wave10] sleeping-jitter amplitude ("
                + what + "): dx=" + worst[0] + " dy=" + worst[1]
                + " dz=" + worst[2] + "  dy samples: " + trace);
        System.out.println("[deds_morph wave10] render-state y swept across one"
                + " tick (" + what + ", extractEntity pt=0 vs pt=1): "
                + swept[0] + " blocks");
        if (Math.abs(swept[0]) > 1.0e-6) {
            throw new AssertionError("a sleeping " + what + " morph's RENDER "
                    + "STATE must not move between partial tick 0 and 1 for a "
                    + "stationary sleeper, but extractEntity places it "
                    + swept[0] + " blocks apart — that is the vibration, "
                    + "measured through the real pipeline");
        }
        if (Math.abs(worst[1]) > 1.0e-6) {
            throw new AssertionError("a sleeping " + what + " morph must not "
                    + "JITTER: the dummy's per-tick render-interpolation span "
                    + "must be zero for a stationary sleeper, but y moves "
                    + worst[1] + " blocks every tick (peak to peak). That is "
                    + "the user-reported \"humanoids sleeping jitter around, "
                    + "like up and down really fast\". dy samples: " + trace);
        }
        if (Math.abs(worst[0]) > 1.0e-6 || Math.abs(worst[2]) > 1.0e-6) {
            throw new AssertionError("a sleeping " + what + " morph must not "
                    + "slide horizontally either: dx=" + worst[0] + " dz="
                    + worst[2]);
        }
    }

    // ------------------------------------------------------------------
    // assertions (the part that can actually go red)
    // ------------------------------------------------------------------

    /**
     * Extracts the render state the morph pipeline really produces for the
     * sleeping local player and checks the two fields
     * {@code LivingEntityRenderer} keys the lie-down on.
     *
     * @param expectLyingDown true for a humanoid morph (pose SLEEPING + a
     *                        non-null bed orientation, i.e. the anchored
     *                        lie-down branch); false for a non-humanoid one,
     *                        which must NOT be in the sleeping pose at all
     */
    private static void assertSleepRenderState(ClientGameTestContext context,
            boolean expectLyingDown, String what) {
        context.runOnClient(client -> {
            if (client.player == null || !client.player.isSleeping()) {
                throw new AssertionError("harness precondition: the local "
                        + "player must be asleep for the " + what + " case");
            }
            EntityRenderState state = client.getEntityRenderDispatcher()
                    .extractEntity(client.player, 1.0f);
            if (!(state instanceof LivingEntityRenderState living)) {
                throw new AssertionError("the " + what + " morph did not "
                        + "extract a LivingEntityRenderState, got " + state);
            }
            boolean lying = living.pose == Pose.SLEEPING;
            if (lying != expectLyingDown) {
                throw new AssertionError("a sleeping " + what
                        + " morph must render with pose "
                        + (expectLyingDown ? "SLEEPING (it is a humanoid, so it"
                                + " lies down like a player)"
                                : "!= SLEEPING (it is NOT a humanoid, so it "
                                        + "stays upright)")
                        + " but the extracted render state carries "
                        + living.pose);
            }
            if (expectLyingDown && living.bedOrientation == null) {
                throw new AssertionError("a sleeping " + what + " morph must "
                        + "carry the bed orientation — that is what anchors the"
                        + " body on the pillow; a null here IS the reported "
                        + "\"standing on the pillow part\" symptom");
            }
        });
    }

    // ------------------------------------------------------------------
    // server-side drivers
    // ------------------------------------------------------------------

    /** Spawns {@code type} beside the player and acquires+wears it. */
    private static void morphInto(ClientGameTestContext context,
            EntityType<? extends LivingEntity> type) {
        morphInto(context, type, false);
    }

    /** As above, optionally as the BABY form - which needs {@code childMorphs}
     *  on, as it is by default. */
    private static void morphInto(ClientGameTestContext context,
            EntityType<? extends LivingEntity> type, boolean baby) {
        onServer(context, (server, player) -> {
            ServerLevel level = player.level();
            Entity victim = type.create(level, EntitySpawnReason.COMMAND);
            if (!(victim instanceof LivingEntity living)) {
                throw new AssertionError("could not build " + type);
            }
            if (baby) {
                if (!(living instanceof Mob mob)) {
                    throw new AssertionError(type + " has no baby form");
                }
                mob.setBaby(true);
                if (!mob.isBaby()) {
                    throw new AssertionError(type + " would not become a baby");
                }
            }
            living.snapTo(player.getX() + 2.0, player.getY(),
                    player.getZ() + 2.0, 0.0f, 0.0f);
            level.addFreshEntity(living);
            Morph.clearTransitionLock(player);
            if (!Morph.acquireTarget(player, living, true, true)) {
                throw new AssertionError("failed to morph into "
                        + (baby ? "a baby " : "") + type
                        + (baby ? " (childMorphs must default to true)" : ""));
            }
        });
    }

    private static void demorph(ClientGameTestContext context) {
        onServer(context, (server, player) -> Morph.demorph(player));
    }

    /**
     * Sleeps through the REAL path: the client right-clicks the bed head, so
     * the server runs {@code BedBlock.useWithoutItem -> startSleepInBed} and
     * BOTH sides end up in the bed.
     *
     * <p>Driving {@code LivingEntity.startSleeping} server-side instead does
     * NOT work for a screenshot harness, and the reason is Playbook §1: a
     * ServerPlayer's position is bookkeeping — the client owns it. The pose and
     * sleeping-pos are synced entity data, so the ASSERTIONS passed, but the
     * client's LocalPlayer stayed at world spawn and every photograph showed an
     * empty bed (observed, run 3 of this harness).</p>
     */
    private static void sleep(ClientGameTestContext context, BlockPos head) {
        context.runOnClient(client ->
                client.gameMode.useItemOn(client.player,
                        InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(head), Direction.UP,
                                head, false)));
        context.waitTicks(5);
        // Drop InBedChatScreen out of the frame. setScreen lives on Gui in 26.2
        // (Playbook §7: "Gui.screen(), off Minecraft"), and it calls the
        // outgoing screen's removed(), NOT onClose() — InBedChatScreen only
        // overrides onClose() (javap: its public surface is init/onClose/
        // onPlayerWokeUp) — so nulling the screen cannot send the wake packet.
        // The assertion re-checks isSleeping() afterwards regardless.
        context.runOnClient(client -> client.gui.setScreen(null));
        context.waitTicks(2);
    }

    /** The "Leave Bed" button's own action, so both sides wake together. */
    private static void wake(ClientGameTestContext context) {
        context.runOnClient(client -> client.player.connection.send(
                new ServerboundPlayerCommandPacket(client.player,
                        ServerboundPlayerCommandPacket.Action.STOP_SLEEPING)));
        context.waitTicks(5);
    }

    /**
     * Stands the player just south of the bed. Uses the real /tp command, which
     * goes through {@code ServerGamePacketListenerImpl.teleport} and therefore
     * really MOVES the client — a bare server-side {@code teleportTo} does not
     * (Playbook §1: player position is client-authoritative).
     */
    private static void standBeside(TestSingleplayerContext singleplayer,
            ClientGameTestContext context, BlockPos bedFoot) {
        run(singleplayer, "tp @a " + (bedFoot.getX() + 0.5) + " "
                + (GROUND + 1) + " " + (bedFoot.getZ() - 1.6) + " 0 0");
        context.waitTicks(5);
    }

    private interface ServerAction {
        void run(MinecraftServer server, ServerPlayer player);
    }

    /**
     * Runs {@code action} on the SERVER thread and waits for it. Called from the
     * client thread, {@code MinecraftServer.execute} genuinely queues (it only
     * runs inline when already on the server thread — Playbook §8), so the wait
     * afterwards is what makes the effect visible.
     */
    private static void onServer(ClientGameTestContext context,
            ServerAction action) {
        context.runOnClient(client -> {
            MinecraftServer server = client.getSingleplayerServer();
            if (server == null) {
                throw new AssertionError("no integrated server");
            }
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayers()
                        .getFirst();
                action.run(server, player);
            });
        });
        context.waitTicks(5);
    }

    // ------------------------------------------------------------------
    // camera / plumbing
    // ------------------------------------------------------------------

    private static void run(TestSingleplayerContext singleplayer,
            String command) {
        singleplayer.getServer().runCommand(command);
    }

    private static void shot(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name)
                .withSize(WIDTH, HEIGHT));
    }

}
