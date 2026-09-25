package com.deds.api.registry;

/**
 * Loader- and version-independent block construction settings.
 *
 * <p>This is the API-owned mirror of vanilla's block "properties/settings"
 * object (another class with a history of renames and constructor churn).
 * Mods describe what they want here; the platform implementation translates
 * to whatever vanilla wants this year. Add fields as mods need them — adding
 * here is cheap, letting vanilla types leak into 40 mods is not.</p>
 */
public final class BlockSettings {

    private float hardness = 1.5f;
    private float resistance = 6.0f;
    private boolean noCollision = false;
    private boolean nonOpaque = false;
    private boolean requiresTool = false;
    private int lightLevel = 0;
    private SoundGroup soundGroup = SoundGroup.STONE;
    private int flameEncouragement = 0;
    private int flammability = 0;

    /** Common block sound sets, mapped to vanilla sound types by the platform. */
    public enum SoundGroup { STONE, WOOD, METAL, GLASS, WOOL, GRASS, SAND, LADDER }

    private BlockSettings() {
    }

    public static BlockSettings create() {
        return new BlockSettings();
    }

    /** Convenience: hardness + blast resistance in one call (vanilla idiom). */
    public BlockSettings strength(float hardness, float resistance) {
        this.hardness = hardness;
        this.resistance = resistance;
        return this;
    }

    /** Entities pass straight through (Secret Rooms ghost block, etc.). */
    public BlockSettings noCollision() {
        this.noCollision = true;
        return this;
    }

    /** Not a full light-blocking cube (glass, torches, ...). */
    public BlockSettings nonOpaque() {
        this.nonOpaque = true;
        return this;
    }

    public BlockSettings requiresTool() {
        this.requiresTool = true;
        return this;
    }

    /** Emitted light, 0-15. */
    public BlockSettings lightLevel(int level) {
        this.lightLevel = level;
        return this;
    }

    public BlockSettings sounds(SoundGroup group) {
        this.soundGroup = group;
        return this;
    }

    /**
     * Makes the block catch and spread fire like a vanilla flammable block
     * (Ded's API v1.5). Both numbers are the vanilla fire odds:
     * {@code encouragement} is how readily fire spreads TO this block from a
     * neighbour (vanilla's "ignite odds"), {@code flammability} how readily it
     * burns away once alight (vanilla's "burn odds"). Oak planks are
     * {@code (5, 20)}.
     *
     * <p>Wrapped because vanilla has no public setter — {@code FireBlock}'s is
     * private and the loader-side registry ({@code FlammableBlockRegistry} on
     * Fabric) is {@code net.fabricmc.*}, which mod code may not import.
     * Registration happens right after the block itself is registered.</p>
     *
     * @param encouragement ignite odds, 0 = never catches fire
     * @param flammability  burn odds, 0 = never burns away
     */
    public BlockSettings flammable(int encouragement, int flammability) {
        this.flameEncouragement = encouragement;
        this.flammability = flammability;
        return this;
    }

    // --- read side (platform implementations) ---

    public float hardness() {
        return hardness;
    }

    public float resistance() {
        return resistance;
    }

    public boolean isNoCollision() {
        return noCollision;
    }

    public boolean isNonOpaque() {
        return nonOpaque;
    }

    public boolean isRequiresTool() {
        return requiresTool;
    }

    public int lightLevelValue() {
        return lightLevel;
    }

    public SoundGroup soundGroupValue() {
        return soundGroup;
    }

    /** Fire "ignite odds"; 0 means the block never catches fire. */
    public int flameEncouragementValue() {
        return flameEncouragement;
    }

    /** Fire "burn odds"; 0 means the block never burns away. */
    public int flammabilityValue() {
        return flammability;
    }
}
