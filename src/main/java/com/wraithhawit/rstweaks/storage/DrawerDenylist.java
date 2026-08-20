package com.wraithhawit.rstweaks.storage;

import com.wraithhawit.rstweaks.Stats;

import it.unimi.dsi.fastutil.objects.Reference2BooleanMap;
import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/**
 * Answers "is this item on the drawer denylist" from a per-{@link Item} cache instead of a
 * tag lookup.
 *
 * <p>Diagnosis, from a 60-second server-thread profile of a Refined Storage network with an
 * External Storage pointed at Functional Storage drawers:
 * {@code ImmutableCollections$SetN.probe} was <b>11.6% of the server thread</b>, the second
 * largest self frame in the whole profile, reached entirely through
 * {@code ItemStack.is(TagKey)} from {@code BigInventoryHandler}.
 *
 * <p>{@code BigInventoryHandler} consults {@code DRAWER_STORAGE_DENYLIST} <b>twice per
 * insert attempt</b> — once at the top of {@code insertItem}, then again inside the
 * {@code isValid} it goes on to call. Same stack, same tag, same answer, and Refined Storage
 * drives that path once per slot per returned craft output.
 *
 * <p>The reason each lookup is not free is worth stating, because "it is just a set
 * contains" is why nobody looks twice at it. {@code stack.is(tag)} resolves to
 * {@code holder.tags.contains(tag)} over an {@link java.util.Set} that is, for an item in a
 * large modded pack, an {@code ImmutableCollections.SetN} holding dozens of entries. Its
 * {@code probe} does a {@code Math.floorMod} — an integer division — and then walks the
 * table comparing {@code TagKey} records, each comparison descending into
 * {@code ResourceLocation.equals} and two {@code String.equals} calls. A <em>miss</em>, which
 * is the overwhelmingly common answer here, probes until it reaches a null slot rather than
 * stopping early. That is the 11.6%: not one slow call, an enormous number of moderately
 * expensive ones.
 *
 * <p>Keyed on {@link Item} rather than on the stack, because that is what the answer actually
 * depends on — a tag holds items, and no component, count or damage value can change
 * membership. A reference-keyed fastutil map makes each lookup an identity hash and one array
 * index, with no division and no string comparison.
 *
 * <p><b>Invalidation is total and comes from the game itself.</b> Tag contents change exactly
 * once, on {@link TagsUpdatedEvent} — datapack load and every {@code /reload} after it — and
 * the whole map is dropped there. There is no expiry to tune and no staleness window: between
 * two of those events the answer is immutable, so a cached answer and a fresh lookup cannot
 * disagree. This is the same argument as the Drawer Controller membership cache: cache what
 * is structural, never what is contents.
 */
public final class DrawerDenylist {

    /**
     * Reference-keyed on purpose. {@link Item} instances are registry singletons, so identity
     * is the correct comparison and the cheapest one; {@code Item} does not override
     * {@code hashCode}, so a value-keyed map would do the same thing more slowly.
     */
    private static final Reference2BooleanMap<Item> DENIED = new Reference2BooleanOpenHashMap<>();

    private DrawerDenylist() {
    }

    /**
     * Only ever called from the server thread — Functional Storage's insert path — so the map
     * needs no synchronisation. {@link TagsUpdatedEvent} also fires on the server thread.
     */
    /**
     * The tag is handed in rather than imported so this class carries no Functional Storage
     * reference. It is registered on the event bus unconditionally, and a pack without that
     * mod would otherwise fail to load the class. Only ever called with
     * {@code DRAWER_STORAGE_DENYLIST}, which is why keying on the item alone is sufficient.
     */
    public static boolean isDenied(final ItemStack stack, final TagKey<Item> tag) {
        final Item item = stack.getItem();
        if (DENIED.containsKey(item)) {
            Stats.drawerDenylistLookupsAvoided++;
            return DENIED.getBoolean(item);
        }
        final boolean denied = stack.is(tag);
        DENIED.put(item, denied);
        return denied;
    }

    @SubscribeEvent
    public static void onTagsUpdated(final TagsUpdatedEvent event) {
        // Unconditional: this fires a handful of times per launch, and working out which
        // items moved would cost more than rebuilding lazily on demand.
        DENIED.clear();
    }
}
