package com.deds.api.entity;

import com.deds.api.registry.RegistryHandle;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;

import java.util.function.Supplier;

/**
 * Entities for one mod (Ded's API v2.3) — {@code ModContext.entities()}.
 *
 * <p><b>Deliberately three methods.</b> Register a type, register a type whose
 * class is alive, and put an instance into a world. That is the whole seam.
 * It is <b>not</b> an entity framework: the {@link Entity} subclass, its
 * {@code SynchedEntityData} accessors, its AI goals, its NBT and its
 * {@code tick()} are ordinary vanilla surface that mod code uses directly
 * (docs/ARCHITECTURE.md, "Level 1 pragmatism"). What goes through the API is
 * the churn-prone, loader-specific part: how a type is <em>built and
 * registered</em>, and — for a living one — where its default attributes go,
 * which has no vanilla registration path at all.</p>
 *
 * <p>The client half — binding a renderer to a registered type — is
 * {@link EntityRenderers}, which is client-only and therefore deliberately not
 * on this interface: this one is reached through {@code ModContext}, which a
 * dedicated server holds too. Same split as {@code MenuRegistrar} /
 * {@code MenuScreens} in v2.2.</p>
 *
 * <h2>What is deliberately NOT here</h2>
 *
 * <p>Each of these was measured against the entity types this seam was built
 * for — row 2 of {@code docs/specs/openblocks/SPEC.md}'s API bill, whose
 * originals are the ten {@code EntityRegistry.registerModEntity} calls at
 * {@code OpenBlocks.java:363-387} — and left out because none of them needs
 * it. Adding an overload later is additive (docs/API-COMPATIBILITY.md §4);
 * removing an unused one is not.</p>
 *
 * <ul>
 * <li><b>No {@code MobCategory} parameter.</b> Every type registered here is
 *     {@code MobCategory.MISC}, and that is the correct answer rather than a
 *     shortcut. The category drives natural spawning and despawning, and none
 *     of these entities spawns naturally — the 1.6.4 originals went through
 *     {@code EntityRegistry.registerModEntity}, which put them outside the
 *     spawn system entirely. {@code MISC} is declared
 *     {@code (max = -1, friendly = true, persistent = true, despawn = 128)}
 *     (javap of {@code MobCategory}'s class initializer), and
 *     {@code isPersistent() == true} is what stops {@code Mob.checkDespawn}
 *     from quietly deleting a player's Luggage while they are away.</li>
 * <li><b>No tracking-range or update-interval knobs — and the interval is
 *     deliberately not vanilla's.</b> All ten originals asked for a 64-block
 *     tracking range; eight of them asked for a position update <em>every
 *     tick</em> and two (the Golden Eye and the Cartographer, which is not
 *     being ported) for every 8. This seam takes vanilla's tracking range of
 *     5 chunks = 80 blocks, which is <em>wider</em> than 64 and so costs
 *     nothing. It does <b>not</b> take vanilla's default update interval of 3
 *     (javap, {@code EntityType.Builder}'s constructor), because 3 is
 *     <em>slower</em> than what eight of the ten asked for, and these are
 *     small entities that ride along with a moving player — the Crane's
 *     magnet and its carried block are attached to one. It uses 1, a value
 *     vanilla itself uses for three of its own types (javap of
 *     {@code EntityTypes}), so every ported type matches its original except
 *     the Golden Eye, which gets more updates than it asked for rather than
 *     fewer.</li>
 * <li><b>No velocity-tracking knob.</b> The originals passed
 *     {@code sendsVelocityUpdates = true}. On 26.2 {@code trackDeltas()} is a
 *     hard-coded blacklist of nine vanilla types (javap), so <em>every</em>
 *     modded type already gets velocity updates and there is nothing to
 *     ask for.</li>
 * <li><b>No spawn-egg helper and no {@code noSummon}.</b> No consumer wants
 *     an egg, and types stay {@code /summon}-able, which is what makes them
 *     testable. See {@link #register} for the obligation that puts on the
 *     factory.</li>
 * </ul>
 */
public interface EntityRegistrar {

    /**
     * Registers an entity type in the mod's namespace.
     *
     * <p>The factory is vanilla's own {@link EntityType.EntityFactory} rather
     * than an interface of ours — unlike {@code BlockEntityRegistrar.Factory}
     * and {@code MenuRegistrar.Factory}, which exist because the vanilla
     * shapes behind them are a loader builder and a private constructor
     * respectively. This one is a plain public
     * {@code (EntityType<T>, Level) -> T}, it is exactly the signature the
     * entity's own constructor must already have, and re-declaring it would
     * buy nothing. A constructor reference like {@code LuggageEntity::new}
     * fits it.</p>
     *
     * <p><b>The factory must tolerate being called with nothing else set.</b>
     * It runs when a saved entity is loaded, when the client is told an entity
     * appeared, and when someone types {@code /summon}. Any state the entity
     * normally receives from its own richer constructor — an owner, a carried
     * stack, a target — will be absent on those paths. Build a dormant entity
     * rather than throwing; the same rule as
     * {@code MenuRegistrar.Factory}'s "the client run has no block entity to
     * trust".</p>
     *
     * <p>{@code width} and {@code height} are the entity's collision box in
     * blocks, passed straight to vanilla's {@code EntityType.Builder.sized},
     * which reads them as {@code EntityDimensions.scalable} (javap) — so they
     * are the size at scale 1 and follow {@code getScale()} thereafter. They
     * are required rather than defaulted because vanilla's default is
     * 0.6 × 1.8 — a player — and almost nothing here is player-shaped:
     * 0.5 × 0.5 for the Luggage and the Magnet, 0.6 × 0.95 for MiniMe,
     * 0.925 × 0.925 for the crane's carried block and 0.02 × 0.02 for the
     * Golden Eye (the {@code setSize} calls in each 1.6.4 class).</p>
     *
     * <p>Call during {@code DedsMod.onInitialize}, on <b>both</b> sides —
     * registration must be symmetric, like {@code ModContext.net()}'s
     * messages, because the client looks the type up by id when the spawn
     * packet arrives.</p>
     *
     * @param name    registry path within the mod's namespace
     * @param factory builds the entity (usually a constructor reference)
     * @param width   collision-box width in blocks
     * @param height  collision-box height in blocks
     * @param <T>     the entity class
     * @return handle to the registered type — needed by the entity's super
     *         constructor and by {@link EntityRenderers#register}
     * @see #registerLiving for anything extending {@code LivingEntity}
     */
    <T extends Entity> RegistryHandle<EntityType<T>> register(String name,
            EntityType.EntityFactory<T> factory, float width, float height);

    /**
     * Registers an entity type whose class extends {@link LivingEntity}, and
     * its default attributes.
     *
     * <p><b>Living entities need a second registration and it is not
     * optional.</b> {@code LivingEntity}'s constructor builds its
     * {@code AttributeMap} from {@code DefaultAttributes.getSupplier(type)},
     * which is a bare {@code Map.get} returning {@code null} for a type nobody
     * registered (javap) — so the entity constructs and then throws a
     * {@code NullPointerException} the first time anything reads an
     * attribute, typically inside {@code setHealth} in its own constructor.
     * There is <b>no vanilla way to add to that map</b>; the registry is
     * populated by a static initializer and the only public route is the
     * loader's, which is why this method exists at all rather than mod code
     * doing it. Splitting it into a second call the caller could forget would
     * be an invitation to that crash, so the attributes are a parameter.</p>
     *
     * <p>Note that this failure is <em>not</em> caught by vanilla's
     * {@code DefaultAttributes.validate()} startup sweep: that sweep skips
     * every {@code MobCategory.MISC} type (javap), and every type this
     * registrar creates is MISC. The crash arrives at first spawn instead.</p>
     *
     * <p>The supplier is normally {@code Mob::createMobAttributes} with
     * additions — e.g.
     * {@code () -> Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 10.0)
     * .add(Attributes.MOVEMENT_SPEED, 0.3)}. It is a {@link Supplier} rather
     * than a built value because {@code AttributeSupplier.Builder} names
     * {@code Attributes} holders, and deferring the call keeps this
     * registration free of any assumption about registry-load order.</p>
     *
     * <p>Everything {@link #register} says about the factory, the dimensions
     * and calling on both sides applies here unchanged.</p>
     *
     * @param name       registry path within the mod's namespace
     * @param factory    builds the entity (usually a constructor reference)
     * @param width      collision-box width in blocks
     * @param height     collision-box height in blocks
     * @param attributes the type's default attributes; called once, during
     *                   this call
     * @param <T>        the entity class
     * @return handle to the registered type
     */
    <T extends LivingEntity> RegistryHandle<EntityType<T>> registerLiving(
            String name, EntityType.EntityFactory<T> factory, float width,
            float height, Supplier<AttributeSupplier.Builder> attributes);

    /**
     * Places an already-constructed {@code entity} at {@code (x, y, z)} in
     * {@code level} and adds it to the world.
     *
     * <p><b>Server side only, and the {@link ServerLevel} in the signature is
     * load-bearing rather than documentation.</b> Vanilla declares
     * {@code addFreshEntity} on {@code LevelWriter} as a {@code default} whose
     * entire body is {@code return false} — {@code ServerLevel} overrides it,
     * {@code ClientLevel} does not (javap). So the same call written against a
     * plain {@code Level} reference compiles, links, and on the client
     * <b>silently does nothing</b> while returning a {@code false} nobody
     * checks. Demanding a {@code ServerLevel} turns that into a compile error
     * (docs/CLIENT-SERVER-PLAYBOOK.md). A client never creates a modded
     * entity; it is told about one.</p>
     *
     * <p>The entity is constructed by the caller, not by this method, because
     * that is what the real spawn sites need — a magnet knows its owner and a
     * projectile knows its stack, and neither can be expressed through the
     * two-argument factory. Build it, set whatever it needs (including
     * {@code setDeltaMovement}), then call this.</p>
     *
     * <p><b>Why the position is a parameter instead of your own
     * {@code setPos}.</b> This places the entity with vanilla's
     * {@code Entity.snapTo}, which sets the position <em>and</em> calls
     * {@code setOldPosAndRot()} (javap). A plain {@code setPos} leaves
     * {@code xOld/yOld/zOld} at the origin, and those are exactly the fields
     * every observing client interpolates from
     * (docs/CLIENT-SERVER-PLAYBOOK.md §2) — the entity's first frame is then a
     * streak from world origin to wherever it really is. Yaw and pitch are
     * left as the caller set them.</p>
     *
     * @param level  the world to spawn into
     * @param entity the instance
     * @param x      spawn x
     * @param y      spawn y (the entity's feet, as everywhere in vanilla)
     * @param z      spawn z
     * @return {@code true} if the world accepted it — vanilla's own
     *         {@code addFreshEntity} result, which is {@code false} for a
     *         duplicate UUID or an entity the level refused
     */
    boolean spawn(ServerLevel level, Entity entity, double x, double y,
            double z);
}
