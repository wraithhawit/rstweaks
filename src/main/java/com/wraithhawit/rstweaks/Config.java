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

    public static final ModConfigSpec.IntValue STEP_REQUESTER_SLOW_CALCULATION_MS = BUILDER
        .comment(
            "Back off a Step Requester slot whose craft calculation took longer than this,",
            "even when the calculation SUCCEEDED. 0 disables it.",
            "",
            "stepRequesterFailureBackoffTicks only sees failures, on the assumption that a",
            "satisfiable request is cheap. That assumption is false once every pattern in the",
            "network lives in one provider. Measured 2026-08-23 on a survival world with the",
            "patterns consolidated into a multiblock crafter: three Step Requesters were 34.8%",
            "of the entire server thread, while only 45 attempts failed in 100 seconds. The",
            "expensive calls all SUCCEEDED, so every one of them reset its slot to no delay and",
            "ran again on the next tick.",
            "",
            "The cost is the branching factor. CraftingTree.calculateChild tries every pattern",
            "that outputs a resource, to exhaustion, and copies the whole plan at each node.",
            "Slots asking for base materials -- redstone, silicon, coal, tin -- have a dozen",
            "competing patterns each in a large pack, so a single successful plan explored on",
            "the order of a million nodes and cost roughly 400ms of the server thread.",
            "",
            "A slot that plans in under this many milliseconds is untouched and keeps starting",
            "crafts on exactly the tick it otherwise would. A slot that costs more than this",
            "sleeps for the same escalating backoff a failure gets, so it still crafts -- just",
            "not twenty times a second. Raise it to be more permissive, lower it to be stricter.",
            "",
            "DEFAULT LOWERED 10 -> 1 IN 0.2.115. The 10 was set from an inference, not a",
            "measurement, and the inference was wrong by two orders of magnitude: it read the",
            "'LP planner declined' log line as one-per-calculation, counted ~1/second against",
            "34.8% of the server thread, and concluded ~400ms per calculation. That log line is",
            "written per distinct resource offered to the LP planner, not per call. Profile",
            "KJdBQvnix4 shows what is really happening -- 60,580ms inside the timed region over",
            "120 seconds, with exactly ONE call above 10ms. Thousands of calls of a few",
            "milliseconds each, not a handful of enormous ones. At 10ms the check almost never",
            "fired; the counter read 'slow crafts backed off: 1'.",
            "",
            "Use the figures /rstweaks stats now prints -- calculation count, mean and slowest --",
            "to set this from data rather than from a guess. If the mean is below 1ms this needs",
            "finer granularity than whole milliseconds and the key will have to change units."
        )
        .defineInRange("stepRequesterSlowCalculationMs", 1, 0, 5000);

    public static final ModConfigSpec.IntValue STEP_REQUESTER_BUDGET_PERCENT = BUILDER
        .comment(
            "The share of the server thread one Step Requester slot may occupy. 0 disables.",
            "",
            "The fixed ladder above cannot bound a variable cost, and measurement is what showed",
            "it. Three Step Requesters spent 60,434ms of a 110-second window inside startTask",
            "across 156 calls: a 387ms MEAN, with the worst pinned at exactly 5,000ms -- which is",
            "RS's TimeoutableCancellationToken.TIMEOUT_MS, so those calculations burned the entire",
            "crafting budget on the server thread and then reported failure.",
            "",
            "A slot whose calculation costs five seconds and then sleeps the ladder's ten-second",
            "cap is still eating a third of the tick. At the ladder's 20-tick base it eats 83%.",
            "No fixed number answers that, because the cost is not fixed.",
            "",
            "So the sleep is derived from the cost: at 5%, a calculation that took N ms is",
            "followed by N * 20 ms of silence. A 5,000ms timeout sleeps 100 seconds. A 70ms",
            "calculation sleeps 1.4 seconds. A 1ms calculation sleeps one tick. Cheap slots are",
            "barely affected; expensive ones are bounded by arithmetic instead of by a guess.",
            "",
            "THE TRADE-OFF IS REAL AND IT IS YOURS TO SET. A slot that keeps hitting the 5s",
            "timeout will sleep for minutes, so whatever stock it maintains refills that much",
            "later. That is the point -- it was previously spending half the server thread to",
            "fail -- but if you would rather it retried sooner, raise this. 10 halves every",
            "sleep, 25 quarters it. Lower it to be stricter.",
            "",
            "Applied as a FLOOR on top of the ladder, never a ceiling, so repeated failures",
            "still escalate and a slot can only ever sleep longer than before, never shorter."
        )
        .defineInRange("stepRequesterBudgetPercent", 5, 0, 100);

    public static final ModConfigSpec.IntValue STEP_REQUESTER_COST_CAP_TICKS = BUILDER
        .comment(
            "Hard ceiling on a cost-derived sleep, so no slot can be silenced indefinitely.",
            "",
            "stepRequesterBudgetPercent multiplies a cost that RS itself bounds at 5,000ms, so",
            "the worst case at 5% is 100 seconds -- 2,000 ticks. This exists so that a future",
            "higher crafting timeout, or a stricter budget, cannot turn into an effectively",
            "permanent silence without somebody choosing it. 6000 ticks = 5 minutes.",
            "",
            "A slot at the ceiling still wakes, retries, and re-derives its sleep from what that",
            "retry actually costs. Nothing here is permanent."
        )
        .defineInRange("stepRequesterCostCapTicks", 6000, 1, 72000);

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

    public static final ModConfigSpec.BooleanValue WAIT_FOR_RUNNING_CRAFT = BUILDER
        .comment(
            "Make an Exporter, Interface or Constructor with an autocrafting upgrade wait for",
            "the craft it already started before starting another one for the same resource.",
            "",
            "Refined Storage refuses a duplicate only when what is ALREADY RUNNING reaches the",
            "amount asked for: ensureTask sums the running tasks for the resource and answers",
            "TASK_ALREADY_RUNNING when that total is >= the amount. When the running total never",
            "catches up, every request starts another task. Two ordinary ways that happens:",
            "",
            "1. THE NETWORK CANNOT CRAFT AS MANY AS THE EXPORTER ASKS FOR. This needs no",
            "   upgrades beyond the ones that make it ask. Say the quota is 64 and there are",
            "   ingredients for one craft: the plan for 64 fails, so RS falls through to",
            "   ensureTaskForCraftableAmount, clamps to what is craftable now -- 1 -- and starts",
            "   a task for 1. One is not sixty-four, so the next request starts another task for",
            "   1, and the next, and the next. This is the common case, because ingredients",
            "   trickling in is the normal state of an autocraft. Measured: twelve requests,",
            "   twelve tasks, one item each, same resource.",
            "",
            "2. A REGULATOR UPGRADE. The autocrafting quota is then the whole outstanding",
            "   shortfall rather than a transfer quota, so it GROWS whenever the destination is",
            "   drained, and every increase is larger than what is running. Measured: twenty",
            "   requests against a growing shortfall, twenty tasks.",
            "",
            "Each of those runs a full crafting calculation, gets its own internal storage, and",
            "is stepped every tick, which is why this is a tick-time problem and not only an",
            "untidy autocrafting monitor.",
            "",
            "With this on, anything already being crafted for a resource is enough to refuse a",
            "further request. The shortfall is then satisfied one task at a time instead of in",
            "parallel, so a large regulated buffer refills more slowly and far more cheaply.",
            "Turn it off to get Refined Storage's behaviour back."
        )
        .define("waitForRunningCraft", true);

    /** Cached: read on the ensureTask path, which a tiered exporter hits several times a tick. */
    public static volatile boolean waitForRunningCraft = true;

    public static final ModConfigSpec.BooleanValue SORT_PATTERNS_BY_PRIORITY = BUILDER
        .comment(
            "Return a resource's candidate patterns in priority order, which Refined Storage",
            "intends and does not achieve.",
            "",
            "RS keeps each output's patterns in a PriorityQueue and reads them back with",
            "holders.stream(). PriorityQueue guarantees ordering only for its HEAD -- Java",
            "documents that its iterator traverses in no particular order -- so every alternative",
            "after the first comes back in raw heap-array layout, which depends on the sequence",
            "the patterns happened to be added in.",
            "",
            "That is not cosmetic. CraftingTree.calculateChild tries alternatives in the order it",
            "receives them, returns on the first that succeeds, and explores every failure to",
            "exhaustion while copying the whole crafting state at each node. An arbitrary order is",
            "an arbitrary cost, and it changes silently whenever patterns are re-added.",
            "",
            "Measured 2026-08-23: consolidating a network's patterns into one provider -- which",
            "re-adds every pattern and rebuilds every per-output heap in a new sequence -- took",
            "three Step Requesters from 0.199 to 20.249 ms/tick with the SAME patterns, the same",
            "count and the same recipes, with some calculations going from sub-millisecond to",
            "burning RS's entire 5,000ms timeout.",
            "",
            "Ties are broken on the pattern's UUID rather than on an insertion counter, so a",
            "pattern keeps its place in the search order when it MOVES BETWEEN PROVIDERS -- which",
            "is precisely the event that caused the regression above. The resulting order is",
            "arbitrary but fixed, which is the point: cost stops depending on insertion history,",
            "and raising a provider's priority genuinely searches it first instead of merely",
            "owning the head of a queue nobody reads in order.",
            "",
            "Turn this off to get RS's heap-array order back."
        )
        .define("sortPatternsByPriority", true);

    /** Cached: read once per ingredient per crafting-tree node, which is as hot as it gets. */
    public static volatile boolean sortPatternsByPriority = true;

    /** Called on config load and reload to refresh the hot-path caches. */
    public static void refresh() {
        lazyPatternPlanCopy = LAZY_PATTERN_PLAN_COPY.get();
        lpPlanner = LP_PLANNER.get();
        externalStorageSlotIndex = EXTERNAL_STORAGE_SLOT_INDEX.get();
        keepRecycledResourcesInTask = KEEP_RECYCLED_RESOURCES_IN_TASK.get();
        skipMismatchedStorageTypes = SKIP_MISMATCHED_STORAGE_TYPES.get();
        cacheFailedInsertsByValue = CACHE_FAILED_INSERTS_BY_VALUE.get();
        cacheDrawerDenylist = CACHE_DRAWER_DENYLIST.get();
        skipEmptyCompositeExtract = SKIP_EMPTY_COMPOSITE_EXTRACT.get();
        clampResourceAmountOverflow = CLAMP_RESOURCE_AMOUNT_OVERFLOW.get();
        boundCraftableSearch = BOUND_CRAFTABLE_SEARCH.get();
        craftingCalculationTimeoutMs = CRAFTING_CALCULATION_TIMEOUT_MS.get();
        mekanismQioExternalStorage = MEKANISM_QIO_EXTERNAL_STORAGE.get();
        durabilityAwarePlanning = DURABILITY_AWARE_PLANNING.get();
        waitForRunningCraft = WAIT_FOR_RUNNING_CRAFT.get();
        sortPatternsByPriority = SORT_PATTERNS_BY_PRIORITY.get();
        refillContainersInCraftingGrid = REFILL_CONTAINERS_IN_CRAFTING_GRID.get();
        containerGridClicks = CONTAINER_GRID_CLICKS.get();
        logGridViewDiagnostics = LOG_GRID_VIEW_DIAGNOSTICS.get();
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
            "None of the other optimizations depend on this. It earns its keep on container",
            "recycling -- one bucket round a loop 64 times is one bucket, not 64 -- which RS's",
            "own depth-first calculator either refuses as a cycle or plans as 64 buckets."
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

    public static final ModConfigSpec.BooleanValue CONTAINER_GRID_CLICKS = BUILDER
        .comment(
            "Grid clicks that make sense while you are holding a tank. (Issue #17.)",
            "",
            "Active only when the cursor holds a fluid or chemical container. With anything",
            "else on the cursor -- an ordinary item, or nothing -- the grid behaves exactly as",
            "Refined Storage ships it, and so does ctrl-click, autocrafting and scrolling.",
            "",
            "  left-click          on a row it can accept: fill the container by one bucket",
            "  right-click         empty one bucket of it into the network, anywhere in the grid",
            "  shift + either      keep going until the transfer stops moving anything",
            "",
            "Stock bindings are right-click for one bucket, left-click for 'everything', and",
            "shift for nothing at all. The problem is that 'everything' is not the whole tank",
            "and cannot be: Refined Storage asks the container for Long.MAX_VALUE and gets back",
            "whatever the container is willing to move in one operation, which for a Mekanism",
            "tank is one tier transfer rate -- 64 B from an Ultimate that holds 256 B. Emptying",
            "a tank is therefore the same operation several times over, which is what shift now",
            "does.",
            "",
            "Left fills and right empties whatever you clicked, including the fluid's own row,",
            "so a tank no longer has to be dumped by hunting for blank space in the grid.",
            "",
            "Set false for stock Refined Storage behaviour."
        )
        .define("containerGridClicks", true);

    /** Read on the client, on a mouse click and on the ticks of a shift-click transfer. */
    public static volatile boolean containerGridClicks = true;

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
            "NO PATTERNS NEEDED. It used to require a pattern producing the filled container,",
            "as a stand-in for 'you meant this fluid to be convertible', which only ever worked",
            "because this mod's own fluid substitution registered such a pattern. That feature",
            "is gone; this one no longer depends on it.",
            "",
            "What limits it now is whether the network can pay: no full container in storage and",
            "not enough fluid means nothing happens and you get the empty container back, exactly",
            "as Refined Storage intended. The test on the items is unchanged and is the strict",
            "part -- one container, one remainder, and the remainder must be precisely that",
            "container emptied, so a recipe with a bespoke remainder is left alone."
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

    public static final ModConfigSpec.BooleanValue MEKANISM_QIO_EXTERNAL_STORAGE = BUILDER
        .comment(
            "Let a Refined Storage External Storage read and write a Mekanism QIO system by",
            "pointing it at a QIO Dashboard.",
            "",
            "Stock RS cannot: External Storage attaches to an IItemHandler capability, and a QIO's",
            "contents are not in the block at all -- they live in a frequency shared by every",
            "dashboard, drive array and importer tuned to it, so there is nothing to attach to and",
            "the storage reads as empty. This registers a provider backed by Mekanism's own",
            "published frequency API instead.",
            "",
            "Two-way, because the External Storage's own Access Mode already offers insert-only",
            "and extract-only; set it there rather than here. The QIO's type and count limits are",
            "respected -- a full QIO simply accepts nothing.",
            "",
            "Ignored entirely when Mekanism is not installed.",
            "",
            "Set false to leave a dashboard looking empty as it does in stock."
        )
        .define("mekanismQioExternalStorage", true);

    /** Read at common setup, and again on a reload only to keep the reported feature list true. */
    public static volatile boolean mekanismQioExternalStorage = true;


    public static final ModConfigSpec.BooleanValue BOUND_CRAFTABLE_SEARCH = BUILDER
        .comment(
            "Let the craftable-amount search be cancelled by the timeout the request already has.",
            "",
            "When a craft cannot start, Refined Storage works out the largest amount that COULD be",
            "made: it doubles from 1 until the amount is no longer craftable, then binary-searches",
            "the gap. Every probe is another complete recursive crafting calculation.",
            "",
            "It runs that search with CancellationToken.NONE, discarding the TimeoutableCancellationToken",
            "the caller built two lines earlier -- so the single most expensive step of a craft request",
            "is the one step that cannot time out. Measured at 98% of a server thread on one Cable",
            "Tiers exporter asking for something it could not make.",
            "",
            "Passing the real token through changes nothing for a search that finishes quickly: a token",
            "that has not timed out answers exactly as NONE does. It only ends the ones that were never",
            "going to finish, which then report MISSING_RESOURCES and are cached like any other refusal.",
            "",
            "Set false for stock behaviour."
        )
        .define("boundCraftableSearch", true);

    /** Cached: read inside the redirect, on the crafting calculation path. */
    public static volatile boolean boundCraftableSearch = true;

    public static final ModConfigSpec.IntValue CRAFTING_CALCULATION_TIMEOUT_MS = BUILDER
        .comment(
            "How long one crafting calculation may run, in milliseconds.",
            "",
            "Refined Storage's own limit is 5000, and it is five seconds OF THE SERVER THREAD -- a",
            "hundred ticks in which nothing else in the world happens. A request that is going to fail",
            "spends all of it before saying so, which is what a multi-second freeze on a network with",
            "an impossible craft request actually is.",
            "",
            "THE DEFAULT MATCHES REFINED STORAGE AND CHANGES NOTHING. Lowering this is a real trade:",
            "a cancelled calculation reports MISSING_RESOURCES, which is indistinguishable from",
            "'cannot be made', so a value below what a legitimate large craft needs will silently",
            "refuse crafts that would have worked. Lower it a step at a time and watch for a craft",
            "that stops being offered.",
            "",
            "1000 is a reasonable first try on a pack where the freeze is worse than the risk."
        )
        .defineInRange("craftingCalculationTimeoutMs", 5000, 50, 60000);

    /** Cached: read on every isCancelled, which is checked throughout the calculation. */
    public static volatile long craftingCalculationTimeoutMs = 5000L;

    public static final ModConfigSpec.BooleanValue CLAMP_RESOURCE_AMOUNT_OVERFLOW = BUILDER
        .comment(
            "Clamp a network's cached total for a resource at Long.MAX_VALUE instead of letting",
            "it wrap past it into a negative number.",
            "",
            "A negative total is a hard server crash, not a display glitch. Refined Storage builds",
            "the whole storage list with a terminal stream collect, so one bad entry throws",
            "'Amount must be larger than 0' and the entire list fails to build. That happens on",
            "the server thread when you open any grid and when any autocraft is planned, so the",
            "symptom is a ticking-block-entity crash every time you touch the network.",
            "",
            "Entry.increment is the only unguarded route into that field -- it validates the",
            "amount being added and never the sum. Reaching it needs a storage reporting an",
            "enormous amount: an addon exposing a long-typed resource (energy, source, chemicals)",
            "under one shared key, summed across every External Storage on the network, will do",
            "it from a single creative or very large buffer.",
            "",
            "Clamping rather than refusing the addition is deliberate. Refusing would leave the",
            "cached total quietly lower than what the storages actually hold, and that list is",
            "what RootStorage.get answers from -- a silent undercount is the shape of bug that",
            "makes items unreachable. A saturated total is a number nobody could represent",
            "anyway, and everything downstream keeps the invariant it expects.",
            "",
            "The clamp is logged once per resource, naming the resource, so the storage causing",
            "it can be found. Nothing is persisted: the list is rebuilt from the storages on",
            "every network build, so an affected save needs no repair.",
            "",
            "Set false to disable."
        )
        .define("clampResourceAmountOverflow", true);

    /** Cached: read on the insert path, but only once an overflow is already certain. */
    public static volatile boolean clampResourceAmountOverflow = true;

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

    public static final ModConfigSpec.BooleanValue CACHE_FAILED_INSERTS_BY_VALUE = BUILDER
        .comment(
            "Let Sophisticated Storage's failed-insert cache actually hit when Refined",
            "Storage is the one inserting.",
            "",
            "Sophisticated already remembers, for the rest of the tick, that a barrel",
            "refused an item, so the next attempt can be answered without rescanning the",
            "whole inventory. It remembers by object identity, though, and Refined Storage",
            "builds a brand new ItemStack for every attempt -- so the answer is never found",
            "and every attempt rescans. Measured at 54% of a whole server thread on an",
            "instance where one autocrafting task was returning outputs into barrels.",
            "",
            "This keeps a second record keyed by item and components rather than by object",
            "identity. It caches rejections only, never contents or free space, it is",
            "cleared on the same tick edge Sophisticated already uses, and it is cleared",
            "again on any successful extraction -- which Sophisticated does not do, making",
            "this strictly tighter than the cache it sits beside. Simulated inserts are",
            "never answered from it.",
            "",
            "Set false to disable."
        )
        .define("cacheFailedInsertsByValue", true);

    /** Cached like {@link #lazyPatternPlanCopy}; read on every insert into a barrel. */
    public static volatile boolean cacheFailedInsertsByValue = true;

    public static final ModConfigSpec.BooleanValue CACHE_DRAWER_DENYLIST = BUILDER
        .comment(
            "Cache whether an item is on Functional Storage's drawer denylist, instead of",
            "looking the tag up on every insert attempt.",
            "",
            "BigInventoryHandler consults DRAWER_STORAGE_DENYLIST twice per attempt -- once in",
            "insertItem, then again inside the isValid it goes on to call. Same stack, same",
            "tag, same answer. Refined Storage drives that path once per slot per returned",
            "craft output, and the set probe underneath it measured 11.6% of a whole server",
            "thread -- the second largest single frame in that profile.",
            "",
            "A tag holds items, so the answer depends only on the item: no component, count or",
            "damage value can change it. The cache is keyed on the item and dropped in full on",
            "TagsUpdatedEvent, which is the only thing that can change a tag -- datapack load",
            "and every /reload. Between two of those, a cached answer and a fresh lookup",
            "cannot disagree, so there is no staleness window and nothing to tune.",
            "",
            "Set false to disable."
        )
        .define("cacheDrawerDenylist", true);

    /** Cached like {@link #lazyPatternPlanCopy}; read on every drawer insert attempt. */
    public static volatile boolean cacheDrawerDenylist = true;

    public static final ModConfigSpec.BooleanValue CHAT_NOTIFICATIONS = BUILDER
        .comment(
            "Announce the mod and its active features in chat when a player joins.",
            "",
            "One line: the version and which optimizations are switched on. The version is",
            "there because a test result has to be attributable to an exact build, and chat",
            "is the only place that is visible without opening a log.",
            "",
            "This does NOT post counters. Run /rstweaks stats for those.",
            "Set false to silence the join line entirely."
        )
        .define("chatNotifications", true);

    public static final ModConfigSpec.BooleanValue LOG_GRID_VIEW_DIAGNOSTICS = BUILDER
        .comment(
            "Log every change the client makes to a Grid's displayed resource list.",
            "",
            "A diagnostic for issue #15 - a row left showing 0 for a wear level that is gone",
            "from the network. Refined Storage's own repository logs this at DEBUG, which a",
            "pack's log level hides, so this restates the same facts at INFO and adds the one",
            "thing its messages leave out: what the backing list holds AFTER the change.",
            "",
            "A row displaying 0 means it is in the view list while its backing entry is gone,",
            "so the line that reports 'view row ADDED' or 'view row KEPT' with 'backing now 0'",
            "is the bug happening, and names the resource and the code path that did it.",
            "",
            "Since 0.2.89 this also audits the view list against the backing list after every",
            "update and every sort, and reports any row that has nothing behind it with a",
            "stack trace. Search the log for 'PHANTOM ROW' first - it catches the bug however",
            "the row got there, which the per-path logging above cannot.",
            "",
            "Client-side only, and noisy - a line per resource per grid update, plus a walk of",
            "the whole view list. Turn it on to reproduce, then turn it off."
        )
        .define("logGridViewDiagnostics", false);

    /** Read on every grid view update; cached like the other hot-path flags. */
    public static volatile boolean logGridViewDiagnostics = false;

    public static final ModConfigSpec.IntValue CHAT_NOTIFICATION_INTERVAL_SECONDS = BUILDER
        .comment(
            "Seconds between unprompted chat summaries of the counters.",
            "",
            "OFF BY DEFAULT (0). The counters are worth reading when you go looking for them",
            "and are chat spam when they arrive on their own -- twelve figures on one line,",
            "every few minutes, whether or not anybody asked. Run /rstweaks stats instead;",
            "it prints the same numbers one per line, on demand, to whoever asked.",
            "",
            "Set a number of seconds to get the old broadcast back, for a server where nobody",
            "is going to type a command but somebody is watching chat. A summary is still",
            "skipped entirely when nothing changed since the last one."
        )
        .defineInRange("chatNotificationIntervalSeconds", 0, 0, 3600);

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
