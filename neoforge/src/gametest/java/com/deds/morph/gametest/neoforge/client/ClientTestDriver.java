package com.deds.morph.gametest.neoforge.client;

import com.deds.api.id.BId;

import com.deds.morph.Morph;
import com.deds.morph.MorphVariant;
import com.deds.morph.client.MorphAcquisitions;
import com.deds.morph.client.MorphDummies;
import com.deds.morph.client.MorphHands;
import com.deds.morph.client.MorphRadial;
import com.deds.morph.client.MorphSelector;

import com.mojang.blaze3d.platform.InputConstants;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppedEvent;
import net.neoforged.neoforge.client.gui.GuiLayer;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.common.NeoForge;

import net.minecraft.client.CameraType;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.CowRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * The NeoForge client test driver: Morph's client render harness on a loader
 * that has no client gametest API. It does the harness half of what Fabric's
 * client gametest API does for {@code MorphRenderClientTest}, as a state
 * machine on {@code ClientTickEvent.Post}: wait for the title screen, create
 * a flat creative world exactly as Fabric's {@code TestWorldBuilderImpl}
 * does, run the scenarios below, save and quit, stop the game, and exit with
 * 0 only if every check passed.
 *
 * <p>The scenarios are NOT a port of every Fabric section. They cover what a
 * loader can break: the key mappings, the mouse hooks, the HUD layer, the
 * level-render hook, the hand hook, the owner-only resync, and that the
 * player's render state really is the morph's (NeoForge patches
 * {@code EntityRenderDispatcher}, {@code LivingEntityRenderer} and
 * {@code AvatarRenderer}, so a morph that silently falls back to a plain
 * player must fail here, not only look wrong in a screenshot). The Fabric
 * test's sleeping render state and jitter sweep, and its selection gate,
 * have no counterpart here.</p>
 *
 * <p><b>Scenarios</b> (hard assertions wherever the state is readable, plus
 * screenshots under {@code build/run/clientGameTest/screenshots}, named like
 * the Fabric harness's where they show the same thing):</p>
 * <ol type="a">
 * <li>Mixin audit: {@code MixinEnvironment.audit()} between two log markers.
 *     It only logs, so its verdict is a grep of the run log between the
 *     markers, done after the run.</li>
 * <li>Morph's six key mappings: present, in the {@code deds_morph:main}
 *     category, which is registered, with the default keys.</li>
 * <li>Morphing into a zombie in third person, mid-change and after; the
 *     local player's box stays the player's until the change ends, then is
 *     the zombie's, while the camera eye glides strictly between the two
 *     heights; after the change the render state extracted for the local
 *     player is a zombie's, not a player's.</li>
 * <li>The selector: opens; the real {@code MouseHandler.onScroll} moves its
 *     selection and not the hotbar slot; real {@code MouseHandler.onButton}
 *     clicks act on the selector only (the Fabric harness's snowball
 *     check), with controls proving the simulated input reaches the game.</li>
 * <li>The favourites radial: opened the way a player opens it (the grave
 *     key held); the crosshair layer's inner render does not run while it
 *     shows, while the layer itself keeps being visited.</li>
 * <li>The acquisition effect: a real kill; the client received the effect
 *     and the level-render pass drew it.</li>
 * <li>The first-person morph hand: the hand hook runs and the zombie's arm
 *     replaces the vanilla one; on NeoForge 26.2 that is the 6-argument
 *     {@code AvatarRenderer} hook.</li>
 * <li>A baby cow morph, mid-change and after; the render state is a baby
 *     cow's.</li>
 * <li>The owner-only list resync: the client keeps its acquired list
 *     through death and respawn, a trip to the nether and a save, quit and
 *     reload, and no morph transition starts on the way.</li>
 * </ol>
 *
 * <p><b>Failure handling.</b> Every step has a tick budget (world creation
 * gets Fabric's 1200); running out records a failure. A failed scenario is
 * recorded and the driver moves on to the next one; a failure while setting
 * the world up skips to saving and stopping. Nothing may escape into
 * Minecraft's main loop: an exception out of {@code Main.main} makes FML
 * show a blocking error dialog instead of exiting. The results go to
 * {@code morph-client-test-results.txt} in the game directory, rewritten
 * after every check so a run that dies still leaves what it found, and the
 * exit code comes from a {@code ClientStoppedEvent} listener. A watchdog
 * thread ends a run that stops ticking altogether.</p>
 *
 * <p>Morph's client state that has no public reader (the selector's row, the
 * acquisition effects, the running transitions, the two render counters) is
 * read by reflection: it is test instrumentation, not API.</p>
 */
final class ClientTestDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger("deds_morph_test");

    /** The log lines the post-run check greps between for the mixin audit. */
    static final String AUDIT_BEGIN = "[deds_morph_test] MIXIN AUDIT BEGIN";
    static final String AUDIT_END = "[deds_morph_test] MIXIN AUDIT END";

    static final String RESULT_FILE = "morph-client-test-results.txt";

    /** Fabric's world creation/load budget ({@code SharedConstants.TICKS_PER_MINUTE}). */
    private static final int WORLD_TICKS = 1200;
    private static final int CHUNK_TICKS = 1200;
    private static final int TITLE_TICKS = 2400;
    private static final int SERVER_TICKS = 200;
    private static final int SHOT_TICKS = 200;
    /** A morph change, plus the margin the Fabric harness waits after one. */
    private static final int SETTLE = Morph.TRANSITION_TICKS + 25;
    /** Longer than MorphDummies' 40-tick "adopt silently" grace after a
     *  player (re)appears: a list that arrives later than that shows. */
    private static final int RESYNC_WAIT = 60;
    private static final long WATCHDOG_MINUTES = 25;
    private static final float EPS = 1.0e-4f;

    private static final Identifier MORPH_CATEGORY =
            Identifier.fromNamespaceAndPath("deds_morph", "main");

    // ------------------------------------------------------------------
    // the step machinery
    // ------------------------------------------------------------------

    /** One client tick of a step; true when the step is done. Throws to fail
     *  its scenario. {@code ticks} counts from 0 at the step's first tick. */
    @FunctionalInterface
    private interface Step {
        boolean tick(Minecraft mc, int ticks) throws Exception;
    }

    @FunctionalInterface
    private interface Action {
        void run(Minecraft mc) throws Exception;
    }

    /** A check: returns the PASS detail, or throws with the FAIL detail. */
    @FunctionalInterface
    private interface Assertion {
        String check(Minecraft mc) throws Exception;
    }

    @FunctionalInterface
    private interface ServerAction {
        void run(MinecraftServer server, ServerPlayer player) throws Exception;
    }

    /** A failed expectation, with the message that goes into the results. */
    private static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message);
        }
    }

    private record Scenario(String name, boolean critical, List<Step> steps) {
    }

    private record Result(String id, boolean pass, String detail) {
    }

    private final List<Scenario> scenarios = new ArrayList<>();
    private final List<Result> results = new ArrayList<>();
    private final Path gameDir = FMLPaths.GAMEDIR.get();
    private int scenarioIndex;
    private int stepIndex;
    private int stepTicks;
    private boolean inTick;
    private volatile boolean stopRequested;
    private volatile boolean completed;
    private volatile boolean exited;

    // ------------------------------------------------------------------
    // what the scenarios share
    // ------------------------------------------------------------------

    /** The new world's save folder, for reopening it. */
    private String levelId;
    /** Calls into the crosshair layer INSIDE Morph's wrapper (render thread). */
    private int innerCrosshairRenders;
    /** Visits of the (wrapped) crosshair layer, from NeoForge's layer event. */
    private int outerCrosshairVisits;
    private int innerMark;
    private int outerMark;
    private int handCallsMark;
    private int handReplacedMark;
    private int effectsMark;
    private int effectSubmitsMark;
    private int selectedMark;
    private int slotMark;
    private LocalPlayer playerMark;
    /** The server's acquired list and worn morph at the last read. */
    private final AtomicReference<List<MorphVariant>> serverList = new AtomicReference<>();
    private final AtomicReference<Optional<MorphVariant>> serverWorn = new AtomicReference<>();
    /** Which part of scenario (i) is being watched for morph transitions. */
    private String transitionWatch;
    private final Map<String, Integer> transitionTicks = new LinkedHashMap<>();

    // reflective readers of Morph's and Minecraft's unexposed client state
    private static final Field SELECTOR_ROW = field(MorphSelector.class, "selected");
    private static final Field HAND_CALLS = field(MorphHands.class, "submitHandCalls");
    private static final Field HAND_REPLACED = field(MorphHands.class, "submitHandReplaced");
    private static final Field EFFECTS = field(MorphAcquisitions.class, "EFFECTS");
    private static final Field EFFECT_SUBMITS =
            field(MorphAcquisitions.class, "submitEffectCalls");
    private static final Field TRANSITIONS = field(MorphDummies.class, "TRANSITIONS");
    private static final Field LIGHT_QUEUE = field(ClientLevel.class, "lightUpdateQueue");
    private static final Field CATEGORY_ORDER = field(KeyMapping.Category.class, "SORT_ORDER");
    private static final Method ON_SCROLL = method(MouseHandler.class, "onScroll",
            long.class, double.class, double.class);
    private static final Method ON_BUTTON = method(MouseHandler.class, "onButton",
            long.class, MouseButtonInfo.class, int.class);

    // ------------------------------------------------------------------
    // wiring
    // ------------------------------------------------------------------

    void register(IEventBus modBus) {
        buildScenarios();
        modBus.addListener(FMLClientSetupEvent.class, this::onClientSetup);
        // HIGHEST: this wrapper goes on first, so Morph's wrapper (normal
        // priority) wraps IT, and this counter counts exactly the calls
        // Morph's crosshair gate lets through to the vanilla layer.
        modBus.addListener(EventPriority.HIGHEST, RegisterGuiLayersEvent.class,
                this::wrapCrosshair);
        NeoForge.EVENT_BUS.addListener(RenderGuiLayerEvent.Pre.class, event -> {
            if (VanillaGuiLayers.CROSSHAIR.equals(event.getName())) {
                outerCrosshairVisits++;
            }
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> onClientTick());
        NeoForge.EVENT_BUS.addListener(ClientStoppedEvent.class, event -> onClientStopped());
        startWatchdog();
        LOGGER.info("[deds_morph_test] client test driver armed: {} scenarios",
                scenarios.size());
    }

    /**
     * Options that must be in place before the first screen is chosen, set on
     * the main thread during client setup (before the initial resource load
     * ends and the game picks between the accessibility onboarding screen and
     * the title screen). The game directory is fresh, so without this the
     * first launch would open the onboarding screen. Fabric's harness sets
     * the same fields at startup.
     */
    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                var options = Minecraft.getInstance().options;
                options.onboardAccessibility = false;
                options.tutorialStep = TutorialSteps.NONE;
                // An unfocused test window must not pause the game: the
                // selector closes on the pause screen and the integrated
                // server stops ticking.
                options.pauseOnLostFocus = false;
            } catch (Throwable t) {
                LOGGER.error("[deds_morph_test] could not preset the options", t);
            }
        });
    }

    private void wrapCrosshair(RegisterGuiLayersEvent event) {
        try {
            event.wrapLayer(VanillaGuiLayers.CROSSHAIR,
                    vanilla -> (GuiLayer) (graphics, deltaTracker) -> {
                        innerCrosshairRenders++;
                        vanilla.render(graphics, deltaTracker);
                    });
        } catch (Throwable t) {
            LOGGER.error("[deds_morph_test] could not wrap the crosshair layer", t);
        }
    }

    private void startWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(WATCHDOG_MINUTES));
            } catch (InterruptedException e) {
                return;
            }
            if (exited) {
                return;
            }
            record("driver.watchdog", false, "the client test did not finish within "
                    + WATCHDOG_MINUTES + " minutes (at " + position() + ")");
            writeResults();
            LOGGER.error("[deds_morph_test] watchdog: halting the JVM");
            Runtime.getRuntime().halt(3);
        }, "deds_morph_test client watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    // ------------------------------------------------------------------
    // the state machine
    // ------------------------------------------------------------------

    private void onClientTick() {
        if (stopRequested || inTick) {
            return;
        }
        inTick = true;
        Minecraft mc = Minecraft.getInstance();
        Scenario scenario = scenarios.get(scenarioIndex);
        try {
            sampleTransitions(mc);
            if (stepIndex == 0 && stepTicks == 0) {
                LOGGER.info("[deds_morph_test] scenario {} begins", scenario.name());
            }
            boolean done = scenario.steps().get(stepIndex).tick(mc, stepTicks);
            stepTicks++;
            if (done) {
                stepIndex++;
                stepTicks = 0;
                if (stepIndex >= scenario.steps().size()) {
                    nextScenario(mc, false);
                }
            }
        } catch (Throwable t) {
            String why = describe(t);
            LOGGER.error("[deds_morph_test] scenario {} failed at step {}: {}",
                    scenario.name(), stepIndex, why, t);
            record(scenario.name() + ".aborted", false, "step " + stepIndex + ": " + why);
            ClientTestInput.releaseAll();
            nextScenario(mc, scenario.critical());
        } finally {
            inTick = false;
        }
    }

    /** Moves on; a failed critical scenario skips to the last (finish) one. */
    private void nextScenario(Minecraft mc, boolean skipToFinish) {
        int finish = scenarios.size() - 1;
        if (scenarioIndex >= finish) {
            requestStop(mc);
            return;
        }
        scenarioIndex = skipToFinish ? finish : scenarioIndex + 1;
        stepIndex = 0;
        stepTicks = 0;
    }

    private void requestStop(Minecraft mc) {
        if (stopRequested) {
            return;
        }
        stopRequested = true;
        writeResults();
        LOGGER.info("[deds_morph_test] stopping the client");
        mc.stop();
    }

    /** The exit code, once the client has shut down (after the world saved). */
    private void onClientStopped() {
        int code = 1;
        try {
            if (!completed) {
                record("driver.completed", false, "the client stopped before the driver "
                        + "finished (at " + position() + ")");
            }
            writeResults();
            code = failures() == 0 && completed ? 0 : 1;
            LOGGER.info("[deds_morph_test] CLIENT TEST SUMMARY: {}", summary());
        } catch (Throwable t) {
            LOGGER.error("[deds_morph_test] could not finish the report", t);
        } finally {
            exited = true;
            System.exit(code);
        }
    }

    private String position() {
        Scenario scenario = scenarios.get(Math.min(scenarioIndex, scenarios.size() - 1));
        return "scenario " + scenario.name() + ", step " + stepIndex + ", tick " + stepTicks;
    }

    // ------------------------------------------------------------------
    // results
    // ------------------------------------------------------------------

    private synchronized void record(String id, boolean pass, String detail) {
        results.add(new Result(id, pass, detail));
        LOGGER.info("[deds_morph_test] {} {}: {}", pass ? "PASS" : "FAIL", id, detail);
        writeResults();
    }

    private synchronized int failures() {
        int failed = 0;
        for (Result result : results) {
            if (!result.pass()) {
                failed++;
            }
        }
        return failed;
    }

    private synchronized String summary() {
        int failed = failures();
        StringBuilder text = new StringBuilder();
        text.append(results.size()).append(" checks, ").append(results.size() - failed)
                .append(" passed, ").append(failed).append(" failed, completed=")
                .append(completed);
        for (Result result : results) {
            if (!result.pass()) {
                text.append("; FAIL ").append(result.id());
            }
        }
        return text.toString();
    }

    /** Rewrites the results file (through a temporary file, so a reader never
     *  sees half of it). */
    private synchronized void writeResults() {
        StringBuilder text = new StringBuilder();
        text.append("Morph Mod Revived: NeoForge client test driver\n");
        text.append("summary: ").append(summary()).append('\n');
        for (Result result : results) {
            text.append(result.pass() ? "PASS " : "FAIL ").append(result.id())
                    .append(" - ").append(result.detail()).append('\n');
        }
        try {
            Path file = gameDir.resolve(RESULT_FILE);
            Path temp = gameDir.resolve(RESULT_FILE + ".tmp");
            Files.writeString(temp, text.toString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("[deds_morph_test] could not write {}", RESULT_FILE, e);
        }
    }

    private static String describe(Throwable t) {
        Throwable cause = t;
        while ((cause instanceof InvocationTargetException
                || cause instanceof CompletionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof Failure ? cause.getMessage()
                : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    // ------------------------------------------------------------------
    // the scenarios
    // ------------------------------------------------------------------

    private void buildScenarios() {
        title();
        mixinAudit();
        keyMappings();
        world();
        zombieMorph();
        acquisitionEffect();
        babyCow();
        selector();
        firstPersonHand();
        favouritesRadial();
        ownerListResync();
        finish(); // always last: nextScenario skips here after a critical failure
    }

    /** The title screen, and the options the rest relies on. */
    private void title() {
        scenario("title", true)
                .waitFor("the title screen", TITLE_TICKS, mc -> {
                    Screen screen = mc.gui.screen();
                    if (screen instanceof AccessibilityOnboardingScreen) {
                        mc.gui.setScreen(new TitleScreen());
                    }
                    return mc.gui.overlay() == null && mc.gui.screen() instanceof TitleScreen;
                })
                .act("apply the test options", mc -> {
                    var options = mc.options;
                    options.onboardAccessibility = false;
                    options.tutorialStep = TutorialSteps.NONE;
                    options.pauseOnLostFocus = false;
                    // Fabric's harness defaults: no clouds, a small render
                    // distance (chunks finish sooner), no music, no chunk
                    // fade, no anisotropic filtering.
                    options.cloudStatus().set(CloudStatus.OFF);
                    options.renderDistance().set(5);
                    options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(0.0);
                    options.chunkSectionFadeInTime().set(0.0);
                    options.maxAnisotropyBit().set(0);
                    // MorphRenderClientTest's camera settings.
                    options.fov().set(70);
                    options.bobView().set(false);
                    options.chatVisibility().set(ChatVisiblity.HIDDEN);
                    options.setCameraType(CameraType.THIRD_PERSON_BACK);
                })
                .done();
    }

    /** The world, created as Fabric's TestWorldBuilderImpl creates it. */
    private void world() {
        scenario("world", true)
                .act("create a flat creative world", mc -> {
                    Screen old = mc.gui.screen();
                    CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(old));
                    if (!(mc.gui.screen() instanceof CreateWorldScreen create)) {
                        throw new Failure("CreateWorldScreen.openFresh opened "
                                + screenName(mc.gui.screen()));
                    }
                    WorldCreationUiState ui = create.getUiState();
                    Holder<WorldPreset> flat = ui.getSettings().worldgenLoadContext()
                            .lookupOrThrow(Registries.WORLD_PRESET)
                            .getOrThrow(WorldPresets.FLAT);
                    ui.setWorldType(new WorldCreationUiState.WorldTypeEntry(flat));
                    ui.setSeed("1");
                    ui.setGenerateStructures(false);
                    ui.getGameRules().set(GameRules.ADVANCE_TIME, false, null);
                    ui.getGameRules().set(GameRules.ADVANCE_WEATHER, false, null);
                    ui.getGameRules().set(GameRules.SPAWN_MOBS, false, null);
                    ui.getGameRules().set(GameRules.RESPAWN_RADIUS, 0, null);
                    // MorphRenderClientTest's one adjustment.
                    ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    levelId = ui.getTargetFolder();
                    if (!clickButton(mc.gui.screen(), "selectWorld.create")) {
                        throw new Failure("no \"" + translated("selectWorld.create")
                                + "\" button on " + screenName(mc.gui.screen()));
                    }
                })
                .waitFor("the new world to load", WORLD_TICKS, mc -> {
                    pressWorldLoadPrompts(mc);
                    return worldLoaded(mc);
                })
                .waitFor("the chunks around the player to render", CHUNK_TICKS,
                        ClientTestDriver::chunksRendered)
                .check("world.loaded", mc -> "flat creative world \"" + levelId + "\" loaded, "
                        + mc.level.dimension().identifier() + ", player at "
                        + mc.player.blockPosition().toShortString())
                .command("time set noon")
                .command("weather clear")
                .command("kill @e[type=!player]")
                .command("tp @a 0.5 -60 0.5 0 15")
                .waitTicks(20)
                .act("face south", mc -> look(mc, 0.0f, 15.0f))
                .waitTicks(5)
                .done();
    }

    /**
     * (a) The audit only logs; the markers bracket what the post-run grep
     * reads. Run at the title screen, before any world: server-side targets
     * (the packet listener, the server player) are not loaded yet, so the
     * audit has real work, and its "Force-loading class" lines prove it ran
     * rather than finding nothing to do. It loads without initialising
     * (Mixin's {@code findClass(name, false)}).
     */
    private void mixinAudit() {
        scenario("a", false)
                .act("audit every mixin target", mc -> {
                    MixinEnvironment environment = MixinEnvironment.getCurrentEnvironment();
                    Object transformer = environment.getActiveTransformer();
                    LOGGER.info(AUDIT_BEGIN);
                    try {
                        LOGGER.info("[deds_morph_test] active mixin transformer: {}",
                                transformer == null ? "none" : transformer.getClass().getName());
                        environment.audit();
                    } finally {
                        LOGGER.info(AUDIT_END);
                    }
                })
                .check("a.mixin_audit_ran", mc -> {
                    Object transformer =
                            MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
                    if (!(transformer instanceof IMixinTransformer)) {
                        throw new Failure("MixinEnvironment.audit() does nothing here: the "
                                + "active transformer is " + transformer);
                    }
                    return "audit ran through " + transformer.getClass().getSimpleName()
                            + " between the log markers; its verdict is the post-run grep";
                })
                .done();
    }

    /** (b) The six mappings, their category and their default keys. */
    private void keyMappings() {
        scenario("b", false)
                .check("b.key_mappings", mc -> {
                    String[] names = {"selector_prev", "selector_next", "selector_select",
                        "selector_cancel", "selector_remove", "selector_favourite"};
                    int[] keys = {InputConstants.KEY_LBRACKET, InputConstants.KEY_RBRACKET,
                        InputConstants.KEY_RETURN, InputConstants.KEY_ESCAPE,
                        InputConstants.KEY_DELETE, InputConstants.KEY_GRAVE};
                    KeyMapping.Category category = new KeyMapping.Category(MORPH_CATEGORY);
                    Collection<?> registered = (Collection<?>) CATEGORY_ORDER.get(null);
                    if (!registered.contains(category)) {
                        throw new Failure("the category " + MORPH_CATEGORY
                                + " is not registered (KeyMapping.Category.SORT_ORDER)");
                    }
                    StringBuilder found = new StringBuilder();
                    for (int i = 0; i < names.length; i++) {
                        String name = "key.deds_morph." + names[i];
                        KeyMapping mapping = null;
                        for (KeyMapping candidate : mc.options.keyMappings) {
                            if (candidate.getName().equals(name)) {
                                mapping = candidate;
                            }
                        }
                        if (mapping == null) {
                            throw new Failure(name + " is not in Options.keyMappings");
                        }
                        if (!mapping.getCategory().equals(category)) {
                            throw new Failure(name + " is in category "
                                    + mapping.getCategory().id() + ", not " + MORPH_CATEGORY);
                        }
                        // The running version's keyboard type: KEYSYM (GLFW)
                        // on 26.2, KEYBOARD (SDL) on 26.3; UNKNOWN is on it.
                        InputConstants.Key key = mapping.getDefaultKey();
                        if (key.getType() != InputConstants.UNKNOWN.getType()
                                || key.getValue() != keys[i]) {
                            throw new Failure(name + " defaults to " + key.getName() + " ("
                                    + key.getType() + " " + key.getValue()
                                    + "), expected keyboard code " + keys[i]);
                        }
                        if (mapping.getDefaultKeyModifier() != KeyModifier.NONE) {
                            throw new Failure(name + " defaults to the NeoForge modifier "
                                    + mapping.getDefaultKeyModifier() + ", expected none");
                        }
                        found.append(found.isEmpty() ? "" : ", ").append(names[i]).append('=')
                                .append(key.getName());
                    }
                    return "6 mappings in " + MORPH_CATEGORY + " (registered), no modifier: "
                            + found;
                })
                .done();
    }

    /** (c) A zombie, third person: mid-change, after, and the box. */
    private void zombieMorph() {
        scenario("c", false)
                .act("third person, facing south", mc -> {
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    look(mc, 0.0f, 15.0f);
                })
                .server("spawn a zombie and morph into it", morphInto(EntityTypes.ZOMBIE, false))
                .waitTicks(Morph.TRANSITION_TICKS / 2 - 5)
                .check("c.box_waits_for_the_change", mc -> {
                    // The box snaps at the END of a change, on the client as on
                    // the server (MorphDummies.reconcileBox skips mid-change),
                    // while the camera eye glides from the old form's height
                    // to the new one's over the change
                    // (MorphDummies.eyeHeightOverride).
                    EntityDimensions player = EntityTypes.PLAYER.getDimensions();
                    EntityDimensions zombie = EntityTypes.ZOMBIE.getDimensions();
                    float width = mc.player.getBbWidth();
                    float height = mc.player.getBbHeight();
                    float eye = mc.player.getEyeHeight();
                    if (Math.abs(width - player.width()) > EPS
                            || Math.abs(height - player.height()) > EPS) {
                        throw new Failure("mid-change the box must still be a player's "
                                + player.width() + " x " + player.height() + ", but it is "
                                + width + " x " + height);
                    }
                    // STRICTLY between, with a margin: an eye held at the
                    // player's height (the override not applied) and one
                    // that snapped to the zombie's at the start are the two
                    // failures this check exists for. Half-way it reads
                    // about 1.66; client lag moves that by hundredths.
                    float low = player.eyeHeight() + 0.01f;
                    float high = zombie.eyeHeight() - 0.01f;
                    if (!(eye > low && eye < high)) {
                        throw new Failure("mid-change the camera eye must be gliding, "
                                + "strictly between the player's " + player.eyeHeight()
                                + " and the zombie's " + zombie.eyeHeight() + " (so within "
                                + low + " .. " + high + "), but it is " + eye);
                    }
                    return "box still a player's " + width + " x " + height
                            + ", eye gliding at " + eye + " (" + player.eyeHeight() + " -> "
                            + zombie.eyeHeight() + ")";
                })
                .shot("01_zombie_morph_mid_change")
                .waitTicks(Morph.TRANSITION_TICKS / 2 + 25)
                .check("c.zombie_dimensions", mc -> sameBox(mc.player,
                        EntityTypes.ZOMBIE, false, "a zombie's"))
                .check("c.render_state_is_the_zombie", mc -> {
                    EntityRenderState state = playerRenderState(mc);
                    if (state instanceof AvatarRenderState || !(state instanceof ZombieRenderState)) {
                        throw new Failure("morphed into a zombie, the local player must extract "
                                + "a ZombieRenderState, but it extracts " + stateName(state)
                                + ": the morph's extraction hook is not reached, so the player "
                                + "renders as a player");
                    }
                    return "extractEntity(local player) = " + stateName(state);
                })
                .shot("01b_zombie_morph_after_change")
                .done();
    }

    /** (f) A real kill; the effect reaches the client and gets drawn. */
    private void acquisitionEffect() {
        scenario("f", false)
                .act("face south", mc -> look(mc, 0.0f, 15.0f))
                .waitTicks(3)
                .act("note the effect counters", mc -> {
                    effectsMark = ((List<?>) EFFECTS.get(null)).size();
                    effectSubmitsMark = EFFECT_SUBMITS.getInt(null);
                })
                .server("kill a pig as the player", (server, player) -> {
                    ServerLevel level = player.level();
                    Entity pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
                    if (!(pig instanceof LivingEntity victim)) {
                        throw new Failure("could not build a pig");
                    }
                    // Ahead and to one side: the third-person camera looks
                    // over the player's back, so a pig straight ahead would
                    // be hidden behind the player all the way in.
                    victim.snapTo(player.getX() + 2.5, player.getY(), player.getZ() + 3.0,
                            180.0f, 0.0f);
                    level.addFreshEntity(victim);
                    Morph.clearTransitionLock(player);
                    victim.hurtServer(level, player.damageSources().playerAttack(player), 1000.0f);
                    if (!victim.isDeadOrDying()) {
                        throw new Failure("the pig survived the player's attack");
                    }
                    if (!Morph.STATE.get(player).ownsType(BId.of("minecraft", "pig"))) {
                        throw new Failure("killing the pig did not acquire it (server list "
                                + Morph.STATE.get(player).acquired() + ")");
                    }
                })
                .waitTicks(6)
                .check("f.acquire_fx_received", mc -> {
                    int now = ((List<?>) EFFECTS.get(null)).size();
                    if (now <= effectsMark) {
                        throw new Failure("no acquisition effect started on the client ("
                                + effectsMark + " before the kill, " + now + " after): "
                                + "deds_morph:acquire_fx never reached MorphAcquisitions.begin");
                    }
                    return now + " running effect(s), " + effectsMark + " before the kill";
                })
                .shot("16_acquisition_effect_mid_flight")
                .check("f.acquisition_effect_drawn", mc -> {
                    int now = EFFECT_SUBMITS.getInt(null);
                    if (now <= effectSubmitsMark) {
                        throw new Failure("MorphAcquisitions.submit drew no effect since the "
                                + "kill (counter " + now + "): the level-render hook "
                                + "(SubmitCustomGeometryEvent) is not reaching it");
                    }
                    return (now - effectSubmitsMark) + " effect submits since the kill";
                })
                .waitTicks(SETTLE) // the kill also morphs the player into the pig
                .command("kill @e[type=!player]") // the pig's drops, out of later shots
                .done();
    }

    /** (h) A baby cow: mid-change and after, like Fabric's shots 12 and 12b. */
    private void babyCow() {
        scenario("h", false)
                .act("face south", mc -> look(mc, 0.0f, 15.0f))
                .server("spawn a baby cow and morph into it", morphInto(EntityTypes.COW, true))
                .waitTicks(Morph.TRANSITION_TICKS / 2 - 5)
                .shot("12_baby_cow_mid_change")
                .waitTicks(Morph.TRANSITION_TICKS / 2 + 25)
                .check("h.baby_cow_dimensions", mc -> sameBox(mc.player,
                        EntityTypes.COW, true, "a baby cow's"))
                .check("h.render_state_is_a_baby_cow", mc -> {
                    EntityRenderState state = playerRenderState(mc);
                    if (!(state instanceof CowRenderState cow) || !cow.isBaby) {
                        throw new Failure("morphed into a baby cow, the local player must "
                                + "extract a baby CowRenderState, but it extracts "
                                + stateName(state)
                                + (state instanceof CowRenderState ? " (not a baby)" : ""));
                    }
                    return "extractEntity(local player) = " + stateName(state) + ", baby";
                })
                .shot("12b_baby_cow_after_change")
                .done();
    }

    /** (d) The selector, driven through the real MouseHandler methods. */
    private void selector() {
        scenario("d", false)
                .command("gamemode survival @a")
                .command("clear @a")
                .command("give @a minecraft:snowball 16")
                .waitTicks(10)
                .require("d.precondition", mc -> {
                    int slot = mc.player.getInventory().getSelectedSlot();
                    ItemStack held = mc.player.getMainHandItem();
                    if (!held.is(Items.SNOWBALL) || held.getCount() != 16) {
                        throw new Failure("expected 16 snowballs in hand (slot " + slot
                                + "), holding " + held);
                    }
                    return "survival, 16 snowballs in hotbar slot " + slot;
                })
                .act("open the selector", ClientTestDriver::openSelector)
                .waitTicks(15)
                .require("d.selector_opens", mc -> {
                    if (!MorphSelector.isOpen()) {
                        throw new Failure("MorphSelector.prev() did not open the strip");
                    }
                    return "open, row " + SELECTOR_ROW.getInt(null) + " of "
                            + (Morph.STATE.get(mc.player).groupedByKey().size() + 1);
                })
                .shot("07_selector_open")
                .act("right-click (press and release)", mc ->
                        click(mc, InputConstants.MOUSE_BUTTON_RIGHT))
                .waitTicks(5)
                .check("d.right_click_closes_only_the_selector", mc -> {
                    int left = snowballs(mc);
                    if (MorphSelector.isOpen()) {
                        throw new Failure("a right-click must close the selector");
                    }
                    if (left != 16) {
                        throw new Failure("the right-click that closed the selector also "
                                + "threw a snowball (" + left + " left)");
                    }
                    return "closed, 16 snowballs left";
                })
                .act("open the selector again", ClientTestDriver::openSelector)
                .waitTicks(15)
                .act("left-click (press and release)", mc ->
                        click(mc, InputConstants.MOUSE_BUTTON_LEFT))
                .waitTicks(5)
                .check("d.left_click_picks_and_closes", mc -> {
                    if (MorphSelector.isOpen()) {
                        throw new Failure("a left-click must pick and close the selector");
                    }
                    return "closed";
                })
                .act("control: right-click with the selector closed", mc ->
                        click(mc, InputConstants.MOUSE_BUTTON_RIGHT))
                .waitTicks(5)
                .check("d.control_click_reaches_the_game", mc -> {
                    int left = snowballs(mc);
                    if (left != 15) {
                        throw new Failure("a right-click with the selector closed must throw "
                                + "one snowball (15 left) but " + left + " are left: the "
                                + "simulated clicks are not reaching the game");
                    }
                    return "one snowball thrown, 15 left";
                })
                .act("open the selector again", ClientTestDriver::openSelector)
                .waitTicks(15)
                .act("scroll down one notch", mc -> {
                    selectedMark = SELECTOR_ROW.getInt(null);
                    slotMark = mc.player.getInventory().getSelectedSlot();
                    scroll(mc, -1.0);
                })
                .waitTicks(2)
                .check("d.scroll_moves_the_selection_not_the_hotbar", mc -> {
                    int rows = Morph.STATE.get(mc.player).groupedByKey().size() + 1;
                    int row = SELECTOR_ROW.getInt(null);
                    int slot = mc.player.getInventory().getSelectedSlot();
                    int expected = Math.floorMod(selectedMark + 1, rows);
                    if (!MorphSelector.isOpen() || row != expected) {
                        throw new Failure("scrolling down with the strip open must move its "
                                + "selection from row " + selectedMark + " to " + expected
                                + " of " + rows + ", but it is on row " + row
                                + (MorphSelector.isOpen() ? "" : " and closed"));
                    }
                    if (slot != slotMark) {
                        throw new Failure("the scroll also moved the hotbar from slot "
                                + slotMark + " to " + slot);
                    }
                    return "row " + selectedMark + " -> " + row + " of " + rows
                            + ", hotbar stays on slot " + slot;
                })
                .shot("07b_selector_after_scroll")
                .act("close the selector", mc -> MorphSelector.cancelKey())
                .waitTicks(3)
                .act("control: scroll with the selector closed", mc -> {
                    slotMark = mc.player.getInventory().getSelectedSlot();
                    scroll(mc, -1.0);
                })
                .waitTicks(2)
                .check("d.control_scroll_reaches_the_hotbar", mc -> {
                    int slot = mc.player.getInventory().getSelectedSlot();
                    if (slot == slotMark) {
                        throw new Failure("a scroll with the selector closed must move the "
                                + "hotbar, but it stayed on slot " + slot + ": the "
                                + "simulated scrolls are not reaching the game");
                    }
                    return "hotbar slot " + slotMark + " -> " + slot;
                })
                .act("scroll the hotbar back", mc -> scroll(mc, 1.0))
                .command("gamemode creative @a")
                .command("clear @a") // an EMPTY main hand draws the arm in (g)
                .waitTicks(5)
                .done();
    }

    /** (g) First person as a zombie: the hand hook runs, and replaces the arm. */
    private void firstPersonHand() {
        scenario("g", false)
                .server("wear the zombie again", wear(EntityTypes.ZOMBIE))
                .waitTicks(SETTLE)
                .act("first person, facing south", mc -> {
                    look(mc, 0.0f, 15.0f);
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                })
                .waitTicks(3)
                .act("note the hand counters", mc -> {
                    handCallsMark = HAND_CALLS.getInt(null);
                    handReplacedMark = HAND_REPLACED.getInt(null);
                })
                .waitTicks(10)
                .check("g.first_person_hand_hook_runs", mc -> {
                    int now = HAND_CALLS.getInt(null);
                    if (now <= handCallsMark) {
                        throw new Failure("first person, zombie morph, empty hand: "
                                + "MorphHands.submitHand never ran (counter stayed at "
                                + now + "); the NeoForge hand mixin "
                                + "(deds_morph.neoforge.hand.mixins.json) is not reached");
                    }
                    return (now - handCallsMark) + " hand hook calls in 10 ticks";
                })
                .check("g.first_person_hand_is_the_zombies", mc -> {
                    int calls = HAND_CALLS.getInt(null) - handCallsMark;
                    int replaced = HAND_REPLACED.getInt(null) - handReplacedMark;
                    if (replaced <= 0) {
                        throw new Failure("first person, zombie morph, empty hand: the hand "
                                + "hook ran " + calls + " times but never replaced the vanilla "
                                + "arm (MorphHands.submitHandReplaced did not move): the "
                                + "player's own arm is showing");
                    }
                    return replaced + " of " + calls + " hand hook calls drew the zombie's arm";
                })
                .shot("14_zombie_morph_first_person_hand")
                .done();
    }

    /** (e) The radial, first person (where a crosshair would show). */
    private void favouritesRadial() {
        scenario("e", false)
                .server("star the pig and the baby cow", (server, player) -> {
                    for (MorphVariant variant : Morph.STATE.get(player).acquired()) {
                        String type = variant.type().toString();
                        if ((type.equals("minecraft:pig") || type.equals("minecraft:cow"))
                                && !Morph.STATE.get(player).isFavourite(variant)) {
                            Morph.toggleFavourite(player, variant);
                        }
                    }
                })
                .waitTicks(5)
                .act("first person", mc -> mc.options.setCameraType(CameraType.FIRST_PERSON))
                .act("note the crosshair counters", this::markCrosshair)
                .waitTicks(5)
                .check("e.control_crosshair_draws", mc -> {
                    if (outerCrosshairVisits <= outerMark
                            || innerCrosshairRenders <= innerMark) {
                        throw new Failure("with the radial closed the crosshair layer must "
                                + "render (visits +" + (outerCrosshairVisits - outerMark)
                                + ", inner renders +" + (innerCrosshairRenders - innerMark)
                                + ")");
                    }
                    return "radial closed: layer visited +" + (outerCrosshairVisits - outerMark)
                            + ", inner render +" + (innerCrosshairRenders - innerMark);
                })
                .shot("15a_first_person_crosshair_control")
                .act("hold the grave key", mc -> ClientTestInput.hold(InputConstants.KEY_GRAVE))
                .waitTicks(10)
                .require("e.radial_opens", mc -> {
                    if (!MorphRadial.isShowing()) {
                        throw new Failure("holding grave did not open the favourites radial");
                    }
                    return "showing (grave held, strip closed), "
                            + Morph.STATE.get(mc.player).favourites().size()
                            + " favourites + own form";
                })
                .act("note the crosshair counters", this::markCrosshair)
                .waitTicks(5)
                .check("e.crosshair_hidden_while_the_radial_shows", mc -> {
                    int visits = outerCrosshairVisits - outerMark;
                    int renders = innerCrosshairRenders - innerMark;
                    if (visits <= 0) {
                        throw new Failure("the crosshair layer was not visited at all in 5 "
                                + "ticks, so this proves nothing");
                    }
                    if (renders != 0) {
                        throw new Failure("the vanilla crosshair rendered " + renders
                                + " times while the radial showed (layer visited " + visits
                                + " times)");
                    }
                    return "layer visited " + visits + " times, vanilla crosshair rendered 0";
                })
                .shot("15_favourites_radial_open")
                .act("release the grave key", mc ->
                        ClientTestInput.release(InputConstants.KEY_GRAVE))
                .waitTicks(5)
                .check("e.radial_closes_without_a_pick", mc -> {
                    if (MorphRadial.isShowing()) {
                        throw new Failure("releasing grave must close the radial");
                    }
                    Optional<MorphVariant> worn = Morph.STATE.get(mc.player).current();
                    if (worn.isEmpty()
                            || !worn.get().type().toString().equals("minecraft:zombie")) {
                        throw new Failure("a release with the cursor in the middle must not "
                                + "change the morph, but the player wears " + worn);
                    }
                    return "closed, still a zombie";
                })
                .act("note the crosshair counters", this::markCrosshair)
                .waitTicks(5)
                .check("e.crosshair_returns", mc -> {
                    if (innerCrosshairRenders <= innerMark) {
                        throw new Failure("the crosshair did not come back after the radial");
                    }
                    return "inner render +" + (innerCrosshairRenders - innerMark);
                })
                .act("third person", mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK))
                .waitTicks(2)
                .done();
    }

    /** (i) The owner-only list survives respawn, nether and rejoin. */
    private void ownerListResync() {
        scenario("i", false)
                .server("read the server's list", this::readServerList)
                .require("i.client_list_before", mc -> sameList(mc, 2, "before anything"))
                // death and respawn
                .act("watch for transitions: respawn", mc -> {
                    transitionWatch = "respawn";
                    playerMark = mc.player;
                })
                .command("kill @a")
                .waitFor("the player to die", 100, mc -> mc.player != null
                        && (mc.player.isDeadOrDying() || mc.gui.screen() instanceof DeathScreen))
                .waitTicks(5)
                .act("respawn", mc -> mc.player.respawn())
                .waitFor("the respawned player", SERVER_TICKS, mc -> mc.player != null
                        && mc.player != playerMark && mc.player.isAlive()
                        && !(mc.gui.screen() instanceof DeathScreen))
                .waitTicks(RESYNC_WAIT)
                .server("read the server's list", this::readServerList)
                .check("i.client_list_after_respawn", mc -> sameList(mc, 2, "after respawn"))
                .check("i.no_transition_on_respawn", mc -> noTransition("respawn"))
                // the nether
                .act("watch for transitions: nether", mc -> transitionWatch = "nether")
                .playerCommand("execute in minecraft:the_nether run tp @s ~ 128 ~")
                .waitFor("the nether", 400, mc -> mc.level != null
                        && Level.NETHER.equals(mc.level.dimension()) && worldLoaded(mc))
                .waitFor("the nether chunks to render", CHUNK_TICKS,
                        ClientTestDriver::chunksRendered)
                .waitTicks(RESYNC_WAIT)
                .server("read the server's list", this::readServerList)
                .check("i.client_list_after_nether", mc -> sameList(mc, 2, "in the nether"))
                .check("i.no_transition_on_nether", mc -> noTransition("nether"))
                .act("open the selector", ClientTestDriver::openSelector)
                .waitTicks(15)
                .shot("17_selector_after_respawn_and_nether")
                .act("close the selector", mc -> MorphSelector.cancelKey())
                .waitTicks(3)
                // save, quit, reopen
                .act("save and quit to the title screen", mc -> {
                    transitionWatch = "rejoin";
                    mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
                })
                .waitFor("the title screen after saving", WORLD_TICKS, mc -> mc.level == null
                        && mc.getSingleplayerServer() == null
                        && mc.gui.screen() instanceof TitleScreen)
                .act("reopen the world", mc -> mc.createWorldOpenFlows().openWorld(levelId,
                        () -> LOGGER.error("[deds_morph_test] reopening {} was cancelled",
                                levelId)))
                .waitFor("the reopened world to load", WORLD_TICKS, mc -> {
                    pressWorldLoadPrompts(mc);
                    return worldLoaded(mc);
                })
                .waitFor("the reopened world's chunks to render", CHUNK_TICKS,
                        ClientTestDriver::chunksRendered)
                .waitTicks(RESYNC_WAIT)
                .server("read the server's list", this::readServerList)
                .check("i.client_list_after_rejoin", mc -> sameList(mc, 2, "after rejoining"))
                .check("i.no_transition_on_rejoin", mc -> noTransition("rejoin"))
                .act("stop watching; open the selector", mc -> {
                    transitionWatch = null;
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    openSelector(mc);
                })
                .waitTicks(15)
                .shot("18_selector_after_rejoin")
                .act("close the selector", mc -> MorphSelector.cancelKey())
                .waitTicks(3)
                .done();
    }

    /** Always last: save and quit if in a world, then stop the game. */
    private void finish() {
        scenario("finish", false)
                .act("save and quit", mc -> {
                    ClientTestInput.releaseAll();
                    if (mc.level != null) {
                        mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
                    }
                })
                .waitFor("the title screen", WORLD_TICKS, mc -> mc.level == null
                        && mc.getSingleplayerServer() == null)
                .act("stop", mc -> {
                    completed = true;
                    requestStop(mc);
                })
                .done();
    }

    // ------------------------------------------------------------------
    // scenario helpers
    // ------------------------------------------------------------------

    private void markCrosshair(Minecraft mc) {
        innerMark = innerCrosshairRenders;
        outerMark = outerCrosshairVisits;
    }

    private void readServerList(MinecraftServer server, ServerPlayer player) {
        serverList.set(List.copyOf(Morph.LIST.get(player).acquired()));
        serverWorn.set(Morph.WORN.get(player));
    }

    /** The CLIENT's copy of the owner-only list equals the server's, which
     *  holds at least {@code atLeast} morphs; the worn morph matches too. */
    private String sameList(Minecraft mc, int atLeast, String when) {
        List<MorphVariant> expected = serverList.get();
        if (expected == null || expected.size() < atLeast) {
            throw new Failure("precondition: the server must hold at least " + atLeast
                    + " morphs " + when + ", holds " + expected);
        }
        List<MorphVariant> client = Morph.LIST.get(mc.player).acquired();
        if (!client.equals(expected)) {
            throw new Failure("the client's acquired list " + when + " is " + types(client)
                    + " but the server's is " + types(expected));
        }
        Optional<MorphVariant> worn = Morph.WORN.get(mc.player);
        if (!worn.equals(serverWorn.get())) {
            throw new Failure("the client's worn morph " + when + " is " + worn
                    + " but the server's is " + serverWorn.get());
        }
        return client.size() + " morphs on both sides " + when + " " + types(client)
                + ", wearing " + worn.map(v -> v.type().toString()).orElse("own form");
    }

    private String noTransition(String phase) {
        Integer ticks = transitionTicks.get(phase);
        if (ticks != null) {
            throw new Failure("a morph transition ran on the client for " + ticks
                    + " ticks during the " + phase + " (MorphDummies must adopt the "
                    + "resynced morph silently)");
        }
        return "no client transition during the " + phase;
    }

    /** Counts ticks on which the local player has a running transition, per
     *  watched phase of scenario (i). */
    private void sampleTransitions(Minecraft mc) throws ReflectiveOperationException {
        if (transitionWatch == null || mc.player == null) {
            return;
        }
        Map<?, ?> running = (Map<?, ?>) TRANSITIONS.get(null);
        if (running.containsKey(mc.player.getUUID())) {
            transitionTicks.merge(transitionWatch, 1, Integer::sum);
        }
    }

    private static String types(List<MorphVariant> variants) {
        List<String> names = new ArrayList<>();
        for (MorphVariant variant : variants) {
            names.add(variant.type().toString() + (variant.data().isEmpty() ? "" : "*"));
        }
        return names.toString();
    }

    /** The render state the entity render dispatcher extracts for the local
     *  player right now, Morph's extraction hook included (the call the
     *  Fabric client test makes). Client thread. */
    private static EntityRenderState playerRenderState(Minecraft mc) {
        return mc.getEntityRenderDispatcher().extractEntity(mc.player, 1.0f);
    }

    private static String stateName(EntityRenderState state) {
        return state == null ? "null" : state.getClass().getSimpleName();
    }

    /** The local player's box and eye equal those of a freshly built
     *  {@code type} (a baby if asked): the mob itself is the reference, not
     *  Morph's own idea of its size. */
    private static String sameBox(LocalPlayer player, EntityType<?> type, boolean baby,
            String what) {
        Entity reference = type.create(player.level(), EntitySpawnReason.LOAD);
        if (reference == null) {
            throw new Failure("could not build a reference " + type);
        }
        if (baby) {
            if (!(reference instanceof Mob mob)) {
                throw new Failure(type + " has no baby form");
            }
            mob.setBaby(true);
        }
        return sameBox(player, reference.getBbWidth(), reference.getBbHeight(),
                reference.getEyeHeight(), what);
    }

    private static String sameBox(LocalPlayer player, float wantWidth, float wantHeight,
            float wantEye, String what) {
        float width = player.getBbWidth();
        float height = player.getBbHeight();
        float eye = player.getEyeHeight();
        String box = width + " x " + height + " eye " + eye;
        if (Math.abs(width - wantWidth) > EPS || Math.abs(height - wantHeight) > EPS
                || Math.abs(eye - wantEye) > EPS) {
            throw new Failure("the local player's box must be " + what + " " + wantWidth
                    + " x " + wantHeight + " eye " + wantEye + ", but it is " + box);
        }
        return "local player box = " + what + ": " + box;
    }

    private static int snowballs(Minecraft mc) {
        ItemStack held = mc.player.getMainHandItem();
        return held.is(Items.SNOWBALL) ? held.getCount() : 0;
    }

    private static void openSelector(Minecraft mc) {
        mc.gui.setScreen(null);
        MorphSelector.prev(); // the first press opens the strip
    }

    /** A press and a release through the real {@code MouseHandler.onButton}. */
    private static void click(Minecraft mc, int button) throws ReflectiveOperationException {
        long window = mc.getWindow().handle();
        MouseButtonInfo info = new MouseButtonInfo(button, 0);
        ON_BUTTON.invoke(mc.mouseHandler, window, info, 1);
        ON_BUTTON.invoke(mc.mouseHandler, window, info, 0);
    }

    /** One wheel notch through the real {@code MouseHandler.onScroll}. */
    private static void scroll(Minecraft mc, double vertical) throws ReflectiveOperationException {
        ON_SCROLL.invoke(mc.mouseHandler, mc.getWindow().handle(), 0.0, vertical);
    }

    /** MorphRenderClientTest's look(): yaw 0 faces south (+z). */
    private static void look(Minecraft mc, float yaw, float pitch) {
        LocalPlayer player = mc.player;
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.setYHeadRot(yaw);
        player.yHeadRotO = yaw;
        player.yBodyRot = yaw;
        player.yBodyRotO = yaw;
    }

    /** MorphRenderClientTest's morphInto: spawn beside the player, acquire and wear. */
    private static ServerAction morphInto(EntityType<? extends LivingEntity> type,
            boolean baby) {
        return (server, player) -> {
            ServerLevel level = player.level();
            Entity victim = type.create(level, EntitySpawnReason.COMMAND);
            if (!(victim instanceof LivingEntity living)) {
                throw new Failure("could not build " + type);
            }
            if (baby) {
                if (!(living instanceof Mob mob)) {
                    throw new Failure(type + " has no baby form");
                }
                mob.setBaby(true);
                if (!mob.isBaby()) {
                    throw new Failure(type + " would not become a baby");
                }
            }
            living.snapTo(player.getX() + 2.0, player.getY(), player.getZ() + 2.0, 0.0f, 0.0f);
            level.addFreshEntity(living);
            Morph.clearTransitionLock(player);
            if (!Morph.acquireTarget(player, living, true, true)) {
                throw new Failure("failed to morph into " + (baby ? "a baby " : "") + type);
            }
        };
    }

    /** MorphRenderClientTest's wear: an ALREADY acquired morph, no acquisition. */
    private static ServerAction wear(EntityType<? extends LivingEntity> type) {
        return (server, player) -> {
            String id = EntityType.getKey(type).toString();
            Morph.clearTransitionLock(player);
            Optional<MorphVariant> variant = Morph.STATE.get(player).acquired().stream()
                    .filter(v -> v.type().toString().equals(id))
                    .findFirst();
            if (variant.isEmpty()) {
                throw new Failure("no " + id + " morph acquired to wear");
            }
            Morph.select(player, variant);
        };
    }

    // ------------------------------------------------------------------
    // world helpers (Fabric's ClientGameTestImpl / TestClientLevelContextImpl)
    // ------------------------------------------------------------------

    private static boolean worldLoaded(Minecraft mc) {
        return mc.level != null && mc.player != null
                && !(mc.gui.screen() instanceof LevelLoadingScreen);
    }

    /** The two prompts Fabric's harness clicks through while a world loads. */
    private static void pressWorldLoadPrompts(Minecraft mc) {
        Screen screen = mc.gui.screen();
        if (screen instanceof BackupConfirmScreen) {
            clickButton(screen, "selectWorld.backupJoinSkipButton");
        } else if (screen instanceof ConfirmScreen
                && screen.getTitle().getContents() instanceof TranslatableContents title
                && "selectWorld.warning.experimental.title".equals(title.getKey())) {
            clickButton(screen, "gui.yes");
        }
    }

    /** Every chunk within the render distance is loaded, the light queue is
     *  empty and every section is compiled (Fabric's waitForChunksRender). */
    private static boolean chunksRendered(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return false;
        }
        int distance = mc.options.getEffectiveRenderDistance();
        int centreX = SectionPos.blockToSectionCoord(mc.player.getBlockX());
        int centreZ = SectionPos.blockToSectionCoord(mc.player.getBlockZ());
        for (int dz = -distance; dz <= distance; dz++) {
            for (int dx = -distance; dx <= distance; dx++) {
                if (level.getChunk(centreX + dx, centreZ + dz, ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }
        try {
            return ((Deque<?>) LIGHT_QUEUE.get(level)).isEmpty()
                    && mc.levelRenderer.hasRenderedAllSections();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Presses the button labelled with {@code translationKey}, as Fabric's
     *  clickScreenButton does. */
    private static boolean clickButton(Screen screen, String translationKey) {
        if (screen == null) {
            return false;
        }
        String label = translated(translationKey);
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Button button && label.equals(button.getMessage().getString())) {
                button.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                return true;
            }
        }
        return false;
    }

    private static String translated(String key) {
        return Component.translatable(key).getString();
    }

    private static String screenName(Screen screen) {
        return screen == null ? "no screen" : screen.getClass().getName();
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("the client test driver cannot read "
                    + owner.getName() + "." + name, e);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("the client test driver cannot call "
                    + owner.getName() + "." + name, e);
        }
    }

    // ------------------------------------------------------------------
    // the scenario builder
    // ------------------------------------------------------------------

    private ScenarioBuilder scenario(String name, boolean critical) {
        return new ScenarioBuilder(name, critical);
    }

    private final class ScenarioBuilder {

        private final String name;
        private final boolean critical;
        private final List<Step> steps = new ArrayList<>();

        ScenarioBuilder(String name, boolean critical) {
            this.name = name;
            this.critical = critical;
        }

        void done() {
            scenarios.add(new Scenario(name, critical, List.copyOf(steps)));
        }

        /** Runs once, on the client thread. */
        ScenarioBuilder act(String what, Action action) {
            steps.add((mc, ticks) -> {
                LOGGER.info("[deds_morph_test] {}: {}", name, what);
                action.run(mc);
                return true;
            });
            return this;
        }

        ScenarioBuilder waitTicks(int count) {
            steps.add((mc, ticks) -> ticks + 1 >= count);
            return this;
        }

        /** Waits for {@code condition}; failing after {@code budget} ticks. */
        ScenarioBuilder waitFor(String what, int budget, Predicate<Minecraft> condition) {
            steps.add((mc, ticks) -> {
                if (condition.test(mc)) {
                    LOGGER.info("[deds_morph_test] {}: {} after {} ticks", name, what, ticks);
                    return true;
                }
                if (ticks > 0 && ticks % 200 == 0) {
                    LOGGER.info("[deds_morph_test] {}: still waiting for {} ({} ticks, "
                            + "screen {})", name, what, ticks, screenName(mc.gui.screen()));
                }
                if (ticks >= budget) {
                    throw new Failure("timed out after " + budget + " ticks waiting for "
                            + what + " (screen " + screenName(mc.gui.screen()) + ")");
                }
                return false;
            });
            return this;
        }

        /** Records PASS with the returned detail or FAIL with the thrown one,
         *  and goes on either way. */
        ScenarioBuilder check(String id, Assertion assertion) {
            steps.add((mc, ticks) -> {
                try {
                    record(id, true, assertion.check(mc));
                } catch (Exception | AssertionError e) {
                    record(id, false, describe(e));
                }
                return true;
            });
            return this;
        }

        /** As {@link #check}, but a failure ends the scenario: what follows
         *  depends on it. */
        ScenarioBuilder require(String id, Assertion assertion) {
            steps.add((mc, ticks) -> {
                String detail;
                try {
                    detail = assertion.check(mc);
                } catch (Exception | AssertionError e) {
                    throw new Failure(id + ": " + describe(e));
                }
                record(id, true, detail);
                return true;
            });
            return this;
        }

        /**
         * Runs {@code action} on the SERVER thread with the (first) server
         * player and waits for it: {@code MinecraftServer.execute} from the
         * client thread queues the task for the server's next tick.
         */
        ScenarioBuilder server(String what, ServerAction action) {
            AtomicReference<CompletableFuture<Void>> pending = new AtomicReference<>();
            steps.add((mc, ticks) -> {
                if (ticks == 0) {
                    LOGGER.info("[deds_morph_test] {}: {}", name, what);
                    IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        throw new Failure("no integrated server to " + what);
                    }
                    CompletableFuture<Void> done = new CompletableFuture<>();
                    pending.set(done);
                    server.execute(() -> {
                        try {
                            List<ServerPlayer> players = server.getPlayerList().getPlayers();
                            if (players.isEmpty()) {
                                throw new Failure("no player on the server");
                            }
                            action.run(server, players.getFirst());
                            done.complete(null);
                        } catch (Throwable t) {
                            done.completeExceptionally(t);
                        }
                    });
                    return false;
                }
                CompletableFuture<Void> done = pending.get();
                if (done.isDone()) {
                    try {
                        done.join();
                    } catch (CompletionException e) {
                        throw new Failure("the server could not " + what + ": "
                                + describe(e));
                    }
                    return true;
                }
                if (ticks >= SERVER_TICKS) {
                    throw new Failure("timed out after " + SERVER_TICKS
                            + " ticks waiting for the server to " + what);
                }
                return false;
            });
            return this;
        }

        /** A command from the server's own source, as Fabric's runCommand. */
        ScenarioBuilder command(String command) {
            return server("run /" + command, (server, player) -> server.getCommands()
                    .performPrefixedCommand(server.createCommandSourceStack(), command));
        }

        /** A command as the player (their position, dimension and @s). */
        ScenarioBuilder playerCommand(String command) {
            return server("run /" + command + " as the player", (server, player) ->
                    server.getCommands().performPrefixedCommand(
                            player.createCommandSourceStack(), command));
        }

        /**
         * A screenshot of the last rendered frame into screenshots/, waiting
         * for the callback (it runs on the IO pool, and also reports
         * failures), then checking it reported success and the file is there.
         * A failed shot is recorded and the scenario goes on.
         */
        ScenarioBuilder shot(String shotName) {
            String fileName = shotName + ".png";
            AtomicReference<Component> outcome = new AtomicReference<>();
            steps.add((mc, ticks) -> {
                String id = "shot:" + shotName;
                if (ticks == 0) {
                    outcome.set(null);
                    try {
                        Screenshot.grab(mc.gameDirectory, fileName,
                                mc.gameRenderer.mainRenderTarget(), 1,
                                message -> outcome.set(message != null ? message
                                        : Component.literal("(no message)")));
                    } catch (RuntimeException e) {
                        record(id, false, "Screenshot.grab threw " + describe(e));
                        return true;
                    }
                    return false;
                }
                Component message = outcome.get();
                if (message == null) {
                    if (ticks >= SHOT_TICKS) {
                        record(id, false, "no screenshot callback within " + SHOT_TICKS
                                + " ticks");
                        return true;
                    }
                    return false;
                }
                File file = new File(new File(mc.gameDirectory, "screenshots"), fileName);
                boolean success = message.getContents() instanceof TranslatableContents key
                        && "screenshot.success".equals(key.getKey());
                boolean exists = file.isFile() && file.length() > 0;
                record(id, success && exists, (success ? "" : "callback said \""
                        + message.getString() + "\"; ") + file.getName()
                        + (exists ? " (" + file.length() + " bytes)" : " MISSING"));
                return true;
            });
            return this;
        }
    }
}
