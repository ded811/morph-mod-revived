package com.deds.api.neoforge;

import com.deds.api.registry.TabRegistrar;

import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackLinkedSet;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * The NeoForge side of {@code TabRegistrar}'s three vanilla-tab operations,
 * reproducing what Fabric's {@code CreativeModeTabEvents.modifyOutputEvent}
 * and {@code FabricCreativeModeTabOutput} do, rule for rule.
 *
 * <p>Fabric hands its listeners the tab's finished lists as plain lists
 * (duplicates allowed, feature-disabled items already filtered out by
 * vanilla), and copies them back into vanilla's sets afterwards, so a
 * duplicate keeps its FIRST occurrence. NeoForge's
 * {@link BuildCreativeModeTabContentsEvent} instead exposes insertion-ordered
 * sets that throw on a duplicate and still contain feature-disabled items
 * (vanilla drops those later). So each operation runs here on a plain copy of
 * each list, with Fabric's exact rules:</p>
 * <ul>
 * <li>append ({@code addToVanilla}, {@code addStacksToVanilla}): a stack
 *     whose item is feature-disabled is skipped; an empty stack or a count
 *     other than 1 throws Fabric's {@code IllegalArgumentException};
 *     otherwise it goes on the end of both lists;</li>
 * <li>{@code insertAfterInVanilla}: nothing for an empty supplier; if no
 *     enabled stack of the anchor item is in the tab, every entry is appended
 *     as above; otherwise the enabled entries go, as one block, right after
 *     the LAST enabled stack of the anchor item, in each list separately
 *     (appended to a list that has no such stack);</li>
 * </ul>
 * and the result is written back with the first occurrence of each stack
 * kept. The operations of one tab run in registration order.
 */
final class VanillaTabOps {

    private interface Op {
        void apply(List<ItemStack> parent, List<ItemStack> search,
                FeatureFlagSet flags);
    }

    private record Registered(ResourceKey<CreativeModeTab> tab, Op op) {
    }

    private static final List<Registered> OPS = new CopyOnWriteArrayList<>();

    private VanillaTabOps() {
    }

    private static ResourceKey<CreativeModeTab> key(TabRegistrar.VanillaTab tab) {
        return ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace(tab.path()));
    }

    static void append(TabRegistrar.VanillaTab tab, Supplier<List<ItemStack>> stacks) {
        OPS.add(new Registered(key(tab), (parent, search, flags) -> {
            for (ItemStack stack : stacks.get()) {
                accept(stack, parent, search, flags);
            }
        }));
    }

    static void insertAfter(TabRegistrar.VanillaTab tab, ItemLike anchor,
            Supplier<List<ItemStack>> stacks) {
        OPS.add(new Registered(key(tab), (parent, search, flags) -> {
            List<ItemStack> entries = stacks.get();
            if (entries.isEmpty()) {
                return;
            }
            Item anchorItem = anchor.asItem();
            if (lastIndexOf(parent, anchorItem, flags) < 0) {
                for (ItemStack stack : entries) {
                    accept(stack, parent, search, flags);
                }
                return;
            }
            List<ItemStack> enabled = entries.stream()
                    .filter(s -> s.getItem().isEnabled(flags)).toList();
            if (enabled.isEmpty()) {
                return;
            }
            enabled.forEach(VanillaTabOps::checkStack);
            insertAfterLast(parent, anchorItem, enabled, flags);
            insertAfterLast(search, anchorItem, enabled, flags);
        }));
    }

    private static void accept(ItemStack stack, List<ItemStack> parent,
            List<ItemStack> search, FeatureFlagSet flags) {
        if (stack.getItem().isEnabled(flags)) {
            checkStack(stack);
            parent.add(stack);
            search.add(stack);
        }
    }

    private static void checkStack(ItemStack stack) {
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("Cannot add empty stack");
        }
        if (stack.getCount() != 1) {
            throw new IllegalArgumentException(
                    "Stack size must be exactly 1 for stack: " + stack);
        }
    }

    /** The last stack of {@code item} that Fabric's lists would contain. */
    private static int lastIndexOf(List<ItemStack> list, Item item,
            FeatureFlagSet flags) {
        for (int i = list.size() - 1; i >= 0; i--) {
            ItemStack s = list.get(i);
            if (s.is(item) && s.getItem().isEnabled(flags)) {
                return i;
            }
        }
        return -1;
    }

    private static void insertAfterLast(List<ItemStack> list, Item anchor,
            List<ItemStack> entries, FeatureFlagSet flags) {
        int at = lastIndexOf(list, anchor, flags);
        if (at < 0) {
            list.addAll(entries);
        } else {
            list.addAll(at + 1, entries);
        }
    }

    /**
     * From {@link DedsApiNeoForge}: applies this tab's operations and writes
     * the lists back, first occurrence kept, through the event's own
     * mutators (the sets it exposes are read-only views).
     */
    static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        List<Op> ops = new ArrayList<>();
        for (Registered registered : OPS) {
            if (registered.tab().equals(event.getTabKey())) {
                ops.add(registered.op());
            }
        }
        if (ops.isEmpty()) {
            return;
        }
        List<ItemStack> parent = new ArrayList<>(event.getParentEntries());
        List<ItemStack> search = new ArrayList<>(event.getSearchEntries());
        for (Op op : ops) {
            op.apply(parent, search, event.getFlags());
        }
        writeBack(event, parent, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
        writeBack(event, search, CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY);
    }

    private static void writeBack(BuildCreativeModeTabContentsEvent event,
            List<ItemStack> stacks, CreativeModeTab.TabVisibility visibility) {
        java.util.Set<ItemStack> firstOccurrence =
                ItemStackLinkedSet.createTypeAndComponentsSet();
        firstOccurrence.addAll(stacks);
        event.removeIf(stack -> true, visibility);
        for (ItemStack stack : firstOccurrence) {
            event.accept(stack, visibility);
        }
    }
}
