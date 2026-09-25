package com.deds.api.neoforge;

import com.deds.api.ModContext;
import com.deds.api.attach.PlayerDataKey;
import com.deds.api.attach.PlayerDataRegistrar;
import com.deds.api.attach.PlayerDataSpec;
import com.deds.api.command.CommandRegistrar;
import com.deds.api.config.ConfigHandle;
import com.deds.api.config.ConfigRegistrar;
import com.deds.api.fluid.FluidRegistrar;
import com.deds.api.id.BId;
import com.deds.api.internal.JsonConfigFile;
import com.deds.api.net.MessageType;
import com.deds.api.net.NetRegistrar;
import com.deds.api.registry.BlockEntityRegistrar;
import com.deds.api.registry.BlockRegistrar;
import com.deds.api.registry.EffectRegistrar;
import com.deds.api.registry.ItemRegistrar;
import com.deds.api.registry.TabRegistrar;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentSyncHandler;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Per-mod context on NeoForge: the surfaces Morph uses, at Fabric parity
 * (player data, networking, commands, config, logger).
 *
 * <p><b>Not supported on NeoForge yet</b>, and loud about it:
 * {@link #blocks()}, {@link #items()}, {@link #effects()}, {@link #tabs()},
 * {@link #blockEntities()} and {@link #fluids()} throw
 * {@link UnsupportedOperationException}. The Fabric backend registers those
 * immediately; on NeoForge the built-in registries are frozen outside the
 * RegisterEvent window (so every factory has to be deferred and every
 * {@code RegistryHandle.get()} becomes a "not yet" until then), fluids need
 * NeoForge's own transfer API with a unit factor of 81 against Ded's
 * {@code FluidAmounts}, and fire odds need an access path of their own. Each is
 * a design of its own, and no mod that ships on NeoForge today needs one.</p>
 *
 * <p>Thread note: this object belongs to one mod and is built on that mod's
 * construction thread, but FML constructs different mods in parallel, so the
 * one static list here ({@link #TARGET_ONLY_TYPES}) is concurrent.</p>
 */
final class NeoForgeModContext implements ModContext, PlayerDataRegistrar,
        NetRegistrar, CommandRegistrar, ConfigRegistrar {

    /**
     * Every TARGET_ONLY player-data type of every Ded's mod, for the owner
     * resync after an initial sync (see {@link #resyncOwnerOnlyData}).
     */
    private static final List<DeferredHolder<AttachmentType<?>, ? extends AttachmentType<?>>>
            TARGET_ONLY_TYPES = new CopyOnWriteArrayList<>();

    private final String modId;
    private final Logger logger;
    private final IEventBus modBus;

    /**
     * This mod's attachment types. ONE per mod, created on the first
     * {@code playerData().register} and registered on the mod's own bus then:
     * {@code DeferredRegister.register(IEventBus)} may be called only once,
     * and a mod with no player data should not create one at all.
     */
    private DeferredRegister<AttachmentType<?>> attachments;

    NeoForgeModContext(String modId, IEventBus modBus) {
        this.modId = modId;
        // The same logger name the Fabric context uses, so a mod's log lines
        // (and JsonConfigFile's warnings) read identically on both loaders.
        this.logger = LoggerFactory.getLogger(modId);
        this.modBus = modBus;
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
        throw unsupported("block registration (ModContext.blocks())");
    }

    @Override
    public ItemRegistrar items() {
        throw unsupported("item registration (ModContext.items())");
    }

    @Override
    public EffectRegistrar effects() {
        throw unsupported("mob effect registration (ModContext.effects())");
    }

    @Override
    public TabRegistrar tabs() {
        throw unsupported("creative tabs (ModContext.tabs())");
    }

    @Override
    public BlockEntityRegistrar blockEntities() {
        throw unsupported("block entity registration (ModContext.blockEntities())");
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
        throw unsupported("fluids (ModContext.fluids())");
    }

    private UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException("Ded's API "
                + NeoForgePlatform.apiVersion() + " does not support " + what
                + " on NeoForge yet (mod '" + modId + "')");
    }

    // --- PlayerDataRegistrar (Ded's API v1) ---

    /**
     * One NeoForge attachment type per spec, registered through this mod's
     * {@link DeferredRegister} (NeoForge adds registry entries only inside
     * RegisterEvent, after every mod is constructed), with the Fabric
     * backend's semantics kept point by point:
     * <ul>
     * <li><b>Disk shape.</b> NeoForge persists through a {@link MapCodec}
     *     written into the attachment's own child compound. A codec that
     *     already is a map codec in disguise ({@link MapCodec.MapCodecCodec},
     *     which Morph's two codecs both are) is unwrapped, so the fields land
     *     exactly where Fabric's {@code persistent(codec)} puts them; any other
     *     codec is wrapped in a {@code value} field.</li>
     * <li><b>Respawn copy.</b> Fabric hands the respawned player the SAME
     *     object; NeoForge's default copy round-trips it through the codec,
     *     which a lenient codec could quietly change. So the copy handler
     *     returns the value itself. {@code copyOnDeath()} must follow
     *     {@code serialize} (NeoForge throws otherwise).</li>
     * <li><b>Sync.</b> ALL is NeoForge's "everyone who sees the holder".
     *     TARGET_ONLY is NOT a plain predicate: NeoForge's INITIAL sync
     *     (login, start-tracking, respawn, dimension change) never asks
     *     {@code sendToPlayer}, so a predicate alone would hand the owner's
     *     private data to every player who starts tracking them (verified in
     *     both NeoForge builds). The handler below writes NOTHING on an
     *     initial sync, which keeps the type out of initial payloads entirely,
     *     and the owner's own copy is restored right after each initial sync
     *     by {@link #resyncOwnerOnlyData}, as an update sync, which does apply
     *     {@code sendToPlayer}. Fabric applies its target-only predicate on
     *     every path; this is how the same privacy holds here.</li>
     * </ul>
     */
    @Override
    public <T> PlayerDataKey<T> register(String name, PlayerDataSpec<T> spec) {
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
        DeferredHolder<AttachmentType<?>, AttachmentType<T>> holder =
                attachments().register(name, () -> type);
        if (spec.syncScope() == PlayerDataSpec.Sync.TARGET_ONLY) {
            TARGET_ONLY_TYPES.add(holder);
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
                T value = player.getExistingDataOrNull(holder.get());
                return value != null ? value : fallback.get();
            }

            /**
             * Fabric's {@code setAttached} treats null as removal and syncs
             * only when the value changed; NeoForge's {@code setData} rejects
             * null and syncs on every call. Both Fabric rules, here.
             */
            @Override
            public void set(ServerPlayer player, T value) {
                AttachmentType<T> t = holder.get();
                if (value == null) {
                    player.removeData(t);
                } else if (!Objects.equals(player.getExistingDataOrNull(t), value)) {
                    player.setData(t, value);
                }
            }
        };
    }

    private synchronized DeferredRegister<AttachmentType<?>> attachments() {
        if (attachments == null) {
            attachments = DeferredRegister.create(
                    NeoForgeRegistries.Keys.ATTACHMENT_TYPES, modId);
            attachments.register(modBus);
        }
        return attachments;
    }

    /**
     * The TARGET_ONLY sync handler: the owner, and only the owner, is sent
     * updates, and an INITIAL sync writes nothing at all (NeoForge then leaves
     * the type out of that payload, per {@link AttachmentSyncHandler#write}).
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
     * syncs left it out (see {@link #register}). Wired by {@link DedsApiNeoForge}
     * to {@code PlayerLoggedInEvent}, {@code PlayerRespawnEvent} and
     * {@code PlayerChangedDimensionEvent} on the game bus: each fires right
     * after NeoForge's initial self-sync, and those three are that sync's only
     * callers. {@code syncData} is an UPDATE sync, which applies
     * {@code sendToPlayer} and so reaches the owner alone. Only types the
     * player actually holds: a fresh player has nothing to restore (and
     * syncing an absent type would send a removal).
     */
    static void resyncOwnerOnlyData(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        for (DeferredHolder<AttachmentType<?>, ? extends AttachmentType<?>> holder
                : TARGET_ONLY_TYPES) {
            AttachmentType<?> type = holder.get();
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
