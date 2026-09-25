package com.deds.morph.gametest.neoforge;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The NeoForge test mod's registrar: registers the shared server gametests
 * (and the NeoForge-only ones) with NeoForge's gametest server EXACTLY as
 * Fabric API's annotation locator registers them on Fabric, so the same test
 * code runs under the same ids with the same test data on both loaders.
 * NeoForge 26.x ships the registration events but no annotation locator.
 *
 * <p>Mirrored from Fabric's {@code TestAnnotationLocator} (fabric-gametest-api-v1
 * 4.0.21 / 4.0.32, identical but for the 26.3 dimension):
 * <ul>
 * <li>Classes in list order: {@code deds_morph_test/gametest-classes.txt}
 *     (shared; the census checkGameTestLists keeps it equal to the Fabric
 *     test mod's "fabric-gametest" entrypoints) and then
 *     {@code deds_morph_test/gametest-classes-neoforge.txt}.</li>
 * <li>ONE instance per class, created here during mod construction, as
 *     Fabric creates each entrypoint instance once during initialization (so
 *     static initializers, like {@code MorphWave9GameTests}' ability
 *     registration, run at the same stage).</li>
 * <li>Methods: {@code getDeclaredMethods()} carrying {@link GameTest}, then
 *     the superclass, recursively. Signatures are validated only for a class
 *     that does not implement {@link CustomTestMethodInvoker}.</li>
 * <li>Id: {@code deds_morph_test:} + camelToSnake(SimpleClassName + "_" +
 *     method), with Fabric's exact regex (digits never split, so
 *     {@code MorphWave10GameTests} becomes {@code morph_wave10game_tests_...}),
 *     used for both the test function and the test instance.</li>
 * <li>Invocation through the invoker when the class has one; an
 *     {@link InvocationTargetException} is unwrapped and a
 *     {@link RuntimeException} target rethrown as-is, so a failed assertion
 *     stays a gametest assertion failure rather than an "unknown" error.</li>
 * <li>Test data: every annotation field passed explicitly
 *     ({@link GameTestData}); the environment is looked up in the registry
 *     being loaded, as Fabric does, so the datapack environments
 *     ({@code deds_morph_test:night}, {@code :no_pvp}) resolve to the Holders
 *     vanilla batches by. NeoForge's event keeps that registry in a private
 *     field with no getter, so it is read by reflection (test code only;
 *     NeoForge's jar is an automatic module, open to reflection).</li>
 * </ul>
 *
 * <p>Unlike Fabric's locator, this one REFUSES to register nothing: a missing
 * or empty class list throws, and so does a listed class that yields no
 * {@link GameTest} method (Fabric only logs a warning for that; the list is
 * this project's own, so there is no Fabric behaviour to keep). A registrar
 * that silently registered nothing would otherwise leave a green run (see
 * neoforge/build.gradle, {@code --tests}). The count check after registration
 * compares the namespace's registry entries with the tests located here, so
 * it only catches test instances some datapack added to this namespace; the
 * independent count, that every listed class declares {@code @GameTest}
 * methods in the source each Minecraft version compiles, is the root census
 * checkGameTestLists, which runs before this on both loaders.</p>
 *
 * <p>Loaded by the dedicated-server gametest run too, where NeoForge's dev
 * launcher masks client classes: nothing here may touch a client class. A
 * client-side test driver belongs in a separate
 * {@code @Mod(value = "deds_morph_test", dist = Dist.CLIENT)} class.</p>
 */
@Mod(MorphNeoForgeGameTests.NAMESPACE)
public final class MorphNeoForgeGameTests {

    static final String NAMESPACE = "deds_morph_test";

    private static final Logger LOGGER = LoggerFactory.getLogger(MorphNeoForgeGameTests.class);

    private static final String SHARED_LIST = "deds_morph_test/gametest-classes.txt";
    private static final String NEOFORGE_LIST = "deds_morph_test/gametest-classes-neoforge.txt";

    /** One test method of one (shared) test class instance. */
    private record TestMethod(Object instance, Method method, GameTest test) {

        Identifier id() {
            return Identifier.fromNamespaceAndPath(NAMESPACE, camelToSnake(
                    instance.getClass().getSimpleName() + "_" + method.getName()));
        }

        /** Fabric's test function: the invoker if any, the unwrap rule. */
        Consumer<GameTestHelper> function() {
            return helper -> {
                try {
                    if (instance instanceof CustomTestMethodInvoker invoker) {
                        invoker.invokeTestMethod(helper, method);
                        return;
                    }
                    method.invoke(instance, helper);
                } catch (InvocationTargetException e) {
                    LOGGER.error("Failed to invoke test method", e);
                    if (e.getTargetException() instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new RuntimeException("Failed to invoke test method: "
                            + e.getMessage(), e);
                } catch (ReflectiveOperationException e) {
                    LOGGER.error("Failed to invoke test method", e);
                    throw new RuntimeException("Failed to invoke test method: "
                            + e.getMessage(), e);
                }
            };
        }
    }

    private final List<TestMethod> tests;

    public MorphNeoForgeGameTests(IEventBus modBus) {
        List<String> shared = readList(SHARED_LIST);
        if (shared.isEmpty()) {
            throw new IllegalStateException(NAMESPACE + ": " + SHARED_LIST
                    + " lists no test classes");
        }
        List<String> neoforgeOnly = readList(NEOFORGE_LIST);
        List<String> classes = new ArrayList<>(shared);
        classes.addAll(neoforgeOnly);
        this.tests = locate(classes);
        LOGGER.info("{}: {} gametests in {} classes ({} shared, {} NeoForge-only)",
                NAMESPACE, tests.size(), classes.size(), shared.size(),
                neoforgeOnly.size());

        modBus.addListener(RegisterEvent.class, this::registerFunctions);
        modBus.addListener(RegisterGameTestsEvent.class, this::registerTests);
    }

    // ------------------------------------------------------------------
    // the class lists
    // ------------------------------------------------------------------

    /**
     * One fully qualified class name per line; blank lines and '#' comments
     * skipped, the same rule the root census checkGameTestLists reads by.
     */
    private static List<String> readList(String resource) {
        InputStream in = MorphNeoForgeGameTests.class.getResourceAsStream("/" + resource);
        if (in == null) {
            in = MorphNeoForgeGameTests.class.getClassLoader().getResourceAsStream(resource);
        }
        if (in == null) {
            throw new IllegalStateException(NAMESPACE + ": the gametest class list "
                    + resource + " is missing from the test mod");
        }
        List<String> names = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String name = line.trim();
                if (!name.isEmpty() && !name.startsWith("#")) {
                    names.add(name);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(NAMESPACE + ": could not read " + resource, e);
        }
        return names;
    }

    // ------------------------------------------------------------------
    // Fabric's TestAnnotationLocator, mirrored
    // ------------------------------------------------------------------

    private static List<TestMethod> locate(List<String> classNames) {
        List<TestMethod> found = new ArrayList<>();
        for (String name : classNames) {
            Object instance;
            try {
                Class<?> testClass = Class.forName(name, true,
                        MorphNeoForgeGameTests.class.getClassLoader());
                instance = testClass.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(NAMESPACE + ": could not create the "
                        + "gametest class " + name, e);
            }
            List<TestMethod> methods = new ArrayList<>();
            findTestMethods(instance, instance.getClass(), methods);
            if (methods.isEmpty()) {
                throw new IllegalStateException(NAMESPACE + ": no methods with the "
                        + "GameTest annotation were found in the listed gametest class "
                        + instance.getClass().getName());
            }
            found.addAll(methods);
        }
        return found;
    }

    private static void findTestMethods(Object instance, Class<?> testClass,
            List<TestMethod> methods) {
        for (Method method : testClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(GameTest.class)) {
                if (!CustomTestMethodInvoker.class.isAssignableFrom(testClass)) {
                    // Only validated for the default reflection invoker.
                    validate(method);
                }
                methods.add(new TestMethod(instance, method,
                        method.getAnnotation(GameTest.class)));
            }
        }
        if (testClass.getSuperclass() != null) {
            findTestMethods(instance, testClass.getSuperclass(), methods);
        }
    }

    private static void validate(Method method) {
        List<String> issues = new ArrayList<>();
        if (method.getParameterCount() != 1
                || method.getParameterTypes()[0] != GameTestHelper.class) {
            issues.add("must have a single parameter of type GameTestHelper");
        }
        if (!Modifier.isPublic(method.getModifiers())) {
            issues.add("must be public");
        }
        if (Modifier.isStatic(method.getModifiers())) {
            issues.add("must not be static");
        }
        if (method.getReturnType() != void.class) {
            issues.add("must return void");
        }
        if (!issues.isEmpty()) {
            throw new UnsupportedOperationException("Test method (%s) has the following issues: %s"
                    .formatted(method.getDeclaringClass().getName() + "#" + method.getName(),
                            String.join(", ", issues)));
        }
    }

    private static String camelToSnake(String input) {
        return input.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // registration
    // ------------------------------------------------------------------

    /** The test functions, into the built-in TEST_FUNCTION registry. */
    private void registerFunctions(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, helper -> {
            for (TestMethod test : tests) {
                helper.register(test.id(), test.function());
            }
        });
    }

    /**
     * The test instances, into the test-instance registry being loaded (after
     * every datapack JSON, before the registries freeze), then checked: every
     * test the listed classes declare must now be there, and nothing else in
     * this namespace.
     */
    @SuppressWarnings("unchecked")
    private void registerTests(RegisterGameTestsEvent event) {
        Registry<TestEnvironmentDefinition<?>> environments =
                (Registry<TestEnvironmentDefinition<?>>) privateField(event, "environmentsRegistry");
        for (TestMethod test : tests) {
            Holder<TestEnvironmentDefinition<?>> environment = environments.getOrThrow(
                    ResourceKey.create(Registries.TEST_ENVIRONMENT,
                            Identifier.parse(test.test().environment())));
            Identifier id = test.id();
            event.registerTest(id, new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, id),
                    GameTestData.of(test.test(), environment)));
        }
        Registry<GameTestInstance> instances =
                (Registry<GameTestInstance>) privateField(event, "testsRegistry");
        long registered = instances.keySet().stream()
                .filter(id -> id.getNamespace().equals(NAMESPACE))
                .count();
        if (registered != tests.size()) {
            throw new IllegalStateException(NAMESPACE + ": the listed classes declare "
                    + tests.size() + " gametests but " + registered
                    + " test instances are registered in this namespace");
        }
        LOGGER.info("{}: registered {} gametests", NAMESPACE, registered);
    }

    private static Object privateField(RegisterGameTestsEvent event, String name) {
        try {
            Field field = RegisterGameTestsEvent.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(event);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(NAMESPACE + ": cannot read "
                    + "RegisterGameTestsEvent." + name + " (did NeoForge rename it?)", e);
        }
    }
}
