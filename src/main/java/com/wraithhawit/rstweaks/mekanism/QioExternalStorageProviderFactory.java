package com.wraithhawit.rstweaks.mekanism;

import com.refinedmods.refinedstorage.api.storage.external.ExternalStorageProvider;
import com.refinedmods.refinedstorage.common.api.storage.externalstorage.ExternalStorageProviderFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Hands every External Storage a QIO provider, whatever it is pointed at.
 *
 * <p>Deliberately not "check for a dashboard here and return an empty provider otherwise".
 * Refined Storage builds this list once, when the External Storage loads, and rebuilds it only
 * on a block state change — so a factory that decided at creation time would answer "not QIO"
 * forever for a dashboard placed after the External Storage. Stock RS has the same problem and
 * solves it the same way: its item and fluid factories always return a provider, and the
 * capability lookup inside is what happens per call.
 *
 * <p>The cost of the extra provider on the other few thousand External Storages in a pack is one
 * cached block entity lookup and an {@code instanceof}, and only for item requests — see
 * {@code rstweaks$serves}.
 */
public class QioExternalStorageProviderFactory implements ExternalStorageProviderFactory {
    @Override
    public ExternalStorageProvider create(final ServerLevel level, final BlockPos pos,
                                          final Direction direction) {
        // Direction is unused on purpose: a QIO frequency is not stored in the block being
        // pointed at and has no sides, so every face of a dashboard sees the same contents.
        return new QioExternalStorageProvider(level, pos);
    }
}
