package com.wraithhawit.rstweaks.mekanism;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.external.ExternalStorageProvider;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;

import com.wraithhawit.rstweaks.storage.TypedExternalStorageProvider;

import mekanism.api.inventory.IHashedItem;
import mekanism.api.inventory.qio.IQIOComponent;
import mekanism.api.inventory.qio.IQIOFrequency;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Exposes a Mekanism QIO frequency to a Refined Storage External Storage.
 *
 * <p>External Storage normally works through capabilities: it asks the block in front of it
 * for an {@code IItemHandler} and wraps it. QIO has no such handler — its contents live in a
 * frequency shared by every dashboard, drive array and importer tuned to it, not in the block
 * you are pointing at — so there is nothing for stock RS to attach to and an External Storage
 * against a dashboard reads as empty.
 *
 * <p>Mekanism does publish the frequency itself, in {@code mekanism.api}: a QIO block entity is
 * an {@link IQIOComponent}, and the {@link IQIOFrequency} it hands back offers exactly the three
 * operations this interface needs. Nothing here reaches into Mekanism's internals and no mixin is
 * involved; RS's own {@code addExternalStorageProviderFactory} is a public registration point.
 *
 * <p><strong>Read-only was considered and rejected.</strong> The External Storage already has an
 * Access Mode in its GUI, so a player who wants insert-only or extract-only can say so there;
 * hard-coding it here would take that choice away and would also stop autocrafting from ever
 * using QIO as a destination.
 */
class QioExternalStorageProvider implements ExternalStorageProvider, TypedExternalStorageProvider {
    private final ServerLevel level;
    private final BlockPos pos;

    /**
     * The block entity, not the frequency: the frequency a dashboard answers with changes when
     * the player retunes it, and caching that would silently keep reading the old network. The
     * block entity is stable until the block is broken, which {@code isRemoved} reports.
     */
    @Nullable
    private BlockEntity cachedBlockEntity;

    QioExternalStorageProvider(final ServerLevel level, final BlockPos pos) {
        this.level = level;
        this.pos = pos;
    }

    @Nullable
    private IQIOFrequency frequency() {
        BlockEntity blockEntity = this.cachedBlockEntity;
        if (blockEntity == null || blockEntity.isRemoved()) {
            blockEntity = this.level.getBlockEntity(this.pos);
            this.cachedBlockEntity = blockEntity;
        }
        if (!(blockEntity instanceof IQIOComponent qio)) {
            return null;
        }
        // Null whenever the dashboard has no frequency selected yet, which is how it leaves the
        // crafting table.
        return qio.getQIOFrequency();
    }

    /**
     * QIO stores items and only items, so a network pulling fluids or Mekanism chemicals never
     * needs to ask this provider. See {@link TypedExternalStorageProvider} for why this is safe
     * to answer up front and a remembered zero would not be.
     */
    @Override
    public boolean rstweaks$serves(final ResourceKey resource) {
        return resource instanceof ItemResource;
    }

    @Override
    public long insert(final ResourceKey resource, final long amount, final Action action,
                       final Actor actor) {
        if (!(resource instanceof ItemResource item) || amount <= 0) {
            return 0L;
        }
        final IQIOFrequency frequency = this.frequency();
        if (frequency == null) {
            return 0L;
        }
        // massInsert takes the stack as a type rather than as an amount -- a single-count stack
        // plus a long -- and returns how much it accepted, which is RS's contract too.
        return frequency.massInsert(item.toItemStack(), amount, toMekanismAction(action));
    }

    @Override
    public long extract(final ResourceKey resource, final long amount, final Action action,
                        final Actor actor) {
        if (!(resource instanceof ItemResource item) || amount <= 0) {
            return 0L;
        }
        final IQIOFrequency frequency = this.frequency();
        if (frequency == null) {
            return 0L;
        }
        return frequency.massExtract(item.toItemStack(), amount, toMekanismAction(action));
    }

    /**
     * A snapshot, because RS's change detection walks the whole iterator and compares it against
     * the last one; a live view over the frequency's map would be a concurrent modification the
     * first time the same tick inserted anything.
     *
     * <p>{@code forAllHashedStored} rather than {@code forAllStored}: the hashed form hands back
     * the stored type without building a fresh {@code ItemStack} per entry, and a QIO holding
     * thousands of types is iterated on every change-detection pass.
     */
    @Override
    public Iterator<ResourceAmount> iterator() {
        final IQIOFrequency frequency = this.frequency();
        if (frequency == null) {
            return List.<ResourceAmount>of().iterator();
        }
        final List<ResourceAmount> contents = new ArrayList<>();
        frequency.forAllHashedStored((IHashedItem hashed, long count) -> {
            // ResourceAmount rejects a non-positive amount outright. QIO drops an item type when
            // its count reaches zero, so this should never fire -- but it throws rather than
            // returning, and one bad entry would take the whole storage down with it.
            if (count <= 0L) {
                return;
            }
            final ItemStack stack = hashed.getInternalStack();
            contents.add(new ResourceAmount(ItemResource.ofItemStack(stack), count));
        });
        return contents.iterator();
    }

    private static mekanism.api.Action toMekanismAction(final Action action) {
        return action == Action.EXECUTE ? mekanism.api.Action.EXECUTE : mekanism.api.Action.SIMULATE;
    }
}
