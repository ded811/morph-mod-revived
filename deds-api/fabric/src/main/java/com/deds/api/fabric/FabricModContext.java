package com.deds.api.fabric;

import com.deds.api.ModContext;
import com.deds.api.attach.PlayerDataKey;
import com.deds.api.attach.PlayerDataRegistrar;
import com.deds.api.attach.PlayerDataSpec;
import com.deds.api.command.CommandRegistrar;
import com.deds.api.config.ConfigHandle;
import com.deds.api.config.ConfigRegistrar;
import com.deds.api.fluid.FluidPort;
import com.deds.api.fluid.FluidRegistrar;
import com.deds.api.fluid.FluidTankView;
import com.deds.api.id.BId;
import com.deds.api.internal.JsonConfigFile;
import com.deds.api.net.MessageType;
import com.deds.api.net.NetRegistrar;
import com.deds.api.registry.BlockEntityRegistrar;
import com.deds.api.registry.BlockRegistrar;
import com.deds.api.registry.BlockSettings;
import com.deds.api.registry.EffectRegistrar;
import com.deds.api.registry.ItemRegistrar;
import com.deds.api.registry.ItemSettings;
import com.deds.api.registry.RegistryHandle;
import com.deds.api.registry.TabRegistrar;

import com.mojang.serialization.Codec;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorageUtil;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Per-mod context on Fabric. Registration is immediate (Fabric has no
 * deferred-register phase), so handles wrap live instances.
 */
final class FabricModContext implements ModContext, BlockRegistrar,
        ItemRegistrar, EffectRegistrar, TabRegistrar, BlockEntityRegistrar,
        PlayerDataRegistrar, NetRegistrar, CommandRegistrar, ConfigRegistrar,
        FluidRegistrar {

    private final String modId;
    private final Logger logger;

    FabricModContext(String modId) {
        this.modId = modId;
        this.logger = LoggerFactory.getLogger(modId);
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
        return this;
    }

    // --- helpers ---

    private record Handle<T>(BId id, T value) implements RegistryHandle<T> {
        @Override
        public T get() {
            return value;
        }
    }

    private Identifier ident(String name) {
        return Identifier.fromNamespaceAndPath(modId, name);
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
        Identifier id = ident(name);
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        Block block = factory.apply(toVanilla(settings).setId(key));
        Registry.register(BuiltInRegistries.BLOCK, key, block);
        // BlockSettings.flammable (v1.5): vanilla's FireBlock.setFlammable is
        // private, so fire odds go through the loader's registry — which is
        // net.fabricmc.*, hence API-side. Registered right after the block so
        // the entry can never reference an unregistered instance.
        if (settings.flameEncouragementValue() > 0
                || settings.flammabilityValue() > 0) {
            FlammableBlockRegistry.getDefaultInstance().add(block,
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
        Identifier id = ident(name);
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        Item.Properties props = new Item.Properties()
                .stacksTo(settings.maxStackSizeValue())
                .setId(key);
        Item item = factory.apply(props);
        Registry.register(BuiltInRegistries.ITEM, key, item);
        return new Handle<>(BId.of(modId, name), item);
    }

    // --- EffectRegistrar ---

    @Override
    public EffectRegistrar.EffectHandle register(String name,
            Supplier<? extends MobEffect> factory) {
        Identifier id = ident(name);
        MobEffect effect = factory.get();
        // registerForHolder: the modern effect surface is Holder-typed
        // (MobEffectInstance, hasEffect/getEffect/removeEffect), so capture the
        // registration-time holder rather than re-looking it up later.
        Holder<MobEffect> holder = Registry.registerForHolder(
                BuiltInRegistries.MOB_EFFECT, id, effect);
        return new EffectHandleImpl(BId.of(modId, name), effect, holder);
    }

    private record EffectHandleImpl(BId id, MobEffect value,
            Holder<MobEffect> holder)
            implements EffectRegistrar.EffectHandle {
        @Override
        public MobEffect get() {
            return value;
        }
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
        // Vanilla wires both of these in its own bootstrap; without them a
        // block item shows the raw item.<ns>.<path> key instead of the
        // block name, and Block.asItem()/pick-block resolve to AIR.
        RegistryHandle<Item> item = register(name, settings,
                props -> factory.apply(props.useBlockDescriptionPrefix()));
        // When several items place ONE block (the five Carpenter's slope
        // placers) the FIRST one registered must stay the canonical answer for
        // Block.asItem() and therefore for pick-block.
        //
        // A plain `Item.BY_BLOCK.putIfAbsent` does NOT achieve that, and the
        // failure is silent. Fabric's own
        // `impl.registry.sync.trackers.vanilla.BlockItemTracker` registers a
        // RegistryEntryAddedCallback that calls
        // `BlockItem.registerBlocks(Item.BY_BLOCK, item)` — a plain `put` —
        // for every BlockItem as it enters the registry (bytecode-verified
        // against fabric-registry-sync-v0). So by the time we get here the map
        // already contains THIS item, putIfAbsent is a no-op, and the LAST
        // registration wins. Hence our own first-claimant table plus an
        // explicit put that undoes Fabric's overwrite.
        Item first = FIRST_ITEM_FOR_BLOCK.putIfAbsent(block.get(), item.get());
        Item.BY_BLOCK.put(block.get(), first != null ? first : item.get());
        return item;
    }

    /**
     * The first item registered for each block through
     * {@link #registerBlockItem}, so a later item for the same block cannot
     * steal {@code Block.asItem()}. Static because blocks are globally unique;
     * written only during {@code onInitialize}, which is single-threaded.
     */
    private static final Map<Block, Item> FIRST_ITEM_FOR_BLOCK =
            new HashMap<>();

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
        Identifier id = ident(name);
        ResourceKey<BlockEntityType<?>> key =
                ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, id);
        FabricBlockEntityTypeBuilder<T> builder =
                FabricBlockEntityTypeBuilder.create(factory::create);
        for (RegistryHandle<? extends Block> block : blocks) {
            builder.addBlock(block.get());
        }
        BlockEntityType<T> type = builder.build();
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, key, type);
        return new Handle<>(BId.of(modId, name), type);
    }

    // --- PlayerDataRegistrar (Ded's API v1) ---

    @Override
    public <T> PlayerDataKey<T> register(String name, PlayerDataSpec<T> spec) {
        Identifier id = ident(name);
        AttachmentRegistry.Builder<T> builder = AttachmentRegistry.<T>builder()
                .persistent(spec.codec())
                .initializer(spec.defaultValue());
        if (spec.copiesOnRespawn()) {
            builder = builder.copyOnDeath();
        }
        switch (spec.syncScope()) {
            case ALL -> builder = builder.syncWith(spec.syncCodec(),
                    AttachmentSyncPredicate.all());
            case TARGET_ONLY -> builder = builder.syncWith(spec.syncCodec(),
                    AttachmentSyncPredicate.targetOnly());
            case NONE -> {
            }
        }
        AttachmentType<T> type = builder.buildAndRegister(id);
        BId bid = BId.of(modId, name);
        java.util.function.Supplier<T> fallback = spec.defaultValue();
        return new PlayerDataKey<T>() {
            @Override
            public BId id() {
                return bid;
            }

            @Override
            public T get(net.minecraft.world.entity.player.Player player) {
                T value = ((AttachmentTarget) player).getAttached(type);
                return value != null ? value : fallback.get();
            }

            @Override
            public void set(net.minecraft.server.level.ServerPlayer player,
                    T value) {
                ((AttachmentTarget) player).setAttached(type, value);
            }
        };
    }

    // --- NetRegistrar (Ded's API v1) ---

    @Override
    public <T> MessageType<T> registerC2S(String name,
            net.minecraft.network.codec.StreamCodec<
                    ? super net.minecraft.network.RegistryFriendlyByteBuf,
                    T> codec,
            C2SHandler<T> handler) {
        return FabricMessageType.c2s(modId, name, codec, handler);
    }

    @Override
    public <T> MessageType<T> registerS2C(String name,
            net.minecraft.network.codec.StreamCodec<
                    ? super net.minecraft.network.RegistryFriendlyByteBuf,
                    T> codec) {
        return FabricMessageType.s2c(modId, name, codec);
    }

    // --- CommandRegistrar (Ded's API v1.1) ---

    @Override
    public void register(CommandRegistrar.Builder builder) {
        // The callback fires on every command-tree (re)build; pass the
        // dispatcher/context/selection straight through to mod code.
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, ctx, selection) ->
                        builder.build(dispatcher, ctx, selection));
    }

    // --- ConfigRegistrar (Ded's API v1.1) ---

    @Override
    public <C> ConfigHandle<C> register(String name, Codec<C> codec,
            java.util.function.Supplier<C> defaults) {
        return JsonConfigFile.load(modId, name, codec, defaults, logger);
    }

    // --- FluidRegistrar (Ded's API v2.1) ---

    @Override
    public RegistryHandle<Fluid> register(String name, Fluid fluid) {
        Identifier id = ident(name);
        Registry.register(BuiltInRegistries.FLUID,
                ResourceKey.create(Registries.FLUID, id), fluid);
        return new Handle<>(BId.of(modId, name), fluid);
    }

    @Override
    public <T extends BlockEntity> void exposeTanks(
            RegistryHandle<BlockEntityType<T>> type,
            Function<T, List<FluidTankView>> tanks) {
        FluidStorage.SIDED.registerForBlockEntity(
                (blockEntity, direction) -> combine(tanks.apply(blockEntity)),
                type.get());
    }

    @Override
    public FluidPort port(Level level, BlockPos pos, Direction side) {
        Storage<FluidVariant> storage =
                FluidStorage.SIDED.find(level, pos, side);
        return storage == null ? null : new FabricFluidPort(storage);
    }

    @Override
    public boolean useHeldContainer(List<FluidTankView> tanks, Player player,
            InteractionHand hand) {
        Storage<FluidVariant> storage = combine(tanks);
        return storage != null
                && FluidStorageUtil.interactWithFluidStorage(storage, player,
                        hand);
    }

    /**
     * One tank becomes itself; several become a {@link TankChain} over them in
     * list order; none becomes {@code null}, which the lookup reads as "this
     * block has no fluid connection".
     */
    private static Storage<FluidVariant> combine(List<FluidTankView> tanks) {
        if (tanks.isEmpty()) {
            return null;
        }
        if (tanks.size() == 1) {
            return new FabricTankStorage(tanks.get(0));
        }
        return new TankChain(
                tanks.stream().map(FabricTankStorage::new).toList());
    }

    /**
     * Several tanks presented as ONE storage, walked as a <b>chain</b>: a tank
     * that will not take (or does not hold) the fluid <b>ends</b> the walk
     * instead of being stepped over.
     *
     * <p>Plain {@link CombinedStorage} is a bag — its {@code insert} does
     * {@code amount += part.insert(...)} for every part and breaks only once
     * the whole amount is placed, so a part that answers 0 is skipped and the
     * ones behind it are still reached. That is wrong for the shape this seam
     * actually serves, a 1.6.4 tank column: {@code TileEntityTank.fillColumn}
     * (OB-1.2.9 :381-392) opens with {@code if (!accepts(resource)) return;}
     * and only recurses upward afterwards, and {@code drainFromColumn}
     * (:359-373) does the same with {@code containsFluid}, so <b>a tank
     * holding a different fluid isolates everything past it</b> — a
     * deliberate way to partition a tank wall. With the bag, liquid XP
     * inserted at the bottom of [xp, water, xp] flows through the water tank
     * into the third one; with the chain it stops, as it did in 1.6.4.</p>
     *
     * <p>What this does NOT reproduce, deliberately: the original's
     * {@code drainFromColumn} recurses to the top of the column <em>before</em>
     * draining itself, so 1.6.4 empties a column top-down while the chain
     * empties it in list order, bottom-up. Only the intermediate distribution
     * differs — the same total leaves the same set of tanks, and the tank's own
     * gravity pass re-settles the column on the next tick.</p>
     *
     * <p>Only an identity refusal is terminal. A tank of the right fluid with
     * no room left, or with nothing left to give, passes the walk on exactly
     * as the original's recursion does — see
     * {@link FabricTankStorage#canHold} and {@link FabricTankStorage#holds}.
     * The contract is written up on
     * {@link com.deds.api.fluid.FluidRegistrar#exposeTanks}, including the
     * warning not to hand this method a list of unrelated tanks.</p>
     */
    private static final class TankChain
            extends CombinedStorage<FluidVariant, FabricTankStorage> {

        TankChain(List<FabricTankStorage> parts) {
            super(parts);
        }

        @Override
        public long insert(FluidVariant resource, long maxAmount,
                TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maxAmount);
            long moved = 0;
            for (FabricTankStorage part : parts) {
                if (!part.canHold(resource)) {
                    break;
                }
                moved += part.insert(resource, maxAmount - moved, transaction);
                if (moved >= maxAmount) {
                    break;
                }
            }
            return moved;
        }

        @Override
        public long extract(FluidVariant resource, long maxAmount,
                TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maxAmount);
            long moved = 0;
            for (FabricTankStorage part : parts) {
                if (!part.holds(resource)) {
                    break;
                }
                moved += part.extract(resource, maxAmount - moved, transaction);
                if (moved >= maxAmount) {
                    break;
                }
            }
            return moved;
        }
    }

    // --- TabRegistrar ---

    @Override
    public void register(String name, RegistryHandle<? extends Item> icon,
            List<RegistryHandle<? extends Item>> items) {
        Identifier id = ident(name);
        CreativeModeTab tab = FabricCreativeModeTab.builder()
                .title(Component.translatable(
                        "itemGroup." + modId + "." + name))
                .icon(() -> new ItemStack(icon.get()))
                .displayItems((params, output) -> {
                    for (RegistryHandle<? extends Item> handle : items) {
                        output.accept(handle.get());
                    }
                })
                .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                ResourceKey.create(Registries.CREATIVE_MODE_TAB, id), tab);
    }

    @Override
    public void addStacksToVanilla(TabRegistrar.VanillaTab tab,
            java.util.function.Supplier<List<ItemStack>> stacks) {
        CreativeModeTabEvents.modifyOutputEvent(
                ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                        Identifier.withDefaultNamespace(tab.path())))
                .register(output -> {
                    for (ItemStack stack : stacks.get()) {
                        output.accept(stack);
                    }
                });
    }

    @Override
    public void insertAfterInVanilla(TabRegistrar.VanillaTab tab,
            net.minecraft.world.level.ItemLike anchor,
            java.util.function.Supplier<List<ItemStack>> stacks) {
        // ANCHOR-based, never index-based: Fabric's insertAfter takes the
        // anchor ITEM, so vanilla can add or reorder entries in this tab in
        // any future MC version and ours still land right after the anchor
        // with zero code change. An index or position count would need
        // re-checking every update — see MOD-COOKBOOK §4.
        CreativeModeTabEvents.modifyOutputEvent(
                ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                        Identifier.withDefaultNamespace(tab.path())))
                .register(output -> {
                    List<ItemStack> entries = stacks.get();
                    if (entries.isEmpty()) {
                        return;
                    }
                    // Missing anchor (datapack removal, feature flag) must
                    // not drop our items — degrade to an append.
                    boolean present = output.getDisplayStacks().stream()
                            .anyMatch(s -> s.is(anchor.asItem()));
                    if (present) {
                        output.insertAfter(anchor, entries);
                    } else {
                        entries.forEach(output::accept);
                    }
                });
    }

    @Override
    public void addToVanilla(TabRegistrar.VanillaTab tab,
            List<RegistryHandle<? extends Item>> items) {
        // Vanilla-tab membership (v1.3): Fabric rebuilds tab contents
        // through this event on BOTH sides, so creative search and recipe
        // viewers (JEI indexes tabs) pick the items up automatically.
        CreativeModeTabEvents.modifyOutputEvent(
                ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                        Identifier.withDefaultNamespace(tab.path())))
                .register(output -> {
                    for (RegistryHandle<? extends Item> handle : items) {
                        output.accept(handle.get());
                    }
                });
    }
}
