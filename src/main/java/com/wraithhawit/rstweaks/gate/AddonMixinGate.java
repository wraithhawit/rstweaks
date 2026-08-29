package com.wraithhawit.rstweaks.gate;

import com.wraithhawit.rstweaks.gate.UpstreamGate.Superseded;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModInfo;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Skips an addon mixin once the addon ships the same fix itself. See {@link UpstreamGate}.
 *
 * <p>{@code requiredMods} in {@code neoforge.mods.toml} already decides whether these configs load
 * at all, so by the time this runs the mod is present and only its version is in question.
 *
 * <p>Mixin plugins run during class transformation, long before {@code ModList} means anything —
 * the existing comments in {@code RSTweaks} say as much. {@link LoadingModList} is the one that is
 * populated this early, which is why the version is read from there and not from the API most of
 * this mod uses.
 */
public final class AddonMixinGate implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("rstweaks");

    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Nullable
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        final Superseded tweak = UpstreamGate.forMixin(mixinClassName);
        if (tweak == null) {
            return true;
        }
        final String installed = versionOf(tweak.modId());
        if (installed == null) {
            // Unreadable version. Apply, which is what this mod did before the gate existed,
            // and say so -- a withdrawn optimization must never be the quiet outcome.
            LOGGER.warn("[rstweaks] could not read the installed {} version; applying {} anyway. "
                + "If you are on {} or newer, that mod has its own fix and ours is redundant.",
                tweak.modId(), tweak.feature(), tweak.supersededAt());
            return true;
        }
        if (UpstreamGate.stillNeeded(tweak, installed)) {
            return true;
        }
        UpstreamGate.standDown(tweak, installed);
        LOGGER.info("[rstweaks] {} {} implements this itself (\"{}\") -- standing down our {}. "
            + "Nothing is wrong; the fix was upstreamed.",
            tweak.modId(), installed, tweak.upstreamNote(), tweak.feature());
        return false;
    }

    /** The loaded version of a mod, or null if it cannot be read at this stage. */
    @Nullable
    private static String versionOf(final String modId) {
        try {
            final List<ModInfo> mods = LoadingModList.get().getMods();
            for (final ModInfo mod : mods) {
                if (modId.equals(mod.getModId())) {
                    return mod.getVersion().toString();
                }
            }
        } catch (final RuntimeException | LinkageError e) {
            // Never let a version lookup stop the game from starting. The caller treats null
            // as "assume old", so the worst case is the behaviour of every previous release.
            LOGGER.warn("[rstweaks] version lookup for {} failed", modId, e);
        }
        return null;
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Nullable
    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass,
                         final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass,
                          final String mixinClassName, final IMixinInfo mixinInfo) {
    }
}
