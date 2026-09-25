package com.deds.api.id;

/**
 * A namespaced identifier ({@code namespace:path}) owned by Ded's API.
 *
 * <p>Vanilla's identifier class has already been renamed once
 * ({@code ResourceLocation} → {@code Identifier} when Minecraft de-obfuscated),
 * which is exactly why mod code never touches the vanilla type directly.
 * Platform implementations convert {@link BId} to whatever vanilla currently
 * calls it.</p>
 */
public record BId(String namespace, String path) {

    public BId {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
    }

    /** Parses {@code "namespace:path"}. */
    public static BId of(String combined) {
        int i = combined.indexOf(':');
        if (i < 0) {
            throw new IllegalArgumentException(
                    "expected namespace:path, got " + combined);
        }
        return new BId(combined.substring(0, i), combined.substring(i + 1));
    }

    public static BId of(String namespace, String path) {
        return new BId(namespace, path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
