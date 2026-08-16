package com.wraithhawit.rstweaks;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Tunables for the optimizations. Kept in COMMON config so the values are
 * available on the logical server, which is where all of this work happens.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue STEP_REQUESTER_FAILURE_BACKOFF_TICKS = BUILDER
        .comment(
            "How long a Step Requester filter slot sleeps after a failed craft attempt.",
            "",
            "Step Crafter's StepRequesterNetworkNode.doWork() runs every tick. When a slot's",
            "request cannot be satisfied, startTask() runs a full recursive Refined Storage",
            "crafting calculation, returns empty, and records nothing about having failed - so",
            "the next tick repeats the whole calculation. In the baseline profile this single",
            "path was 77.8% of the entire server thread.",
            "",
            "Only the FAILING path is delayed. A craft that can actually start is unaffected",
            "and begins on exactly the same tick as it would without this mod.",
            "20 ticks = 1 second."
        )
        .defineInRange("stepRequesterFailureBackoffTicks", 20, 1, 1200);

    public static final ModConfigSpec.IntValue STEP_REQUESTER_MAX_BACKOFF_TICKS = BUILDER
        .comment(
            "Upper bound on the backoff for a slot that keeps failing.",
            "",
            "Each consecutive failure doubles the wait, starting from",
            "stepRequesterFailureBackoffTicks, until it reaches this cap. Any success resets",
            "the slot to no delay at all. This way a transient shortage recovers in about a",
            "second, while a permanently impossible request settles at a negligible cost",
            "instead of burning the tick forever.",
            "",
            "Set this equal to stepRequesterFailureBackoffTicks to disable escalation and use",
            "a single fixed delay. 200 ticks = 10 seconds."
        )
        .defineInRange("stepRequesterMaxBackoffTicks", 200, 1, 12000);

    public static final ModConfigSpec.IntValue UNCRAFTABLE_RECHECK_TICKS = BUILDER
        .comment(
            "How long the network remembers that a resource is not craftable.",
            "",
            "An Exporter with an autocrafting upgrade calls ensureTask() every time its",
            "resource is missing. A negative answer is expensive: a full recursive crafting",
            "calculation, then binarySearchMaxAmount(), which runs ANOTHER full calculation",
            "for every probe as it doubles upward and then binary-searches back down, then a",
            "third calculation for the amount it settled on. Nothing caches the outcome, so",
            "the next tick repeats all of it.",
            "",
            "Only negative results are cached. Anything craftable clears the entry at once,",
            "so this delays noticing that a recipe became possible by at most this many",
            "ticks. 60 ticks = 3 seconds."
        )
        .defineInRange("uncraftableRecheckTicks", 60, 1, 1200);

    public static final ModConfigSpec.BooleanValue LAZY_PATTERN_PLAN_COPY = BUILDER
        .comment(
            "Share ingredient maps between autocrafting plan snapshots instead of",
            "deep-copying them (copy-on-write).",
            "",
            "The crafting calculator snapshots the whole plan at every child calculation so",
            "a failed branch can be discarded. MutablePatternPlan.copy allocates a fresh map",
            "per ingredient index every time, and almost all of it is wasted -- most",
            "snapshots are discarded untouched. At baseline this was 46.5% of the entire",
            "server thread, and it is still the largest single frame during lag spikes.",
            "",
            "With this on, copies share their maps and a plan takes its own copy only before",
            "writing. Behaviour is identical; no plan can ever observe another's mutation.",
            "",
            "This is the most invasive optimization in the mod. Set false to disable it if",
            "you suspect it of causing incorrect crafting. Safe to toggle at runtime in",
            "either direction, including with crafting tasks already running."
        )
        .define("lazyPatternPlanCopy", true);

    /**
     * Cached copy of {@link #LAZY_PATTERN_PLAN_COPY}, refreshed by {@link #refresh()}.
     * The mixin reads this inside the crafting-tree copy path, which runs millions of
     * times per calculation — far too hot for a config-spec lookup on every read.
     */
    public static volatile boolean lazyPatternPlanCopy = true;

    public static final ModConfigSpec.BooleanValue SILENCE_AUTOCRAFTING_DEBUG_LOG = BUILDER
        .comment(
            "Stop Refined Storage's autocrafting task engine writing a DEBUG line per",
            "crafting step to debug.log.",
            "",
            "InternalTaskPattern, TaskImpl and AbstractTaskPattern log 'Stepping', 'Stepped',",
            "'Inserting' and 'Extracted' once per pattern per iteration, each formatting a",
            "full ItemResource with its component patch. NeoForge writes debug.log at DEBUG",
            "by default, so this is on for everyone, and it happens on the server thread.",
            "",
            "Measured on a creative test world running compression crafts: 412,256 lines and",
            "209 MB in 29 seconds -- 7 MB/s of blocking disk writes. Building those messages",
            "and writing them was 71.8% of the entire server thread, more than every mod's",
            "real work put together.",
            "",
            "Only DEBUG is affected. Warnings and errors from Refined Storage still appear,",
            "and no other logger is touched. Set false if you are debugging a crafting bug",
            "and need the step-by-step trace."
        )
        .define("silenceAutocraftingDebugLog", true);

    /** Called on config load and reload to refresh the hot-path caches. */
    public static void refresh() {
        lazyPatternPlanCopy = LAZY_PATTERN_PLAN_COPY.get();
        lpPlanner = LP_PLANNER.get();
        externalStorageSlotIndex = EXTERNAL_STORAGE_SLOT_INDEX.get();
        keepRecycledResourcesInTask = KEEP_RECYCLED_RESOURCES_IN_TASK.get();
        skipMismatchedStorageTypes = SKIP_MISMATCHED_STORAGE_TYPES.get();
        skipEmptyCompositeExtract = SKIP_EMPTY_COMPOSITE_EXTRACT.get();
        durabilityAwarePlanning = DURABILITY_AWARE_PLANNING.get();
        fluidSubstitutionPatterns = FLUID_SUBSTITUTION_PATTERNS.get();
        convertUnmarkedFluidPatterns = CONVERT_UNMARKED_FLUID_PATTERNS.get();
        refillContainersInCraftingGrid = REFILL_CONTAINERS_IN_CRAFTING_GRID.get();
        reversibleFluidSwapPatterns = REVERSIBLE_FLUID_SWAP_PATTERNS.get();
        AutocraftingLogSpam.apply(SILENCE_AUTOCRAFTING_DEBUG_LOG.get());
    }

    public static final ModConfigSpec.BooleanValue LP_PLANNER = BUILDER
        .comment(
            "Plan crafts that involve byproducts or cycles with a linear solver instead",
            "of Refined Storage's recursive tree.",
            "",
            "RS's CraftingTree is depth-first and guards against revisiting a pattern, so it",
            "cannot represent a resource that is both consumed and produced by the same",
            "subgraph. Container recycling is exactly that -- bucket -> milk_bucket -> cake",
            "-> bucket -- which is why 1000 cakes plans 3000 buckets. Expressed as equations",
            "the cycle nets out and the answer is 3.",
            "",
            "This only takes over subgraphs that actually contain a byproduct or a cycle.",
            "Everything else keeps using stock RS.",
            "",
            "The two recipes this used to break are the two it is now tested hardest on.",
            "Netherite smithing templates (1 template + materials -> 2 templates) failed",
            "because a plan that nets to zero was emitted with nothing to seed the cycle,",
            "and Occultism bound books for the same reason a step further out. Both are",
            "scenarios in the headless suite now, which replays every plan the way the",
            "executor will run it and fails on a deadlock rather than on a wrong number.",
            "",
            "It declines rather than guessing. Too many patterns, an exhausted solver",
            "budget, no integer solution, or a plan the replay could not run all mean stock",
            "Refined Storage plans the craft exactly as it does today, and the reason is",
            "logged. The worst case is the behaviour you already have.",
            "",
            "None of the other optimizations depend on this, but fluidSubstitutionPatterns",
            "effectively does: a reversible swap is a cycle, and RS's own calculator reports",
            "'cyclical pattern detected' rather than planning it."
        )
        .define("lpPlanner", true);

    /** Cached like {@link #lazyPatternPlanCopy}; read per craft request. */
    public static volatile boolean lpPlanner = true;

    public static final ModConfigSpec.BooleanValue KEEP_RECYCLED_RESOURCES_IN_TASK = BUILDER
        .comment(
            "Keep the root pattern's byproducts inside the crafting task instead of",
            "pushing them to the network the instant they are made.",
            "",
            "This is why Refined Storage asks for 64 buckets to craft 64 rice slimeballs,",
            "and it is not a planning mistake -- the executor forces it. A task stops",
            "drawing from the network once it starts running, and InternalTaskPattern sends",
            "the ROOT pattern's outputs AND byproducts straight to the network. So when the",
            "pattern making the item you asked for is also the one handing the bucket back,",
            "the bucket leaves the task immediately and iteration two has nothing to fill.",
            "One slimeball works; two deadlock.",
            "",
            "Only byproducts are affected. The item you asked for still arrives in the",
            "network as each one is crafted. Byproducts arrive when the task finishes",
            "rather than during it -- the task returns its whole internal storage on",
            "completion and on cancellation, so nothing is lost, only delayed.",
            "",
            "lpPlanner will refuse to run with this off, because container recycling is",
            "impossible without it."
        )
        .define("keepRecycledResourcesInTask", true);

    /** Cached like {@link #lazyPatternPlanCopy}; read once per crafted iteration. */
    public static volatile boolean keepRecycledResourcesInTask = true;

    public static final ModConfigSpec.BooleanValue FLUID_SUBSTITUTION_PATTERNS = BUILDER
        .comment(
            "Let a pattern that only empties or fills a container run without a machine.",
            "",
            "Refined Storage builds every processing pattern as 'external', meaning: hand",
            "this to a pattern provider and wait for a machine to give the result back.",
            "That is right for a furnace and wrong for a bucket. Nothing has to happen for",
            "a lava bucket to become an empty bucket and 1000mB of lava, so with no machine",
            "to accept it, the craft can never start.",
            "",
            "With this on, a pattern whose inputs and outputs are the same container and",
            "the same fluid on opposite sides is settled by Refined Storage itself, the way",
            "a crafting table recipe is. Any container is covered -- the test asks the",
            "item's own fluid handler, it does not look for buckets.",
            "",
            "The test is strict on purpose. A processing pattern turned internal by mistake",
            "would have Refined Storage produce the output without the machine ever running,",
            "which is a duplication bug rather than a stalled craft.",
            "",
            "OFF BY DEFAULT while this is still being tested. Needs lpPlanner on, which it",
            "now is: a reversible swap is a cycle, and RS's own calculator answers",
            "'cyclical pattern detected' instead of planning it.",
            "",
            "One thing to know before turning it on: if your pack has a machine recipe that",
            "really is a container and its own contents on opposite sides, that recipe is",
            "settled in the ledger rather than sent to the machine. Nothing is created or",
            "destroyed, but the machine does not run and its processing time is skipped."
        )
        .define("fluidSubstitutionPatterns", false);

    /** Cached like {@link #lazyPatternPlanCopy}; read once per pattern resolution. */
    public static volatile boolean fluidSubstitutionPatterns = false;

    public static final ModConfigSpec.BooleanValue CONVERT_UNMARKED_FLUID_PATTERNS = BUILDER
        .comment(
            "Also treat a processing pattern as a fluid substitution when its contents look",
            "like one, even though it was not encoded on the fluid substitution tab.",
            "",
            "OFF BY DEFAULT. Patterns encoded on the fluid tab carry a mark saying what they",
            "are, and only marked patterns are treated as substitutions.",
            "",
            "Recognising one by its contents cannot tell it apart from a real machine recipe",
            "that takes a full container and gives back the empty one plus its fluid, because",
            "at the level of ingredients and outputs those are the same thing. Such a recipe",
            "was being settled in the ledger instead of being sent to the machine, so the",
            "machine never ran. Requiring the mark removes that ambiguity entirely.",
            "",
            "WHAT TURNING IT ON COSTS: nothing is destroyed, but a pattern encoded before",
            "0.2.65 has no mark, so it goes back to being an ordinary processing pattern and",
            "its craft waits for a machine that does not exist. Re-encode it on the fluid tab",
            "and it carries the mark like any new one. Turn this on if you would rather not",
            "re-encode them and accept the ambiguity above.",
            "",
            "Has no effect unless fluidSubstitutionPatterns is on."
        )
        .define("convertUnmarkedFluidPatterns", false);

    /** Cached like {@link #lazyPatternPlanCopy}; read once per pattern resolution. */
    public static volatile boolean convertUnmarkedFluidPatterns = false;

    public static final ModConfigSpec.BooleanValue REVERSIBLE_FLUID_SWAP_PATTERNS = BUILDER
        .comment(
            "Make one fluid substitution pattern work in both directions, so a single",
            "pattern for 'water bucket -> bucket + 1000mB water' also covers",
            "'bucket + 1000mB water -> water bucket'.",
            "",
            "Emptying a container and filling it are the same fact stated twice, so",
            "encoding both is busywork. Refined Storage models a pattern as one direction,",
            "so this registers a second, mirrored pattern alongside the one you inserted.",
            "It is removed again with the original when the crafter is broken.",
            "",
            "NOTE: this deliberately creates a cycle in the crafting graph -- a water",
            "bucket makes water, and water makes a water bucket. The LP planner handles",
            "cycles by construction, which is what it was written for. Refined Storage's",
            "own calculator is more likely to struggle, so leave lpPlanner on if you turn",
            "this on, and turn this off first if plans start failing or hanging.",
            "",
            "Has no effect unless fluidSubstitutionPatterns is on."
        )
        .define("reversibleFluidSwapPatterns", true);

    public static volatile boolean reversibleFluidSwapPatterns = true;

    public static final ModConfigSpec.BooleanValue REFILL_CONTAINERS_IN_CRAFTING_GRID = BUILDER
        .comment(
            "In the Crafting Grid, refill a container the recipe hands back instead of leaving",
            "it empty.",
            "",
            "Crafting a cake takes three milk buckets and returns three empty ones, so the next",
            "cake needs three more milk buckets the network does not have -- even when it is",
            "holding plenty of milk. Refined Storage already pulls a replacement from the network",
            "for an ordinary ingredient after a craft; this is the same for the ingredient that",
            "comes back as a container.",
            "",
            "Nothing is created. The network pays one of two ways, in this order: a full container",
            "already in storage is traded for the empty one, and only if there is none does it",
            "spend the fluid to fill the container the player has. Stock before tank -- turning",
            "milk into buckets while full ones sit in a drawer is the wrong way round.",
            "",
            "Only applies when the network has a pattern producing that filled container, so it",
            "is limited to fluids you have set up. Needs fluidSubstitutionPatterns on."
        )
        .define("refillContainersInCraftingGrid", true);

    /** Cached like {@link #lazyPatternPlanCopy}; read once per crafted ingredient. */
    public static volatile boolean refillContainersInCraftingGrid = true;

    public static final ModConfigSpec.BooleanValue DURABILITY_AWARE_PLANNING = BUILDER
        .comment(
            "Treat a tool that wears out as a supply of uses instead of a stack of items,",
            "so one Mystical Agriculture infusion crystal covers as many crafts as it has",
            "durability and a replacement is only made once it breaks.",
            "",
            "A crystal at damage 0 and the same crystal at damage 1 are different resources",
            "to Refined Storage, and a pattern records the exact one it was encoded with --",
            "so the first craft works and the second has nothing to use. Applied Energistics",
            "solves this by recomputing what a recipe hands back from the item actually",
            "consumed; this is the same idea.",
            "",
            "Both halves are implemented. The planner collapses every wear level of a tool",
            "into one class measured in uses, and the executor substitutes whichever level",
            "is really in the task and ages what the pattern hands back to match -- the",
            "second half is not optional, and there is a test that says so: without it one",
            "crystal does sixty-four jobs and never breaks, which is a duplication bug that",
            "otherwise runs to completion and looks like a working feature.",
            "",
            "Storage is scanned for wear variants only when the pattern graph contains a",
            "durable item at all, so a network with no such recipe pays nothing."
        )
        .define("durabilityAwarePlanning", true);

    /**
     * Cached like {@link #lazyPatternPlanCopy}. Read while building a crafting graph,
     * which is off the tick loop.
     */
    public static volatile boolean durabilityAwarePlanning = true;

    public static final ModConfigSpec.IntValue MAX_PLANNER_PATTERNS = BUILDER
        .comment("Largest pattern subgraph the LP planner will attempt. Beyond this it declines.",
            "",
            "A search from the target follows every ingredient outward, so on a mature",
            "network this reaches a long way -- ores, ingots, and everything made from them.",
            "Set generously: too low and the planner silently declines the very crafts it",
            "exists for. Declines are logged with the subgraph size, so raise this if you",
            "see one naming a craft you wanted solved.")
        .defineInRange("maxPlannerPatterns", 4096, 8, 65536);

    public static final ModConfigSpec.IntValue MAX_SOLVER_NODES = BUILDER
        .comment("Branch-and-bound node budget per craft request. Exhausting it uses the best",
            "integer solution found so far, or declines if none was found.")
        .defineInRange("maxSolverNodes", 5000, 100, 200000);

    public static final ModConfigSpec.IntValue MAX_SIMULATION_PASSES = BUILDER
        .comment("Budget for the executability check that replays RS's scheduling.",
            "A recycled container retires only as many iterations as containers in flight",
            "allow, so passes scale with iteration count. Exceeding this declines the plan.")
        .defineInRange("maxSimulationPasses", 20000, 100, 1000000);

    public static final ModConfigSpec.BooleanValue EXTERNAL_STORAGE_SLOT_INDEX = BUILDER
        .comment(
            "Index which slots of an external inventory hold which resource, so Refined",
            "Storage does not walk every slot to find one.",
            "",
            "RS extracts by scanning linearly, and it SIMULATES constantly -- far more often",
            "than it really extracts. Behind a drawer controller or a wall of Sophisticated",
            "barrels that is thousands of reads per call. On a struggling server this path",
            "measured 42.9 ms/tick, the single largest cost on the server thread.",
            "",
            "The index never decides whether an item is extractable: it only suggests slots,",
            "and each one is re-read live and compared before anything is taken. A wrong",
            "index costs one slow scan, never a wrong answer.",
            "",
            "Set false to disable."
        )
        .define("externalStorageSlotIndex", true);

    /** Cached like {@link #lazyPatternPlanCopy}; read on every extraction. */
    public static volatile boolean externalStorageSlotIndex = true;

    public static final ModConfigSpec.IntValue EXTERNAL_INDEX_TTL_TICKS = BUILDER
        .comment(
            "How long the slot index may be used before it is rebuilt.",
            "",
            "Only matters for ABSENCE. Whether an item is present is always confirmed by a",
            "live read, but 'this inventory has none' cannot be verified by reading a slot,",
            "so it is bounded by time instead. 20 ticks = 1 second, which is the same order",
            "as RS's own external storage cache, refreshed every 5-40 ticks by",
            "detectChanges(). Lower is more responsive to hoppers and pipes filling a",
            "container; higher is faster."
        )
        .defineInRange("externalIndexTtlTicks", 20, 1, 200);

    public static final ModConfigSpec.IntValue MIN_SLOTS_TO_INDEX = BUILDER
        .comment("Inventories smaller than this are scanned directly -- below roughly this",
            "size a linear scan beats building and consulting an index.")
        .defineInRange("minSlotsToIndex", 64, 1, 4096);

    public static final ModConfigSpec.BooleanValue SKIP_EMPTY_COMPOSITE_EXTRACT = BUILDER
        .comment(
            "Skip the storage walk when the network holds none of the resource being extracted.",
            "",
            "CompositeStorage.extract asks every storage in the network in turn, and most calls",
            "are for something the network does not have -- so the cost is the walk, not the work.",
            "Measured on a struggling instance, the composite and proxy extract layers came to",
            "about 12% of the whole server thread, against 3% for the inventory scan underneath.",
            "",
            "This adds no cache. The answer comes from the resource list the composite already",
            "keeps of what its sources hold: the same list the grid shows you and the same one",
            "RootStorage.get answers from. There is nothing new that can go stale on its own.",
            "",
            "DEFAULTED OFF while an item-loss report is open -- turn it on only if you are",
            "chasing extraction cost and are willing to be the test case.",
            "",
            "WHAT IT CHANGES, and why that turned out to matter more than expected: an external",
            "storage contributes nothing to the list on insert (compositeInsert reports an",
            "amountForList of zero). The list gains the item only because insert() then calls",
            "detectChanges(), which DIFFS A FRESH SNAPSHOT of the inventory against a cache. That",
            "is not merely late -- it can be wrong. If the item leaves the inventory before the",
            "snapshot is taken, or a mod moves it outside RS's view, the diff sees nothing and the",
            "list never gains the item at all.",
            "",
            "Without this option that desync is cosmetic and self-healing: the grid shows nothing",
            "but an extraction still walks the storages and finds the items. With it, extraction",
            "returns zero the moment the list says zero, so anything the list has lost becomes",
            "both invisible and unreachable -- which is indistinguishable from having been eaten.",
            "",
            "Set true to skip the walk again once that is understood."
        )
        .define("skipEmptyCompositeExtract", false);

    /** Cached like {@link #lazyPatternPlanCopy}; read on every extraction. */
    public static volatile boolean skipEmptyCompositeExtract = false;

    public static final ModConfigSpec.BooleanValue SKIP_MISMATCHED_STORAGE_TYPES = BUILDER
        .comment(
            "Do not ask an external storage provider for a resource it could not possibly",
            "hold -- an item inventory for energy, an energy cell for iron.",
            "",
            "One External Storage block exposes several providers (items, fluids, and",
            "whatever energy or source types other mods add), and Refined Storage walks all",
            "of them on every extract and insert without looking at the resource type. On a",
            "network where something pulls power every tick, that means asking every item",
            "inventory in the network whether it has any energy. Each answer is instant;",
            "there are just an enormous number of them. Measured at 3.9% of a whole server",
            "thread on a struggling instance.",
            "",
            "This is not a cache and there is nothing to invalidate. An item handler holds",
            "items and never anything else -- it is true at compile time, so a skipped call",
            "is one whose answer was already known. Providers from mods we do not recognise",
            "are still asked every time, exactly as before.",
            "",
            "Set false to disable."
        )
        .define("skipMismatchedStorageTypes", true);

    /** Cached like {@link #lazyPatternPlanCopy}; read on every provider probe. */
    public static volatile boolean skipMismatchedStorageTypes = true;

    public static final ModConfigSpec.BooleanValue CHAT_NOTIFICATIONS = BUILDER
        .comment(
            "Report in chat that the optimizations are active and working.",
            "",
            "Sends a short summary when a player joins, and periodically afterwards if",
            "anything actually happened. The numbers are counts of work avoided, so they",
            "are evidence the mixins fired rather than just a 'loaded' message.",
            "Set false to silence it entirely."
        )
        .define("chatNotifications", true);

    public static final ModConfigSpec.IntValue CHAT_NOTIFICATION_INTERVAL_SECONDS = BUILDER
        .comment(
            "Seconds between periodic chat summaries. Set to 0 to report only on join.",
            "",
            "A summary is skipped entirely when nothing changed since the last one, so a",
            "quiet network stays quiet rather than repeating zeroes."
        )
        .defineInRange("chatNotificationIntervalSeconds", 300, 0, 3600);

    /**
     * A spec value, or the given default when no config file has been loaded.
     *
     * <p>Spec lookups throw {@code IllegalStateException} before the mod loader has read
     * the file, which is exactly the situation
     * {@link com.wraithhawit.rstweaks.test.HeadlessPlannerCheck} runs in — a plain JVM with
     * no Minecraft and no config. Falling back to the declared default there is what lets
     * the planner be verified between builds instead of only in-game, which is how a
     * plan that could not execute reached players in the first place.
     *
     * <p>In the game this fallback never triggers: every read happens long after config
     * load, and a genuine failure to load would have crashed startup already.
     */
    public static int intOrDefault(final ModConfigSpec.IntValue value, final int fallback) {
        try {
            return value.getAsInt();
        } catch (final IllegalStateException configNotLoaded) {
            return fallback;
        }
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
