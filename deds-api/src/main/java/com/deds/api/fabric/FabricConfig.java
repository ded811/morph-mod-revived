package com.deds.api.fabric;

import com.deds.api.Deds;
import com.deds.api.config.ConfigHandle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

/**
 * Fabric implementation of {@link ConfigHandle}: a tiny Codec-backed JSON
 * file at {@code <configDir>/<modId>/<name>.json}. Gson + {@link JsonOps}
 * bridge the codec to pretty JSON. Reads default on absence/parse error (the
 * originals' forgiving config defaulting); writes atomically (temp file +
 * move) so a crash mid-write never truncates the live file.
 *
 * <p>API-side only, so {@code com.mojang.serialization.*} and Gson stay out
 * of mod code — mods just define a record + {@link Codec} and call
 * {@link com.deds.api.config.ConfigRegistrar#register}.</p>
 */
final class FabricConfig<C> implements ConfigHandle<C> {

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private final Codec<C> codec;
    private final Supplier<C> defaults;
    private final Logger logger;
    private volatile C value;

    private FabricConfig(Path path, Codec<C> codec, Supplier<C> defaults,
            Logger logger) {
        this.path = path;
        this.codec = codec;
        this.defaults = defaults;
        this.logger = logger;
    }

    static <C> FabricConfig<C> load(String modId, String name, Codec<C> codec,
            Supplier<C> defaults, Logger logger) {
        Path path = Deds.platform().configDir()
                .resolve(modId).resolve(name + ".json");
        FabricConfig<C> handle =
                new FabricConfig<>(path, codec, defaults, logger);
        handle.reload();
        return handle;
    }

    @Override
    public C get() {
        return value;
    }

    @Override
    public void set(C value) {
        this.value = value;
        save(value);
    }

    @Override
    public void reload() {
        if (Files.isRegularFile(path)) {
            try (Reader reader =
                    Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement json = GSON.fromJson(reader, JsonElement.class);
                this.value = codec.parse(JsonOps.INSTANCE, json).result()
                        .orElseGet(() -> {
                            logger.warn("config {} did not parse cleanly — "
                                    + "using defaults", path);
                            return defaults.get();
                        });
                return;
            } catch (Exception e) {
                logger.warn("failed to read config {} — using defaults",
                        path, e);
            }
        }
        // Absent or unreadable: adopt defaults and materialize the file so the
        // user has a template to edit.
        C def = defaults.get();
        this.value = def;
        save(def);
    }

    private void save(C value) {
        JsonElement json = codec.encodeStart(JsonOps.INSTANCE, value)
                .result().orElse(null);
        if (json == null) {
            logger.warn("config {} did not encode — not written", path);
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer =
                    Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException noAtomic) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.warn("failed to write config {}", path, e);
        }
    }
}
