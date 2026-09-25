package com.deds.api.internal;

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
 * The {@link ConfigHandle} every loader backend hands out: a tiny Codec-backed
 * JSON file at {@code <configDir>/<modId>/<name>.json}, the config directory
 * coming from the active {@link com.deds.api.platform.Platform}. Gson + {@link JsonOps}
 * bridge the codec to pretty JSON. Reads default on absence/parse error (the
 * originals' forgiving config defaulting); writes atomically (temp file +
 * move) so a crash mid-write never truncates the live file.
 *
 * <p>API-side only, so {@code com.mojang.serialization.*} and Gson stay out
 * of mod code — mods just define a record + {@link Codec} and call
 * {@link com.deds.api.config.ConfigRegistrar#register}.</p>
 *
 * <p>It names no loader, so it is shared, and every loader backend hands out
 * the same file with the same behaviour. It used to be the Fabric backend's
 * package-private {@code FabricConfig} and is that class unchanged apart from
 * its name, its package and the public visibility a backend in another
 * package needs: same file paths, same {@code .bak} copy of a file that
 * failed to load, same {@code .tmp} write-then-move. Not API: mods reach it
 * only through {@link com.deds.api.config.ConfigRegistrar}.</p>
 */
public final class JsonConfigFile<C> implements ConfigHandle<C> {

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private final Codec<C> codec;
    private final Supplier<C> defaults;
    private final Logger logger;
    private volatile C value;

    private JsonConfigFile(Path path, Codec<C> codec, Supplier<C> defaults,
            Logger logger) {
        this.path = path;
        this.codec = codec;
        this.defaults = defaults;
        this.logger = logger;
    }

    public static <C> JsonConfigFile<C> load(String modId, String name,
            Codec<C> codec, Supplier<C> defaults, Logger logger) {
        Path path = Deds.platform().configDir()
                .resolve(modId).resolve(name + ".json");
        JsonConfigFile<C> handle =
                new JsonConfigFile<>(path, codec, defaults, logger);
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
                            backUp();
                            return defaults.get();
                        });
                return;
            } catch (Exception e) {
                logger.warn("failed to read config {} — using defaults",
                        path, e);
                if (!backUp()) {
                    this.value = defaults.get(); // keep the user's file untouched
                    return;
                }
            }
        }
        // Absent or unreadable: adopt defaults and materialize the file so the
        // user has a template to edit.
        C def = defaults.get();
        this.value = def;
        save(def);
    }

    /**
     * Keeps a copy of a config file that failed to load, as {@code <name>.json.bak},
     * before anything can overwrite it: the defaults written back (or a later
     * command's save) would otherwise throw away every setting the user wrote.
     */
    private boolean backUp() {
        try {
            Path bak = path.resolveSibling(path.getFileName() + ".bak");
            Files.copy(path, bak, StandardCopyOption.REPLACE_EXISTING);
            logger.warn("kept the unreadable config as {}", bak);
            return true;
        } catch (IOException e) {
            logger.warn("could not back up config {}", path, e);
            return false;
        }
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
