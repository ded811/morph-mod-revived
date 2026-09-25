package com.deds.api.registry;

/**
 * Loader- and version-independent item construction settings.
 * See {@link BlockSettings} for the rationale.
 */
public final class ItemSettings {

    private int maxStackSize = 64;

    private ItemSettings() {
    }

    public static ItemSettings create() {
        return new ItemSettings();
    }

    public ItemSettings maxStackSize(int size) {
        this.maxStackSize = size;
        return this;
    }

    public int maxStackSizeValue() {
        return maxStackSize;
    }
}
