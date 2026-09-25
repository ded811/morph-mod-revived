package com.deds.morph;

/**
 * Holds the {@link MorphLoader} in force. A separate class only because an
 * interface cannot have a mutable static field; everything goes through
 * {@link MorphLoader#get()} and {@link MorphLoader#install}.
 *
 * <p>Volatile because the install happens on a mod-construction thread
 * (NeoForge constructs mods on a worker pool) and every read on the server
 * and client threads afterwards.</p>
 */
final class MorphLoaderSlot {

    /** Vanilla's behaviour: what runs on Fabric, which does not patch Minecraft. */
    static volatile MorphLoader current = new MorphLoader() {
    };

    private MorphLoaderSlot() {
    }
}
