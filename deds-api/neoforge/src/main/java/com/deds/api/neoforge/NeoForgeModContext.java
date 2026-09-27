package com.deds.api.neoforge;

import com.deds.api.ModContext;
import com.deds.api.attach.PlayerDataKey;
import com.deds.api.attach.PlayerDataRegistrar;
import com.deds.api.attach.PlayerDataSpec;
import com.deds.api.command.CommandRegistrar;
import com.deds.api.config.ConfigHandle;
import com.deds.api.config.ConfigRegistrar;
import com.deds.api.energy.EnergyRegistrar;
import com.deds.api.entity.EntityRegistrar;
import com.deds.api.fluid.FluidRegistrar;
import com.deds.api.id.BId;
import com.deds.api.internal.JsonConfigFile;
import com.deds.api.menu.MenuRegistrar;
import com.deds.api.net.MessageType;
import com.deds.api.net.NetRegistrar;
import com.deds.api.neoforge.mixin.FireBlockInvoker;
import com.deds.api.neoforge.transfer.NeoEnergy;
import com.deds.api.neoforge.transfer.NeoFluids;
import com.deds.api.registry.BlockEntityRegistrar;
import com.deds.api.registry.BlockRegistrar;
import com.deds.api.registry.BlockSettings;
import com.deds.api.registry.EffectRegistrar;
import com.deds.api.registry.ItemRegistrar;
import com.deds.api.registry.ItemSettings;
import com.deds.api.registry.RegistryHandle;
import com.deds.api.registry.TabRegistrar;
import com.deds.api.worldgen.WorldgenRegistrar;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentSyncHandler;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Per-mod context on NeoForge, the counterpart of {@code FabricModContext}.
 *
 * <p>Everything registers IMMEDIATELY, exactly as on Fabric, because
 * {@code onInitialize} runs inside NeoForge's registry window (see
 * {@link NeoForgePlatform}): every registry is writable there, so each
 * registrar is the Fabric body with the loader calls swapped, and every handle
 * wraps the live instance. A registration attempted outside that window (from
 * a mod constructor, or after loading) fails with a message saying so,
 * instead of vanilla's "Registry is already frozen".</p>
 *
 * <p>The seams that NeoForge serves through its own events rather than a
 * registry (capabilities, payloads, default attributes, vanilla creative tabs,
 * fluid types) queue here and are handed over by {@link DedsApiNeoForge}'s
 * listeners, which serve every Ded's mod at once.</p>
 *
 * <p>Thread note: {@code onInitialize}, and so every call here, runs on FML's
 * single mod-loading thread; the static tables are still concurrent or
 * guarded, because a misbehaving mod may call from its constructor, which FML
 * runs in parallel.</p>
 */
final class NeoForgeModContext implements ModContext, BlockRegistrar,
        ItemRegistrar, EffectRegistrar, TabRegistrar, BlockEntityRegistrar,
        PlayerDataRegistrar, NetRegistrar, CommandRegistrar, ConfigRegistrar,
        EntityRegistrar {

    /**
     * True while NeoForge's registry window is open: set by
     * {@link DedsApiNeoForge} at the start of the first registry event and
     * cleared at common setup, after the registries froze.
     */
    static volatile boolean registryWindowOpen;

    /**
     * Every TARGET_ONLY player-data type of every Ded's mod, for the owner
     * resync after an initial sync (see {@link #resyncOwnerOnlyData}).
     */
    private static final List<AttachmentType<?>> TARGET_ONLY_TYPES =
            new CopyOnWriteArrayList<>();

    /**
     * Default attributes of every living entity type registered through
     * {@code registerLiving}, handed to NeoForge by
     * {@link #putDefaultAttributes}. Guarded by itself.
     */
    private static final Map<EntityType<? extends LivingEntity>, AttributeSupplier>
            DEFAULT_ATTRIBUTES = new LinkedHashMap<>();

    /**
     * The first item registered for each block through
     * {@link #registerBlockItem}, so a later item for the same block cannot
     * steal {@code Block.asItem()}; see the Fabric context for why a plain
     * putIfAbsent is not enough (NeoForge's item callbacks overwrite
     * {@code Item.BY_BLOCK} last-wins, exactly as Fabric's tracker does).
     */
    private static final Map<Block, Item> FIRST_ITEM_FOR_BLOCK = new HashMap<>();

    private final String modId;
    private final Logger logger;
    private final NeoFluids fluids;
    private final NeoEnergy energy;
    private final NeoMenus menus;

    NeoForgeModContext(String modId, IEventBus modBus) {
        this.modId = modId;
        // The same logger name the Fabric context uses, so a mod's log lines
        // (and JsonConfigFile's warnings) read identically on both loaders.
        this.logger = LoggerFactory.getLogger(modId);
        this.fluids = new NeoFluids(modId, this::requireRegistryWindow);
        this.energy = new NeoEnergy();
        this.menus = new NeoMenus(modId, this::requireRegistryWindow);
    }

    /**
     * Throws, with a message naming the mod and the rule, unless NeoForge's
     * registry window is open.
     */
    void requireRegistryWindow(String what) {
        if (!registryWindowOpen) {
            throw new IllegalStateException("Ded's API: " + what + " for mod '"
                    + modId + "' can only be registered from DedsMod"
                    + ".onInitialize (NeoForge allows registration only while "
                    + "its registry events run)");
        }
    }

    private Identifier ident(String name) {
        return Identifier.fromNamespaceAndPath(modId, name);
    }

    private record Handle<T>(BId id, T value) implements RegistryHandle<T> {
        @Override
        public T get() {
            return value;
        }
    }

    // --- ModContext ---

    @Override
    public String modId() {
        return modId;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public BlockRegistrar blocks() {
        return this;
    }

    @Override
    public ItemRegistrar items() {
        return this;
    }

    @Override
    public EffectRegistrar effects() {
        return this;
    }

    @Override
    public TabRegistrar tabs() {
        return this;
    }

    @Override
    public BlockEntityRegistrar blockEntities() {
        return this;
    }

    @Override
    public PlayerDataRegistrar playerData() {
        return this;
    }

    @Override
    public NetRegistrar net() {
        return this;
    }

    @Override
    public CommandRegistrar commands() {
        return this;
    }

    @Override
    public ConfigRegistrar config() {
        return this;
    }

    @Override
    public FluidRegistrar fluids() {
        return fluids;
    }

    @Override
    public MenuRegistrar menus() {
        return menus;
    }

    @Override
    public EntityRegistrar entities() {
        return this;
    }

    @Override
    public EnergyRegistrar energy() {
        return energy;
    }

    /**
     * The worldgen seam: the placed feature joins Ded's API's one biome
     * modifier ({@link DedsOverworldOres}), which NeoForge applies to every
     * server's biomes before its first chunk. Like Fabric's
     * {@code BiomeModifications.addFeature}, a call is not a registry write,
     * so it is not limited to the registry window.
     */
    @Override
    public WorldgenRegistrar worldgen() {
        return placedFeatureName -> DedsOverworldOres.add(ResourceKey.create(
                Registries.PLACED_FEATURE, ident(placedFeatureName)));
    }

    private static BlockBehaviour.Properties toVanilla(BlockSettings s) {
        BlockBehaviour.Properties p = BlockBehaviour.Properties.of()
                .strength(s.hardness(), s.resistance());
        if (s.isNoCollision()) {
            p = p.noCollision();
        }
        if (s.isNonOpaque()) {
            p = p.noOcclusion();
        }
        if (s.isRequiresTool()) {
            p = p.requiresCorrectToolForDrops();
        }
        if (s.lightLevelValue() > 0) {
            int level = s.lightLevelValue();
            p = p.lightLevel(state -> level);
        }
        p = p.sound(switch (s.soundGroupValue()) {
            case STONE -> SoundType.STONE;
            case WOOD -> SoundType.WOOD;
            case METAL -> SoundType.METAL;
            case GLASS -> SoundType.GLASS;
            case WOOL -> SoundType.WOOL;
            case GRASS -> SoundType.GRASS;
            case SAND -> SoundType.SAND;
            case LADDER -> SoundType.LADDER;
        });
        return p;
    }

    // --- BlockRegistrar ---

    @Override
    public RegistryHandle<Block> register(String name, BlockSettings settings) {
        return register(name, settings, Block::new);
    }

    @Override
    public RegistryHandle<Block> register(String name, BlockSettings settings,
            Function<BlockBehaviour.Properties, ? extends Block> factory) {
        requireRegistryWindow("block '" + name + "'");
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, ident(name));
        Block block = factory.apply(toVanilla(settings).setId(key));
        Registry.register(BuiltInRegistries.BLOCK, key, block);
        // BlockSettings.flammable: the same vanilla fire tables Fabric's
        // FlammableBlockRegistry feeds, written through vanilla's own private
        // setter (NeoForge's IBlockExtension.getFlammability and
        // getFireSpreadSpeed read those tables by default). Right after the
        // block, so the entry can never name an unregistered instance.
        if (settings.flameEncouragementValue() > 0
                || settings.flammabilityValue() > 0) {
            ((FireBlockInvoker) Blocks.FIRE).deds_api$setFlammable(block,
                    settings.flameEncouragementValue(),
                    settings.flammabilityValue());
        }
        return new Handle<>(BId.of(modId, name), block);
    }

    // --- ItemRegistrar ---

    @Override
    public RegistryHandle<Item> register(String name, ItemSettings settings) {
        return register(name, settings, Item::new);
    }

    @Override
    public RegistryHandle<Item> register(String name, ItemSettings settings,
            Function<Item.Properties, ? extends Item> factory) {
        requireRegistryWindow("item '" + name + "'");
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, ident(name));
        Item.Properties props = new Item.Properties()
                .stacksTo(settings.maxStackSizeValue())
                .setId(key);
        Item item = factory.apply(props);
        Registry.register(BuiltInRegistries.ITEM, key, item);
        NeoFluids.trackItem(item);
        return new Handle<>(BId.of(modId, name), item);
    }

    @Override
    public RegistryHandle<Item> registerBlockItem(
            RegistryHandle<? extends Block> block, ItemSettings settings) {
        return registerBlockItem(block, settings,
                props -> new BlockItem(block.get(), props));
    }

    @Override
    public RegistryHandle<Item> registerBlockItem(
            RegistryHandle<? extends Block> block, ItemSettings settings,
            Function<Item.Properties, ? extends Item> factory) {
        return registerBlockItem(block.id().path(), block, settings, factory);
    }

    @Override
    public RegistryHandle<Item> registerBlockItem(String name,
            RegistryHandle<? extends Block> block, ItemSettings settings,
            Function<Item.Properties, ? extends Item> factory) {
        RegistryHandle<Item> item = register(name, settings,
                props -> factory.apply(props.useBlockDescriptionPrefix()));
        // The FIRST item registered for a block stays Block.asItem(): see
        // FIRST_ITEM_FOR_BLOCK. NeoForge's item callback has already put THIS
        // item into Item.BY_BLOCK (last wins), so undo it explicitly.
        synchronized (FIRST_ITEM_FOR_BLOCK) {
            Item first = FIRST_ITEM_FOR_BLOCK.putIfAbsent(block.get(), item.get());
            Item.BY_BLOCK.put(block.get(), first != null ? first : item.get());
        }
        return item;
    }

    // --- EffectRegistrar ---

    @Override
    public EffectRegistrar.EffectHandle register(String name,
            Supplier<? extends MobEffect> factory) {
        requireRegistryWindow("mob effect '" + name + "'");
        MobEffect effect = factory.get();
        Holder<MobEffect> holder = Registry.registerForHolder(
                BuiltInRegistries.MOB_EFFECT, ident(name), effect);
        return new EffectHandleImpl(BId.of(modId, name), effect, holder);
    }

    private record EffectHandleImpl(BId id, MobEffect value,
            Holder<MobEffect> holder) implements EffectRegistrar.EffectHandle {
        @Override
        public MobEffect get() {
            return value;
        }
    }

    // --- BlockEntityRegistrar ---

    @Override
    public <T extends BlockEntity> RegistryHandle<BlockEntityType<T>> register(
            String name, BlockEntityRegistrar.Factory<T> factory,
            List<RegistryHandle<? extends Block>> blocks) {
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException(
                    "block entity type '" + name + "' registered with no "
                    + "host blocks — vanilla would only fail at first use");
        }
        requireRegistryWindow("block entity type '" + name + "'");
        Set<Block> valid = new HashSet<>();
        for (RegistryHandle<? extends Block> block : blocks) {
            valid.add(block.get());
        }
        // The two-argument constructor (public through NeoForge's access
        // transformer) leaves onlyOpCanSetNbt at its vanilla default, which is
        // what Fabric's builder does with its null flag.
        BlockEntityType<T> type = new BlockEntityType<>(factory::create, valid);
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, ident(name)), type);
        return new Handle<>(BId.of(modId, name), type);
    }

    // --- TabRegistrar ---

    @Override
    public void register(String name, RegistryHandle<? extends Item> icon,
            List<RegistryHandle<? extends Item>> items) {
        requireRegistryWindow("creative tab '" + name + "'");
        Component title = Component.translatable("itemGroup." + modId + "." + name);
        CreativeModeTab tab = CreativeModeTab.builder()
                .title(title)
                .icon(() -> new ItemStack(icon.get()))
                .displayItems((params, output) -> {
                    // Vanilla's ItemDisplayBuilder (so Fabric) throws for an
                    // item its tab already holds; NeoForge's build hook
                    // collects the generator's output into a set first and
                    // would drop it silently. Keep vanilla's rule, exactly:
                    // only an item that was added (enabled) counts as held.
                    Set<Item> held = new HashSet<>();
                    for (RegistryHandle<? extends Item> handle : items) {
                        Item item = handle.get();
                        if (held.contains(item)) {
                            throw new IllegalStateException("Accidentally adding the same "
                                    + "item stack twice "
                                    + new ItemStack(item).getDisplayName().getString()
                                    + " to a Creative Mode Tab: " + title.getString());
                        }
                        if (item.isEnabled(params.enabledFeatures())) {
                            held.add(item);
                        }
                        output.accept(item);
                    }
                })
                .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                ResourceKey.create(Registries.CREATIVE_MODE_TAB, ident(name)), tab);
    }

    @Override
    public void addToVanilla(TabRegistrar.VanillaTab tab,
            List<RegistryHandle<? extends Item>> items) {
        VanillaTabOps.append(tab, () -> items.stream()
                .map(handle -> new ItemStack(handle.get())).toList());
    }

    @Override
    public void addStacksToVanilla(TabRegistrar.VanillaTab tab,
            Supplier<List<ItemStack>> stacks) {
        VanillaTabOps.append(tab, stacks);
    }

    @Override
    public void insertAfterInVanilla(TabRegistrar.VanillaTab tab, ItemLike anchor,
            Supplier<List<ItemStack>> stacks) {
        VanillaTabOps.insertAfter(tab, anchor, stacks);
    }

    // --- EntityRegistrar ---

    @Override
    public <T extends Entity> RegistryHandle<EntityType<T>> register(
            String name, EntityType.EntityFactory<T> factory, float width,
            float height) {
        return new Handle<>(BId.of(modId, name),
                buildEntityType(name, factory, width, height));
    }

    /**
     * The attribute supplier is evaluated during this call, as on Fabric:
     * {@code onInitialize} runs after NeoForge registered its own attributes
     * (which {@code LivingEntity.createLivingAttributes} adds), so it can be.
     * NeoForge only accepts default attributes through
     * {@link EntityAttributeCreationEvent}, which it posts at the end of the
     * same registry window, before any entity can exist.
     */
    @Override
    public <T extends LivingEntity> RegistryHandle<EntityType<T>>
            registerLiving(String name, EntityType.EntityFactory<T> factory,
                    float width, float height,
                    Supplier<AttributeSupplier.Builder> attributes) {
        EntityType<T> type = buildEntityType(name, factory, width, height);
        AttributeSupplier built = attributes.get().build();
        synchronized (DEFAULT_ATTRIBUTES) {
            DEFAULT_ATTRIBUTES.put(type, built);
        }
        return new Handle<>(BId.of(modId, name), type);
    }

    private <T extends Entity> EntityType<T> buildEntityType(String name,
            EntityType.EntityFactory<T> factory, float width, float height) {
        requireRegistryWindow("entity type '" + name + "'");
        ResourceKey<EntityType<?>> key =
                ResourceKey.create(Registries.ENTITY_TYPE, ident(name));
        EntityType<T> type = EntityType.Builder.of(factory, MobCategory.MISC)
                .sized(width, height)
                .updateInterval(1)
                .build(key);
        Registry.register(BuiltInRegistries.ENTITY_TYPE, key, type);
        return type;
    }

    @Override
    public boolean spawn(ServerLevel level, Entity entity, double x, double y,
            double z) {
        entity.snapTo(x, y, z);
        return level.addFreshEntity(entity);
    }

    /** From {@link DedsApiNeoForge}: hands every queued default over. */
    static void putDefaultAttributes(EntityAttributeCreationEvent event) {
        synchronized (DEFAULT_ATTRIBUTES) {
            DEFAULT_ATTRIBUTES.forEach(event::put);
        }
    }

    // --- PlayerDataRegistrar (Ded's API v1) ---

    /**
     * One NeoForge attachment type per spec, registered straight into
     * NeoForge's attachment registry (writable during the registry window,
     * and its add callback files synced types into the synced registry), with
     * the Fabric backend's semantics kept point by point:
     * <ul>
     * <li><b>Disk shape.</b> NeoForge persists through a {@link MapCodec}
     *     written into the attachment's own child compound. A codec that
     *     already is a map codec in disguise ({@link MapCodec.MapCodecCodec})
     *     is unwrapped, so the fields land where Fabric's
     *     {@code persistent(codec)} puts them; any other codec is wrapped in a
     *     {@code value} field.</li>
     * <li><b>Respawn copy.</b> Fabric hands the respawned player the SAME
     *     object; NeoForge's default copy round-trips it through the codec.
     *     So the copy handler returns the value itself.</li>
     * <li><b>Sync.</b> ALL is NeoForge's "everyone who sees the holder".
     *     TARGET_ONLY writes nothing on NeoForge's initial sync (which never
     *     asks {@code sendToPlayer}, so a predicate alone would leak the
     *     owner's data to every tracker) and the owner's copy is restored by
     *     {@link #resyncOwnerOnlyData} as an update sync.</li>
     * </ul>
     */
    @Override
    public <T> PlayerDataKey<T> register(String name, PlayerDataSpec<T> spec) {
        requireRegistryWindow("player data '" + name + "'");
        Codec<T> codec = spec.codec();
        MapCodec<T> mapCodec = codec instanceof MapCodec.MapCodecCodec<T> wrapped
                ? wrapped.codec() : codec.fieldOf("value");
        AttachmentType.Builder<T> builder = AttachmentType.builder(spec.defaultValue())
                .serialize(mapCodec)
                .copyHandler((value, holder, provider) -> value);
        if (spec.copiesOnRespawn()) {
            builder = builder.copyOnDeath();
        }
        StreamCodec<? super RegistryFriendlyByteBuf, T> wire = spec.syncCodec();
        switch (spec.syncScope()) {
            case ALL -> builder = builder.sync(wire);
            case TARGET_ONLY -> builder = builder.sync(new OwnerOnlySync<>(wire));
            case NONE -> {
            }
        }
        AttachmentType<T> type = builder.build();
        Registry.register(NeoForgeRegistries.ATTACHMENT_TYPES, ident(name), type);
        if (spec.syncScope() == PlayerDataSpec.Sync.TARGET_ONLY) {
            TARGET_ONLY_TYPES.add(type);
        }
        BId bid = BId.of(modId, name);
        Supplier<T> fallback = spec.defaultValue();
        return new PlayerDataKey<T>() {
            @Override
            public BId id() {
                return bid;
            }

            /**
             * Fabric's {@code getAttached} returns null for "never set" and
             * the API fills in the default. NeoForge's {@code getData} would
             * instead CREATE the default, store it and sync it, so it is never
             * used for a read.
             */
            @Override
            public T get(Player player) {
                T value = player.getExistingDataOrNull(type);
                return value != null ? value : fallback.get();
            }

            /**
             * Fabric's {@code setAttached} treats null as removal and syncs
             * only when the value changed; NeoForge's {@code setData} rejects
             * null and syncs on every call. Both Fabric rules, here. One
             * difference is left: for a value EQUAL to the stored one, Fabric
             * still stores the new instance (skipping only the sync), while
             * here the old instance stays, because NeoForge has no way to
             * store without syncing. Only a mutable value changed after
             * {@code set} can tell; {@code PlayerDataSpec} asks for
             * immutable values.
             */
            @Override
            public void set(ServerPlayer player, T value) {
                if (value == null) {
                    player.removeData(type);
                } else if (!Objects.equals(player.getExistingDataOrNull(type), value)) {
                    player.setData(type, value);
                }
            }
        };
    }

    /**
     * The TARGET_ONLY sync handler: the owner, and only the owner, is sent
     * updates, and an INITIAL sync writes nothing at all.
     */
    private record OwnerOnlySync<T>(StreamCodec<? super RegistryFriendlyByteBuf, T> codec)
            implements AttachmentSyncHandler<T> {

        @Override
        public boolean sendToPlayer(IAttachmentHolder holder, ServerPlayer to) {
            return holder == to;
        }

        @Override
        public void write(RegistryFriendlyByteBuf buf, T attachment, boolean initialSync) {
            if (!initialSync) {
                codec.encode(buf, attachment);
            }
        }

        @Override
        public T read(IAttachmentHolder holder, RegistryFriendlyByteBuf buf,
                T previousValue) {
            return codec.decode(buf);
        }
    }

    /**
     * Restores a player's own TARGET_ONLY data after one of NeoForge's initial
     * syncs left it out (see {@link #register(String, PlayerDataSpec)}). Wired
     * by {@link DedsApiNeoForge} to the login, respawn and dimension-change
     * events, which fire right after NeoForge's initial self-sync.
     * {@code syncData} is an UPDATE sync, which applies {@code sendToPlayer}
     * and so reaches the owner alone. Only types the player actually holds: a
     * fresh player has nothing to restore.
     */
    static void resyncOwnerOnlyData(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        for (AttachmentType<?> type : TARGET_ONLY_TYPES) {
            if (serverPlayer.hasData(type)) {
                serverPlayer.syncData(type);
            }
        }
    }

    // --- NetRegistrar (Ded's API v1) ---

    @Override
    public <T> MessageType<T> registerC2S(String name,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            C2SHandler<T> handler) {
        return NeoForgeMessageType.c2s(modId, name, codec, handler);
    }

    @Override
    public <T> MessageType<T> registerS2C(String name,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        return NeoForgeMessageType.s2c(modId, name, codec);
    }

    // --- CommandRegistrar (Ded's API v1.1) ---

    /**
     * {@code RegisterCommandsEvent} is posted at the end of the
     * {@code Commands} constructor, the same point Fabric's
     * {@code CommandRegistrationCallback} injects at, with the same three
     * values; like Fabric's callback it fires on every command-tree
     * (re)build.
     */
    @Override
    public void register(CommandRegistrar.Builder builder) {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
                event -> builder.build(event.getDispatcher(),
                        event.getBuildContext(), event.getCommandSelection()));
    }

    // --- ConfigRegistrar (Ded's API v1.1) ---

    @Override
    public <C> ConfigHandle<C> register(String name, Codec<C> codec,
            Supplier<C> defaults) {
        return JsonConfigFile.load(modId, name, codec, defaults, logger);
    }
}
