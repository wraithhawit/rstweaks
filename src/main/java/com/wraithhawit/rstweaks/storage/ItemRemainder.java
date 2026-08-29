package com.wraithhawit.rstweaks.storage;

import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.wraithhawit.rstweaks.ledger.rs.Remainder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The Minecraft-backed {@link Remainder}, kept out of the ledger package so the model stays
 * runnable without the game — the same arrangement as {@link ItemDurability}.
 *
 * <p>The game already knows what a milk bucket leaves behind: it is the crafting remainder, the
 * mechanism that hands your bucket back when you bake a cake. Reading it here is what turns a
 * container from an opaque item Refined Storage consumes into a slot with a fate, and it is why a
 * thousand cakes stop planning three thousand buckets.
 *
 * <h2>What this reaches, and what it does not</h2>
 *
 * <p><b>Vanilla crafting containers only.</b> Buckets, bottles, and anything else that declares a
 * {@code craftRemainder}. A <em>processing</em> recipe that returns its container through a machine
 * — a crucible handed back by a smelter, a canning machine's empty can — has no crafting remainder
 * and never will, because nothing in the item declares that relationship. Those byproducts stay
 * unattributed, which is exactly what Refined Storage does with them today: a failed inference
 * costs an optimisation and never an item.
 *
 * <p><b>A remainder is a candidate, not a conclusion.</b> {@code PatternTransforms} only ever uses
 * this answer to match a byproduct the pattern <em>already lists</em>. That matters for the mods
 * that roll dice when handing an ingredient back — Cucumber's reusable items check Unbreaking with
 * {@code Math.random()} — because Refined Storage baked one draw into the pattern when it was
 * encoded. If our answer disagrees with that frozen draw, nothing matches and the slot is simply
 * consumed. It cannot invent a return that the pattern does not promise.
 */
public final class ItemRemainder implements Remainder {
    public static final ItemRemainder INSTANCE = new ItemRemainder();

    /**
     * Per-item, because the answer is a property of the item and this is called once per
     * ingredient per pattern per plan — on Refined Storage's autocrafting threads, hence the
     * concurrent map.
     */
    private final Map<Item, ItemResource> cache = new ConcurrentHashMap<>();

    private static final ItemResource NONE_MARKER =
        ItemResource.ofItemStack(new ItemStack(net.minecraft.world.item.Items.AIR, 1));

    private ItemRemainder() {
    }

    @Nullable
    @Override
    public ResourceKey remainderOf(final ResourceKey input) {
        if (!(input instanceof ItemResource item)) {
            return null;
        }
        final ItemResource cached = this.cache.computeIfAbsent(item.item(), key -> {
            final ItemStack remainder = new ItemStack(key, 1).getCraftingRemainingItem();
            return remainder.isEmpty() ? NONE_MARKER : ItemResource.ofItemStack(remainder);
        });
        if (cached == NONE_MARKER || cached.equals(input)) {
            // An item that is its own remainder is a catalyst, and the first inference rule
            // already covers that by exact resource. Saying it twice here would only add a
            // second way for the two rules to disagree.
            return null;
        }
        return cached;
    }
}
