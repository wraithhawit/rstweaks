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
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import com.wraithhawit.rstweaks.gate.UpstreamGate;
import com.wraithhawit.rstweaks.iface.BlockPick;
import com.wraithhawit.rstweaks.iface.BlockPickPacket;
import com.wraithhawit.rstweaks.iface.ConfigureSlotPacket;
import com.wraithhawit.rstweaks.iface.InventoryInterfaceContent;
import com.wraithhawit.rstweaks.iface.InventoryInterfaceOpener;
import com.wraithhawit.rstweaks.iface.InventoryInterfaceTicker;
import com.wraithhawit.rstweaks.mekanism.MekanismQio;
import com.wraithhawit.rstweaks.planner.Durability;
import com.wraithhawit.rstweaks.storage.DrawerDenylist;
import com.wraithhawit.rstweaks.storage.ItemDurability;
import com.wraithhawit.rstweaks.test.BlockPickGameTest;
import com.wraithhawit.rstweaks.test.CraftingGridRefillGameTest;
import com.wraithhawit.rstweaks.test.InventoryInterfaceGameTest;
import com.wraithhawit.rstweaks.test.RSTweaksGameTests;
import com.wraithhawit.rstweaks.test.SelfTestCommand;

import org.slf4j.Logger;

/**
 * Entry point for the Refined Storage tick-time optimizations.
 *
 * <p>Almost all behaviour lives in {@code mixin/}, applied via {@code rstweaks.mixins.json}. The
 * exception is the Inventory Interface in {@code iface/}, which is the first thing here that adds
 * something rather than making something cheaper, and so the first thing that registers: one data
 * component and one menu type, both in our own namespace and neither attached to a Refined Storage
 * registry. (One data component was registered here before, for fluid substitution; it went when a
 * dedicated mod took that job over.)
 *
 * <p>The class also exists so FML has something to construct for the {@code rstweaks} modid, and
 * so there is one obvious place to log which optimizations are active when reading a spark profile
 * back.
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
        // The Inventory Interface is the one feature here that is content rather than a tweak to
        // how Refined Storage spends a tick, which is why it is also the only thing this mod
        // registers: a data component and a menu type. Both are ours and neither touches Refined
        // Storage's registries, so removing this mod leaves a Wireless Grid a Wireless Grid.
        InventoryInterfaceContent.register(modEventBus);
        NeoForge.EVENT_BUS.register(InventoryInterfaceOpener.class);
        NeoForge.EVENT_BUS.register(InventoryInterfaceTicker.class);
        // Block pick's one packet. Optional so that a client without this mod is not refused the
        // connection over a channel it would never use — the mixin that sends it is not there
        // either, so the channel simply stays quiet.
        modEventBus.addListener(RegisterPayloadHandlersEvent.class, event -> event
            .registrar("1")
            .optional()
            .playToServer(BlockPickPacket.TYPE, BlockPickPacket.STREAM_CODEC, BlockPick::handle)
            .playToServer(ConfigureSlotPacket.TYPE, ConfigureSlotPacket.STREAM_CODEC,
                ConfigureSlotPacket::handle));
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
            event.register(InventoryInterfaceGameTest.class);
            event.register(BlockPickGameTest.class);
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
            // Present-but-superseded is a third state these two can be in: the mod is
            // installed, but a version of it that carries the author's own fix, so our mixin
            // stood down. Claiming it regardless would be the same lie as claiming it when
            // the mod is absent. See UpstreamGate.
            if (ModList.get().isLoaded("stepcrafter")
                && !UpstreamGate.isStoodDown("step requester backoff")) {
                features.add("step requester backoff");
            }
            if (ModList.get().isLoaded("cabletiers")
                && !UpstreamGate.isStoodDown("tiered autocrafter lookup")) {
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
            if (Config.inventoryInterface) {
                features.add("inventory interface");
            }
            if (Config.blockPick) {
                features.add("block pick");
            }
            activeFeatures = String.join(", ", features);
        }
        return activeFeatures;
    }

    @Nullable
    private static String activeFeatures;
}
