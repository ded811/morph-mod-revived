package com.deds.api.neoforge.transfer;

import com.deds.api.fluid.FluidAmounts;
import com.deds.api.fluid.FluidTankView;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.List;
import java.util.Objects;

/**
 * {@code FluidRegistrar.useHeldContainer} on NeoForge: Fabric's
 * {@code FluidStorageUtil.interactWithFluidStorage}, reproduced step for step
 * rather than delegated to NeoForge's {@code FluidUtil}, whose sound category,
 * listeners, game events and supported items all differ.
 *
 * <p>The hand is found through NeoForge's player item access (in creative the
 * held stack is never used up and a filled container is only added if the
 * player lacks one, as in Fabric's creative context, except that NeoForge
 * looks for one in the 36 main slots only, and Fabric also in the off-hand
 * and armour slots). Its fluid storage is, as on Fabric: an empty bucket
 * takes a whole bucket of any fluid whose bucket item maps back to it; any
 * bucket item whose fluid maps back to it gives a whole bucket (subclasses
 * included, which NeoForge's own bucket handler skips), unless it is another
 * mod's bucket subclass carrying its own fluid capability, which is then used
 * instead, as its provider wins on Fabric; a glass bottle takes a third of a
 * bucket of water; a water potion gives one; and another mod's fluid
 * container item is used through NeoForge's item capability, in whole
 * millibuckets.</p>
 *
 * <p>Then: try to FILL the hand from the tanks, else EMPTY it into them,
 * moving the most the receiving side accepts in one step; one fluid, from one
 * source view; play the fill or empty sound at the player's eyes on the
 * players channel, heard by everyone but that player; no game event.</p>
 */
final class HeldContainer {

    private static final long BUCKET = FluidAmounts.BUCKET;
    private static final long BOTTLE = FluidAmounts.BUCKET / 3;

    /** A hand item's fluid storage, in droplets. */
    private interface Hand {
        /** Number of views a "from" pass walks. */
        int views();

        /** The view's fluid, or EMPTY for a blank view. */
        Fluid viewFluid(int view);

        long extract(int view, Fluid fluid, long maxAmount, TransactionContext tx);

        long insert(Fluid fluid, long maxAmount, TransactionContext tx);

        /**
         * The unit this hand moves in: 1 droplet, or a whole millibucket for
         * another mod's container, so what the tanks accept from it is rounded
         * to what it can actually give.
         */
        default int step() {
            return 1;
        }
    }

    private HeldContainer() {
    }

    static boolean interact(List<FluidTankView> tanks, Player player,
            InteractionHand hand) {
        if (tanks == null || tanks.isEmpty()) {
            return false;
        }
        ItemAccess access = ItemAccess.forPlayerInteraction(player, hand);
        Hand handStorage = find(access);
        if (handStorage == null) {
            return false;
        }
        Item handItem = player.getItemInHand(hand).getItem();
        try {
            return fill(tanks, handStorage, player, handItem)
                    || empty(handStorage, tanks, player, handItem);
        } catch (Exception e) {
            CrashReport report = CrashReport.forThrowable(e,
                    "Interacting with fluid storage");
            report.addCategory("Interaction details")
                    .setDetail("Player", () -> String.valueOf(player))
                    .setDetail("Hand", hand)
                    .setDetail("Hand item", handItem::toString)
                    .setDetail("Fluid storage", () -> Objects.toString(tanks, null));
            throw new ReportedException(report);
        }
    }

    /** Tanks to hand: Fabric's first {@code moveWithSound(storage, hand)}. */
    private static boolean fill(List<FluidTankView> tanks, Hand hand,
            Player player, Item handItem) {
        for (FluidTankView tank : tanks) {
            if (tank.fluid() == Fluids.EMPTY) {
                continue;
            }
            Fluid resource = TankOps.normalize(tank.fluid());
            long maxExtracted;
            try (Transaction test = Transaction.openRoot()) {
                maxExtracted = TankOps.extract(tank, resource, Long.MAX_VALUE, 1, test);
            }
            try (Transaction transfer = Transaction.openRoot()) {
                long accepted = hand.insert(resource, maxExtracted, transfer);
                if (accepted > 0 && TankOps.extract(tank, resource, accepted, 1,
                        transfer) == accepted) {
                    transfer.commit();
                    playSound(player, resource, true, handItem);
                    return true;
                }
            }
        }
        return false;
    }

    /** Hand to tanks: Fabric's second {@code moveWithSound(hand, storage)}. */
    private static boolean empty(Hand hand, List<FluidTankView> tanks,
            Player player, Item handItem) {
        for (int view = 0; view < hand.views(); view++) {
            Fluid resource = hand.viewFluid(view);
            if (resource == Fluids.EMPTY) {
                continue;
            }
            long maxExtracted;
            try (Transaction test = Transaction.openRoot()) {
                maxExtracted = hand.extract(view, resource, Long.MAX_VALUE, test);
            }
            try (Transaction transfer = Transaction.openRoot()) {
                long accepted = TankOps.chainInsert(tanks, resource, maxExtracted,
                        hand.step(), transfer);
                if (accepted > 0 && hand.extract(view, resource, accepted,
                        transfer) == accepted) {
                    transfer.commit();
                    playSound(player, resource, false, handItem);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Fabric's sound choice, played the way Fabric plays it: at the eyes,
     * players channel, the acting player excluded. Fabric asks the fluid's
     * registered attribute handler first ({@code FluidVariantAttributes});
     * the NeoForge counterpart is the fluid's {@code FluidType}, which gives
     * vanilla water and lava their sounds, Ded's fluids theirs
     * ({@link DedFluidType}) and another mod's fluid whatever that mod
     * chose. The fallback, and the bottle special case, are Fabric's.
     */
    private static void playSound(Player player, Fluid fluid, boolean fill,
            Item handItem) {
        SoundEvent sound = fluid.getFluidType().getSound(new FluidStack(fluid, 1),
                fill ? SoundActions.BUCKET_FILL : SoundActions.BUCKET_EMPTY);
        if (sound == null) {
            if (fill) {
                sound = fluid == Fluids.LAVA ? SoundEvents.BUCKET_FILL_LAVA
                        : fluid.getPickupSound().orElse(SoundEvents.BUCKET_FILL);
            } else {
                sound = fluid == Fluids.LAVA ? SoundEvents.BUCKET_EMPTY_LAVA
                        : SoundEvents.BUCKET_EMPTY;
            }
        }
        if (fluid == Fluids.WATER) {
            if (fill && handItem == Items.GLASS_BOTTLE) {
                sound = SoundEvents.BOTTLE_FILL;
            }
            if (!fill && handItem == Items.POTION) {
                sound = SoundEvents.BOTTLE_EMPTY;
            }
        }
        player.level().playSound(player, player.getX(), player.getEyeY(),
                player.getZ(), sound, SoundSource.PLAYERS, 1, 1);
    }

    /** The hand's fluid storage, or null if the held item has none. */
    private static Hand find(ItemAccess access) {
        ItemResource held = access.getResource();
        if (held.isEmpty()) {
            return null;
        }
        Item item = held.getItem();
        if (item == Items.BUCKET) {
            return new EmptyContainer(access, Items.BUCKET, null, BUCKET);
        }
        if (item instanceof BucketItem bucket) {
            // On Fabric the bucket logic is only a fallback: a provider
            // another mod registers for its own item wins. So a BucketItem
            // SUBCLASS from another mod that carries its own NeoForge fluid
            // capability (its own empty bucket, a bucket that empties into a
            // different item) is asked first. Plain buckets, whose NeoForge
            // handler matches the logic below, and Ded's own buckets keep
            // the exact logic.
            if (bucket.getClass() != BucketItem.class && !NeoFluids.isDedItem(item)) {
                ResourceHandler<FluidResource> own =
                        access.oneByOne().getCapability(Capabilities.Fluid.ITEM);
                if (own != null) {
                    return new ForeignContainer(own);
                }
            }
            Fluid content = bucket.content;
            if (content != Fluids.EMPTY && content.getBucket() == bucket) {
                return new FullContainer(access, item, TankOps.normalize(content),
                        BUCKET);
            }
            return null;
        }
        if (item == Items.GLASS_BOTTLE) {
            return new EmptyContainer(access, Items.GLASS_BOTTLE, Fluids.WATER, BOTTLE);
        }
        if (item == Items.POTION) {
            return isWaterPotion(held)
                    ? new FullContainer(access, Items.POTION, Fluids.WATER, BOTTLE)
                    : null;
        }
        ResourceHandler<FluidResource> foreign =
                access.oneByOne().getCapability(Capabilities.Fluid.ITEM);
        return foreign == null ? null : new ForeignContainer(foreign);
    }

    private static boolean isWaterPotion(ItemResource resource) {
        PotionContents contents = resource.getComponents()
                .getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        return resource.is(Items.POTION)
                && contents.potion().orElse(null) == Potions.WATER;
    }

    /**
     * An empty bucket or glass bottle: takes exactly {@code amount} of a
     * matching fluid by exchanging one item for its filled form, components
     * kept (Fabric's {@code EmptyBucketStorage} / {@code EmptyItemFluidStorage}).
     * {@code only} is the one fluid a bottle takes; null for a bucket, which
     * takes any fluid whose bucket item maps back to it.
     */
    private record EmptyContainer(ItemAccess access, Item emptyItem, Fluid only,
            long amount) implements Hand {

        @Override
        public int views() {
            return 0;
        }

        @Override
        public Fluid viewFluid(int view) {
            return Fluids.EMPTY;
        }

        @Override
        public long extract(int view, Fluid fluid, long maxAmount, TransactionContext tx) {
            return 0;
        }

        @Override
        public long insert(Fluid fluid, long maxAmount, TransactionContext tx) {
            ItemResource current = access.getResource();
            if (!current.is(emptyItem) || maxAmount < amount) {
                return 0;
            }
            ItemResource filled;
            if (only == null) {
                if (!(fluid.getBucket() instanceof BucketItem full)
                        || full.content != fluid) {
                    return 0;
                }
                filled = ItemResource.of(full, current.getComponentsPatch());
            } else {
                if (fluid != only) {
                    return 0;
                }
                ItemStack stack = current.toStack();
                stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.WATER));
                filled = ItemResource.of(Items.POTION, stack.getComponentsPatch());
            }
            return access.exchange(filled, 1, tx) == 1 ? amount : 0;
        }
    }

    /**
     * A full bucket or a water potion: gives exactly {@code amount} of its
     * fluid by exchanging one item for its empty form (Fabric's
     * {@code FullItemFluidStorage} / {@code WaterPotionStorage}).
     */
    private record FullContainer(ItemAccess access, Item fullItem, Fluid contained,
            long amount) implements Hand {

        private boolean stillFull() {
            ItemResource current = access.getResource();
            return fullItem == Items.POTION ? isWaterPotion(current)
                    : current.is(fullItem);
        }

        @Override
        public int views() {
            return 1;
        }

        @Override
        public Fluid viewFluid(int view) {
            return stillFull() ? contained : Fluids.EMPTY;
        }

        @Override
        public long extract(int view, Fluid fluid, long maxAmount, TransactionContext tx) {
            if (!stillFull() || fluid != contained || maxAmount < amount) {
                return 0;
            }
            ItemResource current = access.getResource();
            ItemResource emptied;
            if (fullItem == Items.POTION) {
                ItemStack stack = current.toStack();
                stack.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
                emptied = ItemResource.of(Items.GLASS_BOTTLE, stack.getComponentsPatch());
            } else {
                emptied = ItemResource.of(Items.BUCKET, current.getComponentsPatch());
            }
            return access.exchange(emptied, 1, tx) == 1 ? amount : 0;
        }

        @Override
        public long insert(Fluid fluid, long maxAmount, TransactionContext tx) {
            return 0;
        }
    }

    /**
     * Another mod's fluid container item, through NeoForge's item capability:
     * whole millibuckets, converted at the edge.
     */
    private record ForeignContainer(ResourceHandler<FluidResource> handler)
            implements Hand {

        private static int toMb(long droplets) {
            return (int) Math.min(droplets / TankOps.DROPLETS_PER_MB,
                    Integer.MAX_VALUE);
        }

        @Override
        public int step() {
            return TankOps.DROPLETS_PER_MB;
        }

        @Override
        public int views() {
            return handler.size();
        }

        @Override
        public Fluid viewFluid(int view) {
            FluidResource resource = handler.getResource(view);
            if (resource.isEmpty() || !resource.isComponentsPatchEmpty()) {
                return Fluids.EMPTY;
            }
            Fluid fluid = TankOps.normalizeOrNull(resource.getFluid());
            return fluid == null ? Fluids.EMPTY : fluid;
        }

        @Override
        public long extract(int view, Fluid fluid, long maxAmount, TransactionContext tx) {
            int mb = toMb(maxAmount);
            return mb == 0 ? 0 : (long) handler.extract(view, FluidResource.of(fluid),
                    mb, tx) * TankOps.DROPLETS_PER_MB;
        }

        @Override
        public long insert(Fluid fluid, long maxAmount, TransactionContext tx) {
            int mb = toMb(maxAmount);
            return mb == 0 ? 0 : (long) handler.insert(FluidResource.of(fluid), mb, tx)
                    * TankOps.DROPLETS_PER_MB;
        }
    }
}
