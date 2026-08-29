package com.wraithhawit.rstweaks.iface;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Which items can carry an Inventory Interface.
 *
 * <p>An allowlist of ids, not a class test. The obvious class test — "anything extending
 * {@code AbstractNetworkEnergyItem}" — also catches the Wireless Autocrafting Monitor and the
 * Wireless Security Manager, neither of which has an inventory to interface with, and it would
 * silently adopt every future network-bound item an addon adds. Ids are also the only way to
 * reach a Portable Grid, which is a {@code BlockItem} and shares no supertype with the wireless
 * ones.
 *
 * <p>Ids rather than classes has a second consequence that matters more: nothing here references
 * a class from Quartz Arsenal or Universal Grid, so neither becomes a dependency, neither needs a
 * mixin config of its own, and a pack without them resolves those two ids to nothing at all. The
 * list is config, so an addon nobody here has heard of is a line in a file rather than a build.
 *
 * <p>Resolved once and cached, because {@link #isSupported(ItemStack)} is called for every stack
 * in every player's inventory on every pass. {@link #invalidate()} is wired to config reload.
 */
public final class SupportedGrids {
    /**
     * The grids in this pack, in the order they were added to it. Creative variants are included
     * deliberately — a creative Wireless Grid is exactly the item somebody testing this feature
     * reaches for first, and leaving it out reads as the feature being broken.
     */
    public static final List<String> DEFAULTS = List.of(
        "refinedstorage:wireless_grid",
        "refinedstorage:creative_wireless_grid",
        "refinedstorage:portable_grid",
        "refinedstorage:creative_portable_grid",
        "refinedstorage_quartz_arsenal:wireless_crafting_grid",
        "refinedstorage_quartz_arsenal:creative_wireless_crafting_grid",
        "universalgrid:wireless_universal_grid",
        "universalgrid:creative_wireless_universal_grid"
    );

    private static volatile Set<Item> resolved;

    private SupportedGrids() {
    }

    public static boolean isSupported(final ItemStack stack) {
        return !stack.isEmpty() && items().contains(stack.getItem());
    }

    /**
     * The resolved items, for Refined Storage's slot-reference provider, which asks for a set of
     * items rather than a predicate.
     */
    public static Set<Item> items() {
        Set<Item> current = resolved;
        if (current == null) {
            synchronized (SupportedGrids.class) {
                current = resolved;
                if (current == null) {
                    current = resolve();
                    resolved = current;
                }
            }
        }
        return current;
    }

    public static void invalidate() {
        resolved = null;
    }

    private static Set<Item> resolve() {
        final List<? extends String> configured = Config.INVENTORY_INTERFACE_ITEMS.get();
        final Set<Item> items = new LinkedHashSet<>(configured.size());
        final Set<String> unknown = new HashSet<>();
        for (final String id : configured) {
            final ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null) {
                unknown.add(id);
                continue;
            }
            // An id from a mod that is not installed is the normal case for this list, not an
            // error: the defaults name two addons on purpose. Only a malformed id, or one whose
            // namespace IS present, is worth a word - and telling those apart is not worth the
            // code, so this stays at debug.
            BuiltInRegistries.ITEM.getOptional(location).ifPresentOrElse(items::add, () -> unknown.add(id));
        }
        if (!unknown.isEmpty()) {
            RSTweaks.LOGGER.debug("[rstweaks] inventory interface: no such item(s) {}", unknown);
        }
        return Set.copyOf(items);
    }
}
