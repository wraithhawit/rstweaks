package com.wraithhawit.rstweaks;

import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

import com.wraithhawit.rstweaks.mekanism.MekanismQio;
import com.wraithhawit.rstweaks.planner.Durability;
import com.wraithhawit.rstweaks.storage.DrawerDenylist;
import com.wraithhawit.rstweaks.storage.ItemDurability;
import com.wraithhawit.rstweaks.test.CraftingGridRefillGameTest;
import com.wraithhawit.rstweaks.test.RSTweaksGameTests;
import com.wraithhawit.rstweaks.test.SelfTestCommand;

import org.slf4j.Logger;

/**
 * Entry point for the Refined Storage tick-time optimizations.
 *
 * <p>This mod registers no content. All behaviour lives in {@code mixin/}, applied via
 * {@code rstweaks.mixins.json}. It briefly registered one data component, for fluid substitution;
 * that feature and its component were removed when a dedicated mod took over the job. The class
 * exists so FML has something to construct for the {@code rstweaks} modid, and so there is one
 * obvious place to log which optimizations are active when reading a spark profile back.
 */
@Mod(RSTweaks.MODID)
public class RSTweaks {
    public static final String MODID = "rstweaks";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Build version, read from the jar's own metadata rather than duplicated as a
     * constant — a hardcoded copy drifts from {@code gradle.properties} exactly when
     * it matters, which is while diagnosing whether the running jar is the one just
     * built. Surfaced in the startup log and the chat join message so a test result
     * can always be tied to a specific build.
     */
    public static String version = "unknown";

    public RSTweaks(IEventBus modEventBus, ModContainer modContainer) {
        version = modContainer.getModInfo().getVersion().toString();
        // The planner package holds no Minecraft types so it can be run headlessly;
        // durability is the one thing only the game can answer, so it is handed in.
        Durability.Holder.set(ItemDurability.INSTANCE);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        // The pattern-plan mixin reads its toggle on a path far too hot for a
        // config-spec lookup, so mirror it into a plain field whenever it changes.
        modEventBus.addListener(ModConfigEvent.Loading.class, event -> {
            Config.refresh();
            // Purely cosmetic, and only worth doing once per launch: NightConfig cannot
            // emit a blank line between entries, so the file arrives as a wall of '#'.
            ConfigFormatter.addBlankLinesBetweenEntries(event.getConfig().getFullPath());
        });
        modEventBus.addListener(ModConfigEvent.Reloading.class, event -> Config.refresh());
        NeoForge.EVENT_BUS.register(ServerTicks.class);
        // Only meaningful when Functional Storage is present, but registering a listener for
        // an event that never matters is free, and gating it on ModList here would run before
        // ModList is dependable.
        NeoForge.EVENT_BUS.register(DrawerDenylist.class);
        NeoForge.EVENT_BUS.register(ChatReporter.class);
        NeoForge.EVENT_BUS.register(SelfTestCommand.class);
        // Only fires when -Dneoforge.enabledGameTestNamespaces includes this mod;
        // the command above is the version that works without a launch flag.
        // Common setup rather than here: Refined Storage installs its API delegate and registers
        // its own external storage providers in its mod constructor, and constructor order
        // between two mods is not something to rely on. ModList is only dependable this late too.
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> {
            // enqueueWork, not the listener body: common setup is dispatched in parallel across
            // mods, and RS keeps its factories in a plain ArrayList. The enqueued work runs on
            // the main thread, one mod at a time.
            if (MekanismQio.isAvailable()) {
                event.enqueueWork(MekanismQio::register);
            }
        });
        modEventBus.addListener(RegisterGameTestsEvent.class, event -> {
            event.register(RSTweaksGameTests.class);
            event.register(CraftingGridRefillGameTest.class);
        });
        LOGGER.info("[rstweaks] v{} loaded", version);
    }

    /**
     * What is actually running, rather than everything this mod can do.
     *
     * <p>Three optimizations target mods that are now optional, and their mixin configs
     * are skipped entirely when the mod is absent. Announcing them regardless would make
     * the startup line and the chat message claim work that is not happening — and those
     * two messages are the only evidence most people will ever look at.
     *
     * <p>Computed on first use, not during construction: {@code ModList} is not
     * dependable while mods are still being built.
     */
    public static synchronized String activeFeatures() {
        if (activeFeatures == null) {
            final List<String> features = new ArrayList<>(8);
            features.add("uncraftable cache");
            if (Config.waitForRunningCraft) {
                features.add("duplicate request suppression");
            }
            features.add("plan copy-on-write");
            features.add("external storage index");
            if (Config.skipEmptyCompositeExtract) {
                features.add("empty extract skip");
            }
            if (ModList.get().isLoaded("stepcrafter")) {
                features.add("step requester backoff");
            }
            if (ModList.get().isLoaded("cabletiers")) {
                features.add("tiered autocrafter lookup");
            }
            if (ModList.get().isLoaded("functionalstorage")) {
                features.add("drawer controller cache");
            }
            if (MekanismQio.isAvailable()) {
                features.add("QIO external storage");
            }
            if (Config.lpPlanner) {
                features.add("LP planner");
            }
            if (Config.durabilityAwarePlanning) {
                features.add("tool durability");
            }
            activeFeatures = String.join(", ", features);
        }
        return activeFeatures;
    }

    @Nullable
    private static String activeFeatures;
}
