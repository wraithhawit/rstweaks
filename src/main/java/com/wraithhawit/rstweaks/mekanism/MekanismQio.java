package com.wraithhawit.rstweaks.mekanism;

import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;

import com.wraithhawit.rstweaks.Config;
import com.wraithhawit.rstweaks.RSTweaks;

import net.neoforged.fml.ModList;

/**
 * The one place that knows Mekanism might not be installed.
 *
 * <p>Everything Mekanism-typed lives in {@link QioExternalStorageProvider}, which is only ever
 * loaded from here and only after {@link #isAvailable()} has said yes. Nothing on the mod's
 * startup path names a Mekanism class, so a pack without Mekanism never resolves one.
 *
 * <p>No mixin config accompanies this, unlike the Step Crafter and Cable Tiers integrations:
 * external storage providers are a public, documented Refined Storage extension point, so this
 * is a registration rather than a patch.
 */
public final class MekanismQio {
    private MekanismQio() {
    }

    public static boolean isAvailable() {
        return Config.mekanismQioExternalStorage && ModList.get().isLoaded("mekanism");
    }

    /**
     * Must run after Refined Storage's own mod constructor, which is where its API delegate is
     * installed and its own factories are registered — common setup is the first lifecycle
     * event that is guaranteed to be later than every constructor.
     */
    public static void register() {
        RefinedStorageApi.INSTANCE.addExternalStorageProviderFactory(
            new QioExternalStorageProviderFactory());
        RSTweaks.LOGGER.info("[rstweaks] External Storage can now read a Mekanism QIO Dashboard");
    }
}
