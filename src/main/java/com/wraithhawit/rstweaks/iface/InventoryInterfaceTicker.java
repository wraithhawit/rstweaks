package com.wraithhawit.rstweaks.iface;

import java.util.List;
import java.util.Optional;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.filter.FilterMode;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;

import com.wraithhawit.rstweaks.Config;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import org.jetbrains.annotations.Nullable;

/**
 * Runs the Inventory Interface: the half of the feature that happens while nobody is looking.
 *
 * <p>Both directions read one filter list, and the amount on a filter entry means the same thing
 * in each: how many of that resource to keep on you. Insert files away the surplus, export tops up
 * the shortfall. See {@link InventoryInterfaceState} for what each filter mode does with that.
 *
 * <h2>Cost</h2>
 *
 * <p>This is a mod for making Refined Storage cost less per tick, so a feature of ours that polls
 * every player every tick would be indefensible. It does not: a pass happens once every
 * {@code inventoryInterfaceIntervalTicks} (20 by default), and players are staggered across that
 * window by entity id so a full server does not do all of them on the same tick. A pass on a
 * player carrying no configured grid is 41 {@code get} calls against stacks whose component patch
 * is empty. Nothing resolves a network, touches a disk, or allocates until a stack turns up
 * carrying a configuration that is switched on.
 *
 * <h2>What it refuses to touch</h2>
 *
 * <p>Auto-insert is the half that can ruin an afternoon — a BLOCK-mode filter is a standing
 * instruction to file away everything you did not name, and the things you did not name include
 * the pickaxe you are holding. So it never takes the held item, never takes armour or the offhand,
 * never takes another grid (filing your wireless grid into your network is a walk home), and by
 * default never takes anything from the hotbar at all. The hotbar rule is the one that is a
 * judgement rather than a safety rail, so it is the one that is config.
 */
public final class InventoryInterfaceTicker {
    private InventoryInterfaceTicker() {
    }

    @SubscribeEvent
    public static void onPlayerTick(final PlayerTickEvent.Post event) {
        if (!Config.inventoryInterface || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        final int interval = Config.inventoryInterfaceIntervalTicks;
        if (interval <= 0) {
            return;
        }
        // Staggered by entity id rather than all landing on the same tick. Math.floorMod because
        // entity ids are not guaranteed positive and a negative remainder would simply never
        // match, silently switching the feature off for that player.
        if (Math.floorMod(player.tickCount + player.getId(), interval) != 0) {
            return;
        }
        run(player);
    }

    /**
     * Every grid the player is carrying, wherever it is kept.
     *
     * <p>Not an inventory scan: a grid worn in a Curios slot is reached through Refined Storage's
     * own composite provider and would be invisible to one. See {@link SupportedGrids#carriedBy}.
     */
    private static void run(final ServerPlayer player) {
        final Inventory inventory = player.getInventory();
        for (final SlotReference slotReference : SupportedGrids.carriedBy(player)) {
            final ItemStack stack = slotReference.resolve(player).orElse(null);
            if (stack == null) {
                continue;
            }
            final InventoryInterfaceState state = stack.get(InventoryInterfaceContent.STATE.get());
            if (state == null || !state.isActive()) {
                continue;
            }
            serve(player, inventory, slotReference, stack, state);
        }
    }

    private static void serve(final ServerPlayer player,
                              final Inventory inventory,
                              final SlotReference slotReference,
                              final ItemStack gridStack,
                              final InventoryInterfaceState state) {
        final Optional<InventoryInterfaceTarget> resolved =
            InventoryInterfaceTarget.of(player, gridStack, slotReference);
        if (resolved.isEmpty()) {
            return;
        }
        final InventoryInterfaceTarget target = resolved.get();
        final Actor actor = new PlayerActor(player);
        if (state.insert() && target.mayInsert(player)) {
            insert(inventory, slotReference, state, target, actor);
        }
        if (state.export() && target.mayExtract(player)) {
            export(player, inventory, slotReference, state, target, actor);
        }
    }

    /**
     * Slots auto-insert will not take from, whatever the filter says.
     *
     * <p>The grid doing the filing, the item in your hand, and — unless
     * {@code inventoryInterfaceInsertFromHotbar} says otherwise — the whole hotbar. Armour and the
     * offhand are outside this range entirely, so they are never candidates in the first place.
     *
     * <p>The grid's own slot is asked of the reference rather than compared to an index, because a
     * grid does not have to be in the inventory at all. {@code isDisabledSlot} is exactly this
     * question — it exists so a menu can grey out the slot its item came from — and a grid in a
     * Curios slot correctly answers false to every inventory index, having none to protect.
     */
    private static boolean isProtected(final Inventory inventory,
                                       final SlotReference slotReference,
                                       final int slot) {
        if (slotReference.isDisabledSlot(slot) || slot == inventory.selected) {
            return true;
        }
        return !Config.inventoryInterfaceInsertFromHotbar && slot < Inventory.getSelectionSize();
    }

    private static void insert(final Inventory inventory,
                               final SlotReference slotReference,
                               final InventoryInterfaceState state,
                               final InventoryInterfaceTarget target,
                               final Actor actor) {
        final List<Optional<ResourceAmount>> filter = state.filter();
        // How much of each listed resource is still owed to the player, walked down as the pass
        // goes. "Keep 64" is a fact about the inventory as a whole, not about one slot, and
        // walking a per-entry budget keeps the first stacks found and files away the rest.
        final long[] keepLeft = new long[filter.size()];
        for (int i = 0; i < filter.size(); ++i) {
            keepLeft[i] = filter.get(i).map(ResourceAmount::amount).orElse(0L);
        }
        final int mainSize = inventory.items.size();
        // What is in the slots this pass will not touch still counts against the budget. Without
        // this, "keep 64" means 64 outside the hotbar PLUS whatever is in it, and the export half
        // -- which counts the whole inventory -- disagrees with the insert half about the same
        // number. Sixteen in the hotbar and sixty-four in the bag would settle at eighty.
        for (int slot = 0; slot < mainSize; ++slot) {
            if (!isProtected(inventory, slotReference, slot)) {
                continue;
            }
            final ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            final int match = indexOf(state, ItemResource.ofItemStack(stack));
            if (match >= 0) {
                keepLeft[match] = Math.max(0L, keepLeft[match] - stack.getCount());
            }
        }
        for (int slot = 0; slot < mainSize; ++slot) {
            if (isProtected(inventory, slotReference, slot)) {
                continue;
            }
            final ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || SupportedGrids.isSupported(stack)) {
                continue;
            }
            final ItemResource resource = ItemResource.ofItemStack(stack);
            final int match = indexOf(state, resource);
            final long surplus;
            if (state.filterMode() == FilterMode.ALLOW) {
                if (match < 0) {
                    continue;
                }
                final long kept = Math.min(stack.getCount(), keepLeft[match]);
                keepLeft[match] -= kept;
                surplus = stack.getCount() - kept;
            } else {
                // BLOCK: a listed resource is yours, whatever its amount says. The amount is still
                // read -- by export, which tops it back up. "Do not put these away, and keep me
                // stocked with them" is a coherent thing to ask for and this is where it is said.
                if (match >= 0) {
                    continue;
                }
                surplus = stack.getCount();
            }
            if (surplus <= 0L) {
                continue;
            }
            final long inserted = target.insert(resource, surplus, actor);
            if (inserted <= 0L) {
                continue;
            }
            stack.shrink((int) inserted);
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
            inventory.setChanged();
        }
    }

    private static void export(final ServerPlayer player,
                               final Inventory inventory,
                               final SlotReference slotReference,
                               final InventoryInterfaceState state,
                               final InventoryInterfaceTarget target,
                               final Actor actor) {
        for (final Optional<ResourceAmount> entry : state.filter()) {
            if (entry.isEmpty() || !(entry.get().resource() instanceof ItemResource resource)) {
                continue;
            }
            final long want = entry.get().amount();
            if (want <= 0L) {
                continue;
            }
            final long missing = want - count(inventory, state, resource);
            if (missing <= 0L) {
                continue;
            }
            final ItemStack sample = resource.toItemStack();
            // One stack per entry per pass. A player who has just emptied a filter slot's worth of
            // blocks would otherwise pull the whole shortfall in a single tick, and the point of
            // the cap is that the work per pass stays bounded no matter what the filter says.
            final long take = Math.min(Math.min(missing, sample.getMaxStackSize()), room(inventory, slotReference, sample));
            if (take <= 0L) {
                continue;
            }
            final long extracted = target.extract(resource, take, actor);
            if (extracted <= 0L) {
                continue;
            }
            give(player, inventory, target, actor, resource, extracted);
        }
    }

    /**
     * Puts an extracted amount in the player's inventory, and makes sure that every item that came
     * out of storage ends up somewhere.
     *
     * <p>{@link #room} was measured a moment ago, so the ordinary path places all of it. The
     * leftover path exists because "a moment ago" is not "now" — the same tick can have moved the
     * inventory underneath us. Items that will not fit go back into storage; items that will not
     * go back are dropped at the player's feet. Neither branch is expected to run, and neither is
     * allowed to be a {@code return} that quietly ends the count somewhere below where it started:
     * an item that leaves a disk and reaches no inventory has been destroyed, which is the one
     * bug this mod has shipped before and must not ship again.
     */
    private static void give(final ServerPlayer player,
                             final Inventory inventory,
                             final InventoryInterfaceTarget target,
                             final Actor actor,
                             final ItemResource resource,
                             final long extracted) {
        long remaining = extracted;
        final int maxStackSize = resource.toItemStack().getMaxStackSize();
        while (remaining > 0L) {
            final ItemStack toAdd = resource.toItemStack(Math.min(remaining, maxStackSize));
            final int before = toAdd.getCount();
            inventory.add(toAdd);
            final int placed = before - toAdd.getCount();
            if (placed <= 0) {
                break;
            }
            remaining -= placed;
        }
        if (remaining <= 0L) {
            inventory.setChanged();
            return;
        }
        inventory.setChanged();
        final long returned = target.insert(resource, remaining, actor);
        remaining -= returned;
        while (remaining > 0L) {
            final long dropped = Math.min(remaining, maxStackSize);
            player.drop(resource.toItemStack(dropped), false);
            remaining -= dropped;
        }
    }

    /** How many of a resource the player is carrying, by the same matching rule the filter uses. */
    private static long count(final Inventory inventory,
                              final InventoryInterfaceState state,
                              final ItemResource resource) {
        long total = 0L;
        for (int slot = 0; slot < inventory.items.size(); ++slot) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (matches(state, resource, ItemResource.ofItemStack(stack))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** How many more of a stack the player's main inventory could hold. */
    private static long room(final Inventory inventory,
                             final SlotReference slotReference,
                             final ItemStack sample) {
        final int maxStackSize = sample.getMaxStackSize();
        long free = 0L;
        for (int slot = 0; slot < inventory.items.size(); ++slot) {
            if (slotReference.isDisabledSlot(slot)) {
                continue;
            }
            final ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                free += maxStackSize;
            } else if (ItemStack.isSameItemSameComponents(stack, sample)) {
                free += Math.max(0, maxStackSize - stack.getCount());
            }
        }
        return free;
    }

    /** The filter slot a resource matches, or -1. */
    private static int indexOf(final InventoryInterfaceState state, final ItemResource resource) {
        final List<Optional<ResourceAmount>> filter = state.filter();
        for (int i = 0; i < filter.size(); ++i) {
            final Optional<ResourceAmount> entry = filter.get(i);
            if (entry.isPresent()
                && entry.get().resource() instanceof ItemResource configured
                && matches(state, configured, resource)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Fuzzy mode compares normalised resources, which is Refined Storage's own definition of
     * "the same item ignoring its components" — the one that makes a filter for a pickaxe match a
     * pickaxe that has been used.
     */
    private static boolean matches(final InventoryInterfaceState state,
                                   final ItemResource configured,
                                   final ItemResource candidate) {
        return state.fuzzyMode()
            ? configured.normalize().equals(candidate.normalize())
            : configured.equals(candidate);
    }
}
