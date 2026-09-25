package com.deds.morph.api;

import com.deds.api.id.BId;

import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where another mod contributes a morph {@link Ability} (wave-9 item 1).
 *
 * <p><b>Original.</b> {@code morph/common/ability/AbilityHandler.java:37-39}
 * held the registry ({@code abilityMap} Class→List, {@code stringToClassMap}
 * String→Class), and {@code morph/api/Ability.java:125-176} exposed
 * {@code registerAbility}/{@code mapAbilities}/{@code removeAbility}/
 * {@code hasAbility} across the reflection trampoline. {@code mapAbilities}
 * overwrote same-type entries in place and auto-registered unknown types with a
 * console warning ({@code AbilityHandler.java:105-109}).</p>
 *
 * <p><b>Ours is smaller by one whole concept.</b> The original had to MAP
 * abilities onto entity CLASSES because its ability set was a 24-class table
 * ({@code AbilityHandler.java:56-78}) with superclass inheritance
 * ({@code :156-176}). We derive abilities from a morph's real 26.2 properties,
 * so a contributor supplies the predicate itself
 * ({@link Ability#appliesTo}) and there is no map, no class keys, no
 * inheritance walk and no {@code removeAbility} — one method registers, one
 * reads.</p>
 *
 * <p><b>Call it from your mod's initializer.</b> Registration is not
 * thread-safe by design (initializers are single-threaded) and takes effect for
 * every morph variant probed afterwards; morph profiles are cached per variant,
 * so registering after a variant has already been probed will not retro-apply to
 * that variant until the cache is rebuilt.</p>
 *
 * <p>Additive-only: this class only ever gains methods.</p>
 */
public final class AbilityRegistry {

    private static final Map<BId, Ability> REGISTERED = new LinkedHashMap<>();

    private AbilityRegistry() {
    }

    /**
     * Registers a third-party ability. Re-registering the same {@link Ability#id}
     * REPLACES the previous entry (the original's "only one ability of the same
     * type per entity; this overwrites" rule, {@code Ability.java:133-141}).
     *
     * @return the ability, for chaining
     * @throws IllegalArgumentException if {@code ability} or its id is null
     */
    public static Ability register(Ability ability) {
        if (ability == null || ability.id() == null) {
            throw new IllegalArgumentException(
                    "a morph Ability and its id() must not be null");
        }
        REGISTERED.put(ability.id(), ability);
        return ability;
    }

    /** Every registered third-party ability, in registration order. */
    public static List<Ability> registered() {
        return List.copyOf(REGISTERED.values());
    }

    /** The registered ability with this id, or null. */
    public static Ability get(BId id) {
        return REGISTERED.get(id);
    }

    /**
     * The registered abilities that apply to a morph of {@code dummy} — the
     * union Morph adds to the built-in {@link com.deds.morph.MorphAbility} set.
     * A contributor whose {@code appliesTo} throws is skipped, never fatal.
     */
    public static List<Ability> resolve(LivingEntity dummy) {
        if (REGISTERED.isEmpty()) {
            return List.of();
        }
        List<Ability> out = new ArrayList<>();
        for (Ability ability : REGISTERED.values()) {
            try {
                if (ability.appliesTo(dummy)) {
                    out.add(ability);
                }
            } catch (Exception broken) {
                com.deds.api.Deds.LOGGER.warn(
                        "[deds_morph] ability {} threw from appliesTo; skipped",
                        ability.id(), broken);
            }
        }
        return List.copyOf(out);
    }
}
