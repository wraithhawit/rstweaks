# Changelog

The version prints at startup (`[rstweaks] vX.Y.Z loaded ...`) and in the chat join
message, so a test result can always be tied to an exact build.

Patch digit bumps on every build handed over for testing.

`VERSIONS.txt` is the short form of this file — one or two lines per version. Both are
maintained; this one carries the reasoning, that one is the index.

## 0.2.116

**The expensive calculations fail. They do not succeed. 0.2.113 had it backwards, and the
measurement 0.2.115 added is what proved it.**

`slow crafts backed off` never appeared in a single report — not once across the whole session,
with the threshold at 10ms and the world in its worst state. Meanwhile the new line said:

```
148 craft calculations (112.81ms mean, 4,999ms slowest, 16,696ms total)
156 craft calculations (387.39ms mean, 5,000ms slowest, 60,434ms total)
311 craft calculations (194.79ms mean, 5,000ms slowest, 60,580ms total)
```

Two things fall out of that. First, **0.2.113's original ~400ms estimate was right** and
0.2.115's "correction" to it was wrong; the distribution is heavy-tailed, not uniformly small,
and a quiet network genuinely does read 0.99ms mean while a busy one reads 387ms. Second, and
decisively: **4,999 and 5,000ms is `TimeoutableCancellationToken.TIMEOUT_MS`.** Those
calculations burn RS's entire crafting budget on the server thread and then return empty — which
is the *failure* path. `slow crafts backed off` is zero because nothing expensive ever reaches
the success branch.

So the mixin's original premise — that it is the failed attempt that repeats — was correct all
along. What is wrong is the **ladder**, and it is wrong in a way no amount of tuning fixes: a
fixed sleep cannot answer a variable cost. A slot whose calculation costs 5,000ms and then
sleeps the 200-tick cap is still spending a third of the server thread. At the 20-tick base it
spends 83%.

`stepRequesterBudgetPercent` (default **5**) derives the sleep from the cost instead. A slot may
occupy at most that share of the server thread, so a calculation costing N ms is followed by
`N * (100 / percent)` ms of silence:

| calculation | sleep at 5% |
| --- | --- |
| 1ms | 1 tick |
| 70ms | 1.4s |
| 387ms (measured mean) | 7.7s |
| 5,000ms (the timeout) | 100s |

Applied as a **floor on the ladder, never a ceiling**, so repeated failures still escalate and a
slot can only ever sleep longer than it used to, never shorter. `stepRequesterCostCapTicks`
(6,000 = 5 minutes) stops an extreme budget or a raised crafting timeout turning into permanent
silence; a capped slot still wakes, retries, and re-derives its sleep from what the retry costs.

**The trade-off is real.** A slot that keeps hitting the 5s timeout will now sleep for minutes,
so whatever stock it maintains refills that much later. That is the intended exchange — it was
previously spending half the server thread to fail — but raise the percentage if you would
rather it retried sooner.

**Config trap worth knowing:** NeoForge does not rewrite an existing config file when a default
changes in code, so 0.2.115's `stepRequesterSlowCalculationMs` 10 → 1 never reached the live
config, which still read 10. Changing a default only affects fresh installs. Edit the file or
delete it to regenerate.

`backoffCheck` grows to **73 assertions**; the seven new cost-floor cases were confirmed to fail
with the floor removed.

## 0.2.115

**0.2.113 set its threshold from an inference instead of a measurement, and the inference was
wrong by two orders of magnitude.** The mechanism was right and was firing correctly the whole
time. The number was not.

The reasoning behind 10ms: the `LP planner declined` log line appeared roughly once a second,
which was read as one line per crafting calculation; against 34.8% of the server thread that
gives ~400ms per calculation, so a 10ms threshold looked generous. **That log line is written
per distinct resource offered to the LP planner, not per call** — it is not a call counter, and
using it as one was unfounded.

Profile `KJdBQvnix4` shows the real shape. The timed region holds **60,580ms of `startTask` over
a 120-second profile** — 50.5% of the server thread, wrapping exactly the right call — and
**exactly one** of those calls exceeded 10ms. So the count is in the thousands and the mean is
low single-digit milliseconds: thousands of small calls, not a handful of enormous ones. In game
the counter read `slow crafts backed off: 1`.

Two changes, neither to the mechanism:

- **`/rstweaks stats` now prints `N craft calculations (X.XXms mean, Yms slowest, Zms total)`.**
  Count, mean and worst case together, because setting a threshold requires knowing which shape
  you are looking at, and nothing in game said so before. This is the line that would have
  caught the mistake immediately.
- **`stepRequesterSlowCalculationMs` default drops 10 → 1.**

If the mean turns out to be below 1ms, whole milliseconds are too coarse and the key will have
to change units — the new stats line is what will say so.

The lesson generalises past this config key: a number derived from a log line's frequency is
only as good as the assumption about what writes that line, and that assumption was never
checked against the code that emits it.

## 0.2.114

**Tests for 0.2.113, and a note on what they cannot reach.** No behaviour change.

`plannerCheck` can never test a mixin — nothing transforms Refined Storage's or Step Crafter's
bytecode in a plain JVM, so a mixin-dependent assertion there exercises stock code and passes
whatever we do. `ExtractionSelfTest` sat in exactly that position for months. But the
*decision* 0.2.113 changed is ordinary arithmetic over slot state, and that half can be pulled
out and checked exhaustively.

So `SlotBackoff` now holds every branch that decides whether a slot sleeps, with no Minecraft,
Refined Storage, mixin or config types in it. Thresholds are parameters rather than config
reads, which is what makes the timing deterministic in a test. `StepRequesterNetworkNodeMixin`
keeps only the three injection points, the config reads and the stat counters.

`./gradlew backoffCheck` — **59 assertions across 15 scenarios**, wired into `check` so `build`
runs it. It is instant and depends on nothing, unlike `plannerCheck`, so there was no reason
for it to be opt-in.

Confirmed to have teeth, per the discipline in `rstweaks-gametest-harness`: reinstating the
pre-0.2.113 unconditional reset on success fails **32 of the 59**, including every SLOW-PATH
case and the whole sleep-duration scenario. A test that passes with the bug present is not a
test.

Covered: the failure ladder escalates, doubles and caps; a fast success resets it; a slow
success arms it; `elapsed == threshold` counts as slow and one millisecond under does not;
slow successes escalate and cap like failures; failures and slow successes share one ladder
across flips; `slowMs = 0` restores failure-only behaviour exactly; a slot sleeps for its full
interval and wakes on the last tick; slots are independent; reconfiguration clears a sleeping
slot; capacity grows without losing state and never shrinks; and an out-of-range slot — the
mixin's initial `currentSlot = -1` — is inert rather than throwing on the server thread.

**What this still does not prove:** that the redirects fire, that `startTask` is the method
being timed, or that the elapsed figure is real. Those need a running game, and the reading
that settles them is the "slow crafts backed off" counter appearing in chat.

## 0.2.113

**The Step Requester backoff was watching the wrong signal.** 0.2.x added
`stepRequesterFailureBackoffTicks` on an explicit premise, written into the mixin's javadoc:
*"the satisfiable path is already cheap … it is specifically the failed attempt that repeats
forever."* That premise is now disproved, and the disproof is worth recording because the
mixin was working perfectly the whole time.

Measured 2026-08-23 in a survival world whose patterns had all been consolidated into a single
multiblock pattern provider. Three Step Requesters at one location held **34.8% of the entire
server thread** (`StepRequesterNetworkNode.doWork`, spark `ZHq9zHxsZH`; Observable `Cl4wO` puts
the same three blocks at 20.249 ms/tick, 61.4% of all block-entity time). Over 100 seconds of
that, the chat counters reported:

| counter | 100s |
| --- | --- |
| failed attempts backed off | **45** |
| crafting calculations skipped | 5,828 |
| plan copies avoided | **+72,600,000 in 45s** |

Failures were negligible and the backoff was firing correctly on every one of them. The
expensive calculations were the ones that **succeeded** — so each hit the `else` branch,
reset its own slot to zero delay, and ran again on the very next tick, forever.

The cost is the *branching factor*, not the outcome. `CraftingTree.calculateChild` iterates
every pattern that outputs a resource and explores each to exhaustion, copying the plan at
each node. The Step Requester slots here ask for base materials — `redstone`, `silicon`,
`coal`, `tin_ingot` — which in a large pack have a dozen competing patterns each. One
successful plan explored on the order of a million nodes and cost roughly 400ms of server
thread. Consolidating every pattern into one provider is what exposed it: it is the first time
the repository hands the calculator *all* the alternatives at once.

So `stepRequesterSlowCalculationMs` (default 10, `0` disables) times `startTask` and puts a
slot to sleep when a **successful** calculation exceeds the threshold, on the same escalating
ladder failures use. Failures and slow successes share one ladder deliberately: a slot is
either cheap enough to run every tick or it is not, and a slot that alternates between the two
should keep escalating rather than resetting on each flip. A fast success is still completely
untouched and starts its craft on exactly the tick it otherwise would.

New chat line, **"slow crafts backed off"**, reported separately from failures — a high number
there with failures near zero is precisely the signature the old backoff could not see.

The mixin's class javadoc has been rewritten rather than amended. It asserted the false premise
as established fact, which is how the gap survived this long.

## 0.2.112

**The five-second freeze has a number, and now it has a config.** 0.2.111 took the doubling
search out of the calculation budget; this is the budget itself.

`TimeoutableCancellationToken.TIMEOUT_MS = 5000`. That is five seconds *of the server thread* —
a hundred ticks in which nothing else in the world happens — and a request that is going to
fail spends all of it before saying so. Profile `o7HhlSJezm` shows 0.2.111 working exactly as
intended (`ensureTaskForCraftableAmount` 98.07% → 0.30%) and shows what it leaves behind: the
*first* calculation, `ensureTask` → `calculatePlan`, at 50% of the thread, bounded by nothing
but this constant.

`craftingCalculationTimeoutMs` makes it configurable via `@ModifyConstant` on the comparison
in `isCancelled`.

**The default is RS's own 5000 and changes nothing, deliberately.** A cancelled calculation
reports `MISSING_RESOURCES`, which is indistinguishable from "cannot be made" — so a value
below what a legitimate large craft needs will silently refuse crafts that would have worked.
rstweaks has made that mistake before (0.2.96, where a solver cap quietly blocked late-game
crafts and a passing proxy test was mistaken for a proof), so this ships as a lever rather
than as a change. 1000 is a reasonable first try where the freeze is worse than the risk.

## 0.2.111

**The craftable-amount search can be cancelled now.** This is the fix the 0.2.110 notes were
describing without implementing.

When a craft cannot start, RS works out the largest amount that *could* be made: it doubles from 1
until the amount is no longer craftable, then binary-searches the gap. **Every probe is another
complete recursive crafting calculation.** And it runs that search like this:

```java
binarySearchMaxAmount(calculator, resource, CancellationToken.NONE)
```

The caller's `TimeoutableCancellationToken` is sitting in a parameter two lines above and is
thrown away. So the single most expensive step of a craft request is the one step that cannot time
out.

Measured: **98% of the server thread**, all of it inside that search, from one Cable Tiers exporter
asking for something it could not make. `ensureTaskForCraftableAmount` was 98.07% of the 98.09%
spent in `ensureTask` -- essentially all of it.

**Passing the real token through changes nothing for a search that finishes.** A token that has not
timed out answers exactly as `NONE` does. It only ends the ones that were never going to finish,
which then report `MISSING_RESOURCES` and get cached like any other refusal -- so the uncraftable
cache finally has something to cache.

Config: `boundCraftableSearch`, default on.

This is the fourth face of one defect, and the first fix that bounds *cost* rather than frequency.
The Step Requester backoff and the uncraftable cache both limit how often a doomed request runs.
Neither could limit how long it ran, because RS had discarded the mechanism for that.

## 0.2.110

**`/rstweaks stats` now names what the network keeps failing to autocraft.**

A profile of a badly lagging world: **92.4% of the server thread** was one Exporter with an
autocrafting upgrade, through
`MissingResourcesListeningExporterTransferStrategy -> scheduleAutocrafting -> ensureTask ->
CraftingCalculatorImpl.calculate`. Below that, 32% `HashMap.hash` and 14% `ItemResource.equals` --
map traffic proportional to the size of the crafting tree, not any single hot spot.

The uncraftable cache was working. It just cannot help enough: it suppresses the retry for
`uncraftableRecheckTicks` (60 by default), and on a network with a lot of patterns a single
calculation can take seconds. Sixty ticks between multi-second calculations is still most of the
server thread.

**So the fix is not a faster calculation, it is finding the exporter.** Which is close to
impossible by hand -- nothing in game says which exporter is asking, or for what. The names now
appear in `/rstweaks stats`, most recent first, with how many times each has been refused.

This is the third variation on one defect: something asks for a craft that cannot happen, and
Refined Storage re-derives the whole answer every time. The Step Requester backoff and the
uncraftable cache each bound how *often*; nothing bounds how *expensive*. Naming the resource is
what lets a player remove the cause rather than pay for it more slowly.

## 0.2.109

**Pattern plan copying: the outer map is shared too.** `lazyPatternPlanCopy` already shared the
inner ingredient maps, which took `MutablePatternPlan.copy` from 46.5% of the server thread to
6.7%. This finishes the job.

The remaining cost was the part the first pass left behind. `copy()` still allocated a fresh outer
map and put every ingredient index into it, one at a time. A breakdown of the method on a busy
network says so exactly:

| inside `MutablePatternPlan.copy` | share |
|---|---|
| the loop itself (self) | **66.9%** |
| `HashMap.put` | **29.2%** |
| `HashMap$EntryIterator.next` | 3.8% |
| our existing inner-map share | **0.05%** |

So the inner-map optimisation was working perfectly and everything left was rebuilding the outer
map. On the profile it was drawn from, `copy` was 39% of the whole server thread.

Now `copy()` hands the new plan this plan's outer map and skips the loop -- the iteration is
emptied rather than the method rewritten, because `MutablePatternPlan` is package-private and this
mixin cannot so much as name its return type. `addUsedIngredient` takes a private copy of the
outer map before its first write, exactly as it already did for the inner ones.

**The invariant is unchanged and still the whole argument:** no plan ever mutates a map another
plan can observe. The class makes that checkable -- it is forty lines, `ingredients` is written in
exactly one place (`addUsedIngredient`), and `getPlan` only reads.

Found while profiling rsmc, which turned out not to be the problem: a multiblock crafter puts a lot
of patterns on a network, and every pattern is another branch the calculator explores on every
craft.

## 0.2.108

**An External Storage can read a Mekanism QIO.** (Issue #12.)

Point an External Storage at a QIO Dashboard and stock Refined Storage shows nothing. The
reason is not a bug in either mod: External Storage works through capabilities — it asks the
block in front of it for an `IItemHandler` and wraps it — and a QIO's contents are not in the
block at all. They live in a *frequency*, shared by every dashboard, drive array, importer and
exporter tuned to the same name. There is no handler on the dashboard to attach to, so there is
nothing for RS to see.

The issue proposed writing a provider against "the QIO frequency API" and warned this might be
larger than anything else in the mod. It is not, because both halves are already public:

- Refined Storage's `RefinedStorageApi.INSTANCE.addExternalStorageProviderFactory` is a
  documented extension point — the same one RS uses for its own item and fluid providers.
- Mekanism publishes the frequency in `mekanism.api`, not in `common`: a QIO block entity is an
  `IQIOComponent`, and the `IQIOFrequency` it returns offers `forAllHashedStored`, `massInsert`
  and `massExtract` — which map one-to-one onto `ExternalStorageProvider`'s `iterator`, `insert`
  and `extract`, including the simulate/execute split and the "amount actually moved" return.

So there is **no mixin on either side**, and nothing here reaches into internals. Mekanism is an
optional dependency; without it the class is never loaded.

**Checked first, as the issue asked:** `refinedstorage-mekanism-integration` does *not* cover
this. Every class in it is `Chemical*` — chemical storage disks, a chemical external storage
provider, chemical grid strategies. QIO is not mentioned anywhere in the jar. So this does not
belong upstream in that mod as it stands today.

**Two-way, deliberately.** The issue asked whether read-only would be simpler, and it would be —
but the External Storage already has an Access Mode in its own GUI. Hard-coding read-only here
would take that choice away from the player *and* stop autocrafting from ever using the QIO as
a destination. The QIO's own limits still apply: a frequency at its type or count capacity
accepts nothing, which arrives as an ordinary partial insert.

Three details that are easy to get wrong and are worth recording:

- **The provider is handed out for every External Storage, not only for dashboards.** RS builds
  the provider list once, when the External Storage loads, and rebuilds it only on a block state
  change. A factory that checked for a dashboard at creation time would answer "not QIO" forever
  for a dashboard placed afterwards. The check therefore happens per call, exactly as the
  capability lookup does in RS's own providers.
- **The block entity is cached, the frequency is not.** A dashboard's frequency changes when the
  player retunes it, and caching it would silently keep reading the old network.
- **It declares itself item-only** through `TypedExternalStorageProvider`, so a network pulling
  fluids or chemicals never pays for it.

Config: `mekanismQioExternalStorage`, default on, ignored when Mekanism is absent. The startup
line reports `QIO external storage` when it is live.


## 0.2.107

**Clamp instead of wrap.** (Issue #17.) Follows the question "what would it take for it to move
more than 64 B?" - and the answer turned out to be "remove the reason it was ever 64".

The cap is 64 **operations**, not 64 buckets, and what an operation moves depends on the regime:

| regime | per operation | 64 operations buys |
|---|---|---|
| ordinary | one container transfer rate - 64 B on an Ultimate | 4096 B; an Ultimate fills in four, the cap never binds |
| network total above `Integer.MAX_VALUE` | 1 B, all that survived the conversion | 64 B - the limit actually being hit |

So the limit was never the cap. It was 0.2.106's bucket fallback, and that existed because of
this:

```java
public static FluidStack toFluidStack(FluidResource fluidResource, long amount) {
    if (amount > 2147483647L) {
        LOGGER.warn("Truncating too large amount for {} to fit into FluidStack {}", fluidResource, amount);
    }
    return new FluidStack(..., (int) amount, ...);
}
```

The warning is accurate and the cast is not a truncation - it is a **wrap**. Above
`Integer.MAX_VALUE` the sign flips, so 2.2 billion mB arrives as roughly -2.1 billion, and every
consumer reads it as nothing or worse. `VariantUtilMixin` clamps the argument first.

**Clamping is correct wherever truncating was not, which is everywhere.** No caller wants a
negative amount, and any caller asking for more than an `int` can hold is asking for "as much as
possible". A transfer is a two-sided negotiation - the container answers with what it will
actually take - so offering `Integer.MAX_VALUE` instead of the true total loses nothing. Only a
single operation moving more than 2.147 billion mB would notice, and no container has that
capacity.

That fixes both halves at once: `canExtract` stops declaring that your tank cannot hold XP
fluid, and `ENTIRE_RESOURCE` transfers work, so shift-fill on such a fluid is back to one
operation per transfer rate.

**The client asks whether the clamp actually applied rather than assuming it.**
`survivesLargeTransfer` runs the exact conversion the transfer will run and looks at the result;
`VariantUtilMixin` is `require = 0` and would decline silently if another mod claimed the
method, and a fast path chosen on an unverified assumption is how 0.2.104 went wrong. Clamped,
the fast path; not clamped, 0.2.106's bucket fallback.

**So: what would move more than 64 B?** After this, an ordinary shift-fill already can - four
operations of 64 B fill an Ultimate. The cap only binds for a container whose capacity is more
than 64 transfer rates, which no Mekanism tier is. If one ever turns up, `MAX_OPERATIONS` in
`GridContainers` is the single number to raise, and the only cost is packets in one tick.

## 0.2.106

**A fluid the network holds billions of could not be picked up, and the tank was stored
instead.** (Issue #17.) Reported in game on 0.2.105: an External Storage on a Just Dire Things
experience holder, ~2.1 MB of XP fluid visible in the grid, left-click and shift-left-click
both fail and the tank goes into the system.

`FluidGridResource.canExtract` offers the container **the entire network total**:

```java
ResourceAmount toFill = new ResourceAmount(this.resource, repository.getAmount(this.resource));
return Platform.INSTANCE.fillContainer(carriedStack, toFill).map(r -> r.amount() > 0L).orElse(false);
```

and `fillContainer` reaches `VariantUtil.toFluidStack`, which narrows a long to an int -
warning above `Integer.MAX_VALUE` and then doing it anyway:

```java
if (amount > 2147483647L) {
    LOGGER.warn("Truncating too large amount for {} to fit into FluidStack {}", fluidResource, amount);
}
return new FluidStack(..., (int) amount, ...);
```

A displayed "2.1 MB" is 2.10-2.19 billion mB, and above 2.147 billion that cast wraps
**negative**. The tank is handed a negative `FluidStack`, returns 0, and `canExtract` concludes
it cannot hold XP fluid. Refined Storage's own tooltip path, `tryFillFluidContainer`, asks the
same question with **one bucket** and gets it right; only `canExtract` asks "can you take all
of it".

**The fill decision no longer asks `canExtract`.** It asks whether the container is the right
*kind* for the row - fluid container and fluid row, chemical container and chemical row - which
involves no amount and so cannot be broken by one. This is a deliberate loosening: it will now
attempt a fill on a row the container turns out not to accept, and that click moves nothing.
**The cost of being too permissive is a click that does nothing; the cost of being too strict
was storing the player's tank in the network.** Those are not comparable, so it errs the cheap
way. That the tank was the *consequence* of a failed fill was the real defect here - a fill and
a store are different intentions and one should never silently become the other.

**Shift-fill above the same line uses bucket-sized transfers.** `ENTIRE_RESOURCE` asks for
`min(network total, Long.MAX_VALUE)` and that figure goes through the same narrowing on its way
to the container, so it would fail for exactly the resources this fixes. Above
`Integer.MAX_VALUE` the fill switches to `SINGLE_RESOURCE` operations, one bucket each, which
survive the conversion. Slower - and the operation cap will stop short of filling a large tank
in one click - but it moves something.

The diagnostic line under `logGridViewDiagnostics` now also reports `canHold`, `canExtract` and
`available`, so the disagreement between the first two is visible directly rather than inferred.

## 0.2.105

**0.2.104 made this less reliable, not more.** (Issue #17.) Reported in game: *"it's less
reliable than before, I barely got it to work a few times."*

0.2.104 resolved a container click only from slot rectangles recorded during rendering, and
**returned early when none of them matched**. That made one unproven assumption - that the
recording is populated and correctly placed - load-bearing for the entire feature, with no way
to tell from the outside whether it held. If it does not hold, every click falls through to
stock, which is worse than the stale-hover behaviour it replaced.

Three changes, in order of importance:

**The measured answer is now a preference, not a requirement.** When no rectangle matches, the
row falls back to the one Refined Storage resolved itself - the stale-hover value 0.2.103 used.
So the fresh answer can only ever be an improvement: when the rectangles are right the click is
resolved from its own coordinates, and when they are not, behaviour is exactly 0.2.103's. A
mechanism that can silently disable a feature should not be the only path to it.

**The blank-area right-click hook is back.** 0.2.104 folded both entry points into the click
router and lost the case the router cannot answer - "is this inside the storage area", which
`canInsert` has already decided by the time `mouseClickedInGrid` runs. Emptying a container
works anywhere in the grid again, including past the end of the list.

**There is now a diagnostic**, under the existing `logGridViewDiagnostics`:

```
[rstweaks][grid] container click button=0 shift=false at=(112,84) cells=63 cell=14
                 stale=Water resolved=Water canExtract=true
```

`cells` is how many rectangles were recorded, `cell` is which one the click hit (-1 for none),
and `stale` against `resolved` says which of the two answered. That distinguishes "the
recording is empty", "the recording is misplaced" and "the recording is fine and something else
is wrong" - three theories that are indistinguishable from the outside and which I would
otherwise be guessing between. Read the line before theorising about this again.

## 0.2.104

**Clicking a row faster than the game could draw it put the tank in the network.** (Issue #17.)

Reported in game on 0.2.103: *"sometimes it won't fill and will just insert, I think it's if I
hover over it too fast."* Exactly right, and the reason is that Refined Storage decides which
row you clicked while it is **rendering**, not while you are clicking:

```java
protected void renderRows(...) {
    this.currentGridSlotIndex = -1;                       // reset each frame
    ...
}
private void renderSlot(GuiGraphics g, int mouseX, int mouseY, int idx, ...) {
    boolean inBounds = mouseX >= slotX && ... ;
    if (inBounds && this.isOverStorageArea(mouseX, mouseY)) {
        if (resource != null) this.currentGridSlotIndex = idx;
    }
}
```

`mouseClicked` then reads `getCurrentGridResource()`, which is whatever the **last drawn
frame** was hovering. Move onto a row and click before the next frame arrives and the index is
stale or `-1`, so `canExtract` answers false and the click falls through to the insert branch.

In stock Refined Storage that is nearly harmless: the insert branch inserts the carried stack,
which is what a left-click over the grid does anyway. Holding a tank it is not — the tank goes
into the network instead of being filled. **This is a stock race that this feature made
expensive**, not something the feature introduced.

Clicks made with a container on the cursor are now resolved from **the coordinates the click
carries**, not from the last frame's hover. The slot rectangles are recorded as Refined
Storage draws them, at the point where it is already handed `slotX` and `slotY`:

```java
@Inject(method = "renderSlot(...IIILcom/refinedmods/.../ResourceRepository;II)V", at = @At("HEAD"))
```

so none of the layout - the seven pixel inset, the eighteen pixel pitch, nine columns, the
scrollbar offset - is duplicated on our side, and a change to any of it is picked up rather
than drifted from. The hit test is then RS's own, `mouseX >= slotX && ... <= slotX + 16`,
against those rectangles.

The hook also moved up from the two entry points to the router itself,
`mouseClicked(double, double, int, GridResource, ItemStack)`, because **the routing decision
was the thing that was wrong**. All four outcomes are handled there and the callback is
cancelled for every one of them - falling through for any would hand that case back to stock,
reading the same stale index. Clicks that are not over a drawn cell at all, or not carrying a
container, still return untouched.

Behaviour is unchanged from what 0.2.103 intended:

```
  left-click          on a row it can accept: fill the container by one bucket
  left-click          anywhere else in the grid: store the container as an item (stock)
  right-click         empty one bucket of it into the network, anywhere in the grid
  shift + either      as many operations as it takes to fill or empty it
```

All three injectors were checked against the shipped Refined Storage bytecode rather than the
decompiled source, because they carry `require = 0`: a descriptor that does not match would
disable the feature silently instead of crashing.

## 0.2.103

**A shift-click now sends its operations together, in the tick it was made.** (Issue #17.)

0.2.101 emptied a tank by re-sending one operation per tick for as long as the cursor stack
kept changing, then stopping after eight quiet ticks. It worked, and it felt like it: a click
took most of a second, and the last eight ticks of that were spent waiting to discover it was
already finished.

The count is now known before the first operation leaves. `GridContainers` divides the
container's contents - or its free space - by what one operation moves:

```java
long stored = 0;
for (int tank = 0; tank < fluid.getTanks(); tank++) {
    stored += fluid.getFluidInTank(tank).getAmount();
}
return operations(stored, fluid.drain(Integer.MAX_VALUE, SIMULATE).getAmount());
```

**Both figures are measured, not assumed.** The divisor is a simulated drain of everything,
which is exactly the amount a real `ENTIRE_RESOURCE` insert will move, because it is the same
call with `EXECUTE`. So the tier table in the 0.2.101 notes is documentation, not something
the code depends on: a config that changes a Mekanism rate, a tier we have not heard of, or a
tank from another mod entirely all answer correctly without being known about. Anything that
declines to give a number - full, empty, wrong resource, no opinion - falls back to a single
operation, which is Refined Storage's own behaviour.

The fill direction needs the resource as well as the container, because an empty tank cannot
be asked what it would give back, only what it would accept, and what it would accept depends
on what is offered. It comes from `GridResource.getResourceForRecipeMods()`.

Chemicals get the same arithmetic through `IChemicalHandler`, reached with reflective handles
resolved once alongside the capability lookup. They are resolved as a group, so a partial API
change disables the measurement rather than half-performing it. Note that `insertChemical`
returns the *remainder*, so what fits is the offer minus what comes back - the one place the
two APIs do not read alike.

Overshooting is harmless and undershooting is unlikely: an operation with nothing left to move
is a packet the server answers with a zero-length transfer, and the count is capped at 64.
What the network can accept is deliberately not part of the sum - that is the server's to
enforce, and having the client predict it would be predicting a condition rather than
measuring a container.

## 0.2.102

**Fixes 0.2.101: every container click also put the tank itself into the network.** (Issue #17.)

Left-clicking a fluid row put a bucket in the tank *and* stored the tank; right-clicking put a
bucket in the network *and* stored the tank. One cause for both, and it is worth writing down
because the flag is named for the opposite of what it does.

`CompositeGridInsertionStrategy`:

```java
public boolean onInsert(GridInsertMode insertMode, boolean tryAlternatives) {
    if (tryAlternatives) {
        for (GridInsertionStrategy alt : this.alternativeStrategies) {
            if (alt.onInsert(insertMode, true)) return true;
        }
    }
    return this.defaultStrategy.onInsert(insertMode, tryAlternatives);
}
```

The **default** strategy is `ItemGridInsertionStrategy`, which inserts the carried stack and
never declines. The fluid and chemical strategies are the **alternatives**. So
`tryAlternatives` does not mean "try harder if the normal thing fails" — it means *consider
fluids at all*, and passing false does not fall back to storing the tank, it goes straight to
storing the tank.

0.2.101 passed Refined Storage's own `clickedButton == 1` through, so every left-click and
every tick of a shift-repeat took the item path. Every insert now passes `true`. The fallback
that stores an **empty** tank as an item is unaffected — the fluid strategy declines an empty
container on its own, and the default runs after it.

**Left-click no longer inserts at all.** Left means "fill the container", and blank grid space
is not something to fill from, so left-clicking away from a resource row now keeps stock
behaviour rather than being intercepted. The bindings, with a fluid or chemical container on
the cursor:

```
  left-click          on a row it can accept: fill the container by one bucket
  right-click         empty one bucket of it into the network, anywhere in the grid
  shift + either      keep going until the transfer stops moving anything
```

## 0.2.101

**Grid clicks that make sense while you are holding a tank.** (Issue #17.)

The report was that shift-right-clicking a fluid or chemical tank into the grid moves 1 B like
a plain right-click, instead of the whole tank. Chasing it turned up something better worth
knowing: **"the whole tank" was never available on any binding.**

Refined Storage's insert modes are `SINGLE_RESOURCE` (one bucket) and `ENTIRE_RESOURCE`, and
`ENTIRE_RESOURCE` asks the container's own handler for `Long.MAX_VALUE` and takes what it is
given. A Mekanism tank gives one tier transfer rate per operation:

| Fluid Tank | capacity | per operation |
|------------|---------:|--------------:|
| Basic      |    32 B  |          1 B  |
| Advanced   |    64 B  |          4 B  |
| Elite      |   128 B  |         16 B  |
| Ultimate   |   256 B  |         64 B  |

So the observed "left-click moves 64 B" is the Ultimate tank's rate, not a clamp anywhere in
Refined Storage and not a bug in this mod. **Emptying a tank is not a bigger transfer, it is
the same transfer repeated.** That is the actual feature, and no amount of mode-picking would
have produced it.

Stock bindings, with a container on the cursor, were also the wrong way round for one: the
only precise action was right-click, "everything" was the default on left-click, shift meant
nothing at all on insert and "put it in my inventory instead" on extract. And because
`mouseClicked` commits to the extract branch the moment `canExtract` agrees, whichever button
was pressed, there was no way to dump a tank while pointing at the fluid it holds - you had to
find blank space in the grid.

New bindings, active **only** while the cursor holds a fluid or chemical container:

```
  left-click            fill the container by one bucket
  right-click           empty one bucket of it into the network
  shift + either        run that direction until it stops moving anything
```

Left fills, right empties, whichever you clicked. Anything else on the cursor - an ordinary
item, or nothing - routes exactly as it did, as do ctrl-click, autocrafting, scrolling, and
the fallback that stores an *empty* tank as an item. Config: `containerGridClicks`.

**Chemicals cost no dependency.** `GridContainers` recognises a chemical container by reading
one public static field, `ChemicalUtil.ITEM_CAPABILITY`, off Refined Storage's own Mekanism
integration by reflection. Naming `IChemicalHandler` to build the capability token ourselves
would have meant compiling against Mekanism to gain nothing: we never call the handler, only
ask whether the stack has one, and `ItemCapability` is a plain NeoForge type. Absent
integration, absent Mekanism, and any other failure all answer the same way - no chemical
support - so the catch is `Throwable` and clicking on fluids is unaffected.

**The repeat stops on an observed effect, not a predicted count.** `driveContainerRepeat`
re-sends the operation each tick for as long as the cursor stack keeps changing, and gives up
after 8 unchanged ticks or 64 operations. The alternative was to compute the count up front
from the container's capacity and rate - two numbers a mod may make dynamic, reachable for
chemicals only through more reflection, and wrong the moment either assumption slips. Issue
#15 took five wrong answers because its probes re-evaluated a condition instead of observing
an effect; this spends that lesson in advance. Eight ticks is chosen against server latency:
one operation is a packet out and a changed stack back, so a tick or two of "nothing yet" is
the normal case mid-transfer, not the end of one.

Shift is deliberately not re-checked while the repeat runs. A shift-click released quickly is
still a shift-click, and requiring the modifier to be held would make the result depend on how
fast the player let go.

Both injectors are `require = 0`. This is ergonomics; if another mod overwrites either grid
click path, the right outcome is stock behaviour, not a crash in a stranger's pack.

## 0.2.100

**Performance.** Functional Storage asks whether an item is on the drawer denylist twice for
every insert attempt, and the tag lookup underneath was **11.6% of LavaSurf's server thread**
— the second largest self frame in that profile, after the Sophisticated rescans 0.2.99
addressed.

`BigInventoryHandler` checks it at the top of `insertItem`, then again inside the `isValid`
that same call goes on to make:

```java
public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
    if (stack.is(StorageTags.DRAWER_STORAGE_DENYLIST)) return stack;   // here
    ...
    if (this.isValid(slot, stack)) {                                   // and again inside
```

Same stack, same tag, same answer, and Refined Storage drives that path once per slot per
returned craft output.

Worth saying why an apparently trivial set lookup costs that much, since "it is just a
`contains`" is the reason nobody looks twice at it. `stack.is(tag)` resolves to
`holder.tags.contains(tag)` over what is, for an item in a pack this size, an
`ImmutableCollections.SetN` holding dozens of entries. Its `probe` does a `Math.floorMod` —
an integer division — then walks the table comparing `TagKey` records, each comparison
descending into `ResourceLocation.equals` and two `String.equals` calls. A **miss**, which is
the overwhelmingly common answer here, probes until it reaches a null slot rather than
stopping early. So: not one slow call, an enormous number of moderately expensive ones. That
shape is why it shows up as `SetN.probe` self time rather than anywhere obvious.

The cache is keyed on `Item`, because that is what the answer actually depends on — a tag
holds items, and no component, count or damage value can change membership. A
reference-keyed fastutil map makes each lookup an identity hash and one array index, with no
division and no string comparison. Keying on the stack instead would have reintroduced the
same hashing cost it is meant to remove.

**Invalidation is total and comes from the game.** Tag contents change on exactly one event —
`TagsUpdatedEvent`, at datapack load and after every `/reload` — and the whole map is dropped
there. Between two of those, the answer is immutable, so a cached answer and a fresh lookup
cannot disagree. There is no expiry to tune and no staleness window, which makes this a
weaker claim than 0.2.99's per-tick cache needed to make, not a stronger one.

Both call sites are redirected, not just the redundant second one: skipping it would need the
mixin to know who called `isValid`, and `isValid` is reachable from elsewhere. Caching both is
simpler and leaves no path uncovered. On the first sighting of an item, or with the toggle
off, the original `stack.is(tag)` runs and its result is what gets returned and stored.

Verified at bytecode level before shipping, since a `@Redirect` that fails to match is silent
until runtime: `javap -c` confirms exactly one `ItemStack.is:(Lnet/minecraft/tags/TagKey;)Z`
invocation in each of `insertItem` and `isValid`, so `defaultRequire = 1` proves both were
found.

One detail worth recording: `DrawerDenylist` takes the `TagKey` as a parameter rather than
importing `StorageTags`. It is registered on the NeoForge event bus unconditionally — gating
that on `ModList` would run before `ModList` is dependable — so the class must not carry a
reference to a mod that may be absent. The mixin already has the tag in hand.

Config: `cacheDrawerDenylist` (default on). Counter: `drawerDenylistLookupsAvoided`.
Registered in the existing `rstweaks.functionalstorage.mixins.json`, already gated on
`functionalstorage`.

**Remaining from that profile:** `InventoryPartitioner.getPartBySlot` calls `parent.getSlots()`
as a bounds check on every stack read (7.6%). Still deliberately untouched pending a
re-profile on 0.2.99 — most of it is downstream of rescans that should no longer happen.

## 0.2.99

**Performance.** Sophisticated Storage ships a cache whose entire job is to stop a barrel
being rescanned after it has already refused an item. It has never been able to hit when
Refined Storage is the one inserting.

From LavaSurf's 60-second server-thread profile (`23eSKjSDH2`, 2026-08-20): one autocrafting
task was **94.5% of the server thread**, and it was not planning or extraction —
`InternalTaskPattern.returnOutput` alone was 92.5%. The task was returning its crafted
outputs, every returned stack was reaching External Storage rather than a disk, and **54% of
the whole thread** (32.6 s of 60 s) was inside
`CachedFailedInsertInventoryHandler.insertItem`.

The cause is one field:

```java
private final Set<ItemStack> failedInsertStacks = new HashSet<>();
```

`ItemStack` declares neither `equals` nor `hashCode` in 1.21.1 — checked by reflection
against the game jar on the real classpath, because this is exactly the kind of thing that
is wrong when assumed. So that `HashSet` is an identity set: it can only answer "yes" if
handed back the very same object. Refined Storage builds a fresh `ItemStack` for every
insert attempt — `ItemResource.toItemStack`, and `ItemStack.<init>` is itself 3.1% of the
thread — so `failedInsertStacks.contains(stack)` was false every single time.

The profile shows that shape exactly, and it is the reason to believe the diagnosis rather
than merely find it plausible: **32.6 s in `insertItem` against 0.1 s in `HashSet.contains`**.
A cache that was working would look the opposite way round.

So a second record sits beside upstream's, keyed by item and components instead of by
identity, using the same primitives Sophisticated's own `ItemStackKey` uses for equality
(`hashItemAndComponents` / `isSameItemSameComponents`). We deliberately do **not** call
`ItemStackKey.of`: it memoises into a `ConcurrentHashMap<ItemStack, ItemStackKey>`, which is
identity-keyed too, so feeding it RS's fresh stacks would add an entry per attempt and grow
without bound.

**This caches rejections, never item data** — the same distinction that justified the Drawer
Controller fix in 0.2.1. Nothing here reports what a barrel holds or how much room it has.
The cached answer is only "an insert of this exact item into this exact barrel already came
back untouched, this tick", and every call that is not short-circuited still reads contents
live.

Three things bound the staleness. The first two are upstream's own contract:

- **Per tick.** Upstream already clears on every game-tick change — that *is* the assertion
  "within one tick, a stack that failed will fail again". We keep that clock.
- **Total failures only.** A rejection is recorded solely when `insertItem` returns the
  argument object itself, which happens only when *nothing* moved. A partial insert returns
  a different remainder stack and is never cached.
- **Cleared on extraction.** Upstream does not do this; we do. Taking items out is the one
  thing that can make room appear mid-tick, so any successful `extractItem` drops the map.
  That makes this strictly tighter than the identity cache it sits beside, not looser.

The stored value is the smallest count that has failed, and a later attempt is
short-circuited only when it is at least that large. A total rejection means the barrel had
room for zero, so a smaller stack arguably must fail too — but that argument reasons about
upstream's slot-limit maths from the outside, and a void or compression upgrade is exactly
the kind of thing that could make a barrel non-monotonic in count. One int comparison is a
cheap price for not having to be right about it.

Simulated inserts are left alone, exactly as upstream leaves them. A simulation is what a
caller uses to ask whether room exists, and answering that from a cache is how a planner
ends up believing something that is no longer true.

Config: `cacheFailedInsertsByValue` (default on). Counter: `failedInsertScansAvoided`.
New mixin config `rstweaks.sophisticatedcore.mixins.json`, gated on `sophisticatedcore` and
pinned to `[1.4.80,1.5.0)`. Note that Sophisticated Core, unlike Step Crafter, Cable Tiers
and Functional Storage, declares a **plain** version in its own `mods.toml` (`1.4.80`) even
though its filename and MANIFEST carry `1.21.1-1.4.80.2194` — the range must match what the
loader reads, so it carries no Minecraft prefix.

This is an upstream bug in Sophisticated Core and is worth reporting there.

**Still outstanding from the same profile**, not addressed here:

- Functional Storage's `BigInventoryHandler` runs `ItemStack.is(TagKey)` per insert attempt;
  `ImmutableCollections$SetN.probe` is 11.6% of the thread.
- `InventoryPartitioner.getPartBySlot` calls `parent.getSlots()` as a bounds check on every
  stack read (7.6%). Deliberately left alone until this build is re-profiled — most of that
  time is downstream of the same rescans and should disappear on its own, and caching a slot
  count that `changeStorageSize` can move is not worth doing for a win that may already be
  gone.

## 0.2.98

**Bug fix.** A hard server crash whenever anything read the storage list — opening any grid,
starting any autocraft — on a network whose cached total for one resource had overflowed.

Reported by LavaSurf on 2026-08-18. The crash arrived as `IllegalArgumentException: Amount
must be larger than 0` from `ResourceAmount.validate`, thrown out of
`MutableResourceListImpl$Entry.toResourceAmount` while `copyState` was building the list,
reached first through the Step Requester's crafting calculation and then, reproducibly, by
opening a grid. It survived a relog.

Surviving the relog is what identified it. `CompositeStorageImpl` rebuilds its list from
scratch on every network build via `addContentOfSourceToList`, which is
`source.getAll().forEach(this.list::add)` — nothing corrupt is persisted to disk, so the sum
had to be re-overflowing from the storages themselves on every single load. It was never
cache drift.

`Entry.increment` is the only unguarded route into an entry's amount:

```java
private void increment(long amountToIncrement) {
    CoreValidations.validateLargerThanZero(amountToIncrement, "...");
    this.amount += amountToIncrement;   // no overflow check
}
```

Every other route is guarded — `addNew` validates, `decrement` refuses to reach zero, and
`remove` deletes the entry rather than let it. So an entry holding a non-positive amount can
only have got there by wrapping past `Long.MAX_VALUE`, and once one has, the list is a
landmine: harmless until something calls `getAll()`, and then fatal. `copyState` is a
terminal stream collect, so it throws part way through and returns nothing at all — the
symptom is a ticking-block-entity crash, not an empty screen.

Getting there needs a storage reporting an enormous amount. On the reported network the
sources were Refined Types energy External Storages: `EnergyCapabilityCache` reads Grand
Power's `ILongEnergyStorage.getAmount()`, a `long` rather than NeoForge's `int`-capped
`IEnergyStorage.getEnergyStored()`, and reports it under `EnergyResource.ENERGY_RESOURCE` —
a static singleton, so every energy External Storage on the network sums into one shared
`Entry`. One large source plus anything else wraps it.

The total now saturates at `Long.MAX_VALUE`. Saturating rather than refusing the addition is
deliberate: refusing would leave the cached total quietly lower than what the storages hold,
and that list is what `RootStorage.get` answers from — a silent undercount is the shape of
bug that makes items unreachable, which is the failure this project has already been bitten
by once. A saturated total is a number nobody could represent anyway, and every invariant
downstream holds.

Fixed in Refined Storage's own layer rather than in Refined Types, because that is where the
invariant lives, it covers every resource type including ones no addon has written yet, and
it needs no addon to be present. `clampResourceAmountOverflow` turns it off.

**Nothing needs repairing on an affected save.** The bad entry is never written to disk, so
the first network build after this version loads produces a sane list and the grid opens.

Each clamp is logged once per resource, naming the resource, so the storage causing it can be
found; the count is in `Stats` but deliberately kept out of the `/rstweaks stats` rotation,
where every other counter is a saving and this one means something is wrong.

New gametest `resourceTotalsSaturateInsteadOfWrapping` (7 scenarios). With
`clampResourceAmountOverflow = false` it reproduces the reported crash exactly — the total
lands on `-9223372036854775808` and `CompositeStorageImpl.getAll` throws `Amount must be
larger than 0` — and it pins the boundary (a total landing exactly on `Long.MAX_VALUE` is
representable and must not be clamped), that ordinary addition is untouched, that a saturated
entry still extracts, and that RS's own rejection of a non-positive addend survives.

## 0.2.97

**Test-only. No behaviour change.**

0.2.96's scenarios tested `BranchAndBound.solve` directly, which proves the contract but not
the coupling that actually broke: `LpCraftingPlanner` decides a craft is impossible by
matching the literal string `"no integer solution"` out of `PlanMatrix`. Nothing pinned that
literal, so renaming the failure message would silently unhook the verdict and no test would
notice.

Two scenarios added (52 total). `PlanMatrix.solve` takes its caps as arguments, so both drive
the real chain on a graph known to plan -- the recycled-container shape -- once with a node
budget of zero and once with a real one. The starved run must not produce that string; the
funded run must produce a plan, so the starved one is a genuine false negative rather than an
unsolvable graph. Both confirmed red with 0.2.96's bug reinstated, alongside the four from
0.2.96.

Also verified: `runGameTestServer` is 8/8 with 52 sub-scenarios across four suites, no mixin
failures and no exceptions.

## 0.2.96

**Some late-game crafts could not be started at all.** Reported by LavaSurf on Mekanism's
Teleportation Core and the ATM Star's star fragment recipe: the request screen said the
craft was impossible and Start stayed disabled. **Switching the preview from list to tree
made it work**, which is the clue that unravels it.

- **The preview style is not a display toggle.** It is sent to the server, and RS answers it
  with a different calculator listener each way. There are **four**, not the three recorded
  previously: `TaskPlanCraftingCalculatorListener`, `PreviewCraftingCalculatorListener`,
  `IsCraftableCraftingCalculatorListener`, and `TreePreviewCraftingCalculatorListener`. We
  hook the first three. Tree preview therefore bypassed the planner entirely and got stock
  RS's answer, which was correct.
- **The planner was reporting a budget as a proof.** `BranchAndBound.solve` returned `null`
  both when it proved no integer solution exists and when it merely stopped looking -- at
  the depth cap (64), the node budget, a pivot cap inside `Simplex`, or an overflowed
  branch. Only the node budget was tracked, and that flag was discarded whenever no
  incumbent was found, which is precisely when it mattered. `PlanMatrix` labelled the lot
  "no integer solution", `LpCraftingPlanner` promotes exactly that string to `impossible`,
  and `PreviewCraftingCalculatorListenerMixin` treats `impossible` as authoritative and
  disables Start.
- **Big graphs are what hit caps**, so the crafts this made unavailable were the deep
  late-game ones. Nothing was wrong with those recipes.

The fix: `Simplex` now throws `PivotLimitExceeded` for a pivot cap and keeps `null` for a
genuine contradiction; `BranchAndBound.Result` carries a `complete` flag cleared by every
path that abandons work, and is returned even with no solution. Only a complete search that
found nothing is reported as infeasible. Everything else declines, and a decline falls
through to stock RS untouched.

**The asymmetry is worth naming**, because it is what turned a solver limitation into a wall:
`calculatePlan` treats the planner as advisory and ignores a decline, while
`calculatePreview` treats the same planner as authoritative. One bad verdict there is not a
worse plan, it is a craft the player cannot attempt.

Four scenarios added to `plannerCheck` (50 total), each confirmed to fail with the old
behaviour reinstated. One of them asserts a genuinely infeasible program *still* reports a
complete search -- the obvious over-correction is to stop proving impossibility at all.

**Not yet confirmed in game.** See the note about verifying in game, not just headlessly.

## 0.2.95

**A correction to 0.2.93's diagnosis. No behaviour change — the fix was right and the
explanation shipped with it was too narrow.**

- **The duplicate-task flood does not need a regulator upgrade.** 0.2.93 said it did, in the
  config text, the mixin javadoc and the issue comment. Wraith said his original sighting — a
  mega exporter into a Dyson-cube rail ejector, the autocrafting monitor flooded with 10+
  tasks crafting one item each, all the same resource — had no regulator on it. Taking that
  seriously rather than defending the diagnosis is what found the real common case.

- **The general rule is `currentlyCrafting >= amount`, and there are two ordinary ways the
  running total never catches up.** Only one of them is the regulator.

- **The common one: the network cannot craft as many as the exporter asks for.** Quota 64,
  ingredients for one craft. `calculatePlan(resource, 64)` fails, so `ensureTask` falls through
  to `ensureTaskForCraftableAmount`, which clamps to `binarySearchMaxAmount` — 1 — and starts a
  task for **1**. One is not sixty-four, so the next request starts another task for 1, and the
  next, and the next. Every task crafts a single item, they are all the same resource, and
  nothing suppresses any of it. That is the reported symptom exactly, down to the one-item
  crafts, and ingredients trickling in is the *normal* state of an autocraft — so this is the
  case people actually hit, and the regulator is the rarer one.

  ```
  quota     [64, 64, 64, 64, 64, ...]   constant, no regulator
  results   [TASK_CREATED x12]          12 requests -> 12 tasks, one item each
  ```

- **`waitForRunningCraft` already covered both**, because it asks only whether anything is
  running rather than trying to enumerate the ways an amount can outrun it. That is now the
  stated reason for the design rather than a happy accident.

- **`AutocraftingRequestSelfTest` gains the no-regulator scenario**, asserted on both sides of
  the flag like the rest: 12 tasks with the flag off, 1 with it on. Six fixtures now.

- **The lesson, since this is the second correction in two days.** 0.2.93 was closed on a
  headless reproduction of a mechanism that had never been shown to be the reported one, and
  an in-game report was then read as confirming it without checking which jar was installed.
  A reproduction proves a mechanism *exists*. It does not prove it is *the* one, and the
  reporter contradicting your explanation is evidence, not noise.

## 0.2.94

- **`/rstweaks stats` prints one counter per line.** A header, then each non-zero counter on
  its own line with the number coloured separately from the label:

  ```
  [rstweaks] session totals:
    84 duplicate craft requests refused
    32 indexed extractions (56.3% hit, 22 rebuilds)
    7,765 slot-count lookups avoided
    61 plan copies avoided
  ```

  Twelve figures comma-joined into one sentence wrapped at whatever width the reader's chat
  box happened to be, so the number you came to look at landed in the middle of a paragraph.
  Wraith asked for this on 2026-08-17.

- **Nothing posts the counters unprompted any more.** `chatNotificationIntervalSeconds`
  defaults to 0 (was 300), and the join message no longer appends a summary. The counters are
  worth reading when you go looking for them and are spam when they arrive on their own. The
  periodic broadcast is still there for a server where nobody will type a command but somebody
  is watching chat — set a number of seconds to get it back.

- **The join line stays**, carrying the version and the active features. That one is
  load-bearing: a test result has to be attributable to an exact build, and chat is the only
  place that is visible without opening a log. `chatNotifications` now describes only that.

- **`statsPrintOnePerLine` gametest**, which logs the real rendered output as well as asserting
  it. The report is assembled by index-splitting formatted strings, which is exactly the kind
  of code that throws in front of a player rather than in a test; an entry without a space in
  it now goes out unsplit instead of taking a chat message down with it.

## 0.2.93

- **An autocrafting request now waits for the craft already running for that resource (issue
  #14).** `waitForRunningCraft`, default on. Anything already being crafted for a resource is
  enough to answer `TASK_ALREADY_RUNNING`, where stock Refined Storage only refuses when the
  running total reaches the amount asked for.

- **The report blamed the tiered exporters, and they are innocent.** Cable Tiers builds its
  exporter from Refined Storage's own registered factories, so it inherits the
  `MissingResourcesListeningExporterTransferStrategy` wrapper and therefore the check; and its
  several calls per `doWork()` each see the previous call's task, because `TaskContainer` keeps
  tasks in a `CopyOnWriteArrayList` added to synchronously. Step Crafter's Step Requester
  bypasses `ensureTask` entirely but carries its own per-slot `isAlreadyRunningTask` guard.
  Measured, not reasoned: eight requests in one tick start one task.

- **The actual culprit is the REGULATOR upgrade, and it is arithmetic rather than a race.**
  `ensureTask` refuses only when `currentlyCrafting >= amount`. For a plain exporter `amount`
  is `ExporterTransferQuotaProvider`'s base quota — a constant 1, or 64 with a stack upgrade —
  so one task covers every later request. But the autocrafting quota provider is built with
  `respectTransferQuotaWhenRegulating = false`, so with a regulator `amount` is the *entire*
  outstanding shortfall, `desiredAmount - destination.getAmount(resource)`. Drain the
  destination faster than it fills — a machine eating from a regulated buffer — and that
  number grows. Every increase exceeds what is running, so `ensureTask` tops up the difference
  with **another task**:

  ```
  amounts   [8, 24, 40, 56, 72, 88, ...]
  results   [TASK_CREATED, TASK_CREATED, TASK_CREATED, ...]   20 requests -> 20 tasks
  ```

- **Those tasks are not bookkeeping.** Each builds its plan with a full crafting calculation,
  carries its own internal storage, and is stepped every tick until it finishes. This is the
  same expense the Step Requester backoff and the uncraftable cache exist to avoid, met from
  the direction where the request *succeeds* — which is exactly how the issue framed it.

- **Deliberately not a timed cooldown.** A cooldown has to guess a duration and can suppress a
  request when nothing is running at all. Reading the live task list cannot: the moment the
  running task finishes, the next request is answered normally. The cost is that a large
  regulated buffer refills serially rather than in parallel, which is the trade the config
  option exists to reverse.

- **`AutocraftingRequestSelfTest` asserts both sides of the flag** across five fixtures, so the
  suite states the finding rather than only the fix: with `waitForRunningCraft` off it requires
  the regulator case to produce 20 tasks and the other four to produce 1, and with it on it
  requires 1 everywhere. A scenario that stopped reproducing would otherwise report the fix
  working when there was nothing left to fix.

## 0.2.92

- **Gametests for the three paths that only fail in a running game, and a Gradle task that runs
  them without anybody launching Minecraft (issue #2).** `./gradlew runGameTestServer` boots a
  dedicated server, stages Refined Storage into its mods folder, runs every `@GameTest` in the
  `rstweaks` namespace with our mixins applied, and exits non-zero if any fail. About a minute,
  no client, no world, no hand actions. Nine manual round trips in one day over the 0.2.63-0.2.81
  bugs is what this replaces.

- **Refined Storage is `compileOnly`, so a dev run came up without the mod every mixin targets.**
  The new `stage<Run>Mods` tasks sync `libs/refinedstorage-neoforge-*.jar` into each run's
  `mods` folder, where FML's mods-folder locator finds it exactly as it would in a real instance.
  Only Refined Storage: two other jars in `libs/` depend on mods that are not present, and one
  missing dependency fails the whole launch.

- **Why these cannot be part of `plannerCheck`.** Nothing transforms Refined Storage's bytecode in
  a plain JVM, so a headless run of any of the below exercises stock RS and passes no matter what
  our code does. `ExtractionSelfTest` had been in that position since it was written: it toggles
  `externalStorageSlotIndex` and compares the two runs, and under `plannerCheck` both runs were
  the same unmixed code.

- **`TaskEngineSelfTest` — a real `TaskImpl` stepped to completion over a real `RootStorageImpl`,
  across six fixtures** including a plain two-step craft planned by stock RS, a recycled container,
  a self-duplicating pattern, and two durability graphs. The step loop reproduces
  `TaskContainer.step`'s `catch (Exception)` on purpose: RS's answer to a throw in a task step is
  to mark the task *completed*, fire the finished toast and drop it with its internal storage
  inside, so a throw has to be reported as the item destruction it is rather than as a harness
  stack trace. Completion is never accepted on its own — every resource is audited against
  `stored + made - used`, and durable tools against uses in versus uses out.

- **`ExtractionSelfTest` gained the stale-index path**, which nothing had ever reached. Two
  extractions run against **one** storage with the inventory rearranged in between, which is what a
  hopper or a player does and what no fresh index can be. Asserted three ways: what physically left
  the inventory must equal what was reported, a SIMULATE must move nothing and never promise more
  than exists, and the whole sequence must answer identically with the index off.

- **`CraftingGridRefillGameTest` builds an actual network out of blocks** — creative controller, 1k
  storage, 64B fluid storage, Crafting Grid — and drives the real `useIngredientWithRemainingItem`
  on the real result slot out of a real menu. A stub network would have kept passing through the
  0.2.79 failure, where this feature was gated on a pattern that only existed because our own fluid
  substitution registered it: parking that feature switched this one off silently. Both payment
  routes and the decline are covered, and the decline matters most — without it the other two would
  keep passing if the refill started declining everything.

- **Every suite was confirmed to fail with the bug it targets reinstated**, which is the only
  evidence that a passing test means anything:
  - 0.2.55's stale-entry exit (`partial = 0` instead of the running total) → *"took 808 iron ingots
    out of the inventory but reported 745 — ITEMS DESTROYED"*. **The first version of these
    fixtures did not catch it.** They disturbed the *first* candidate slot, where the exit is
    reached with nothing extracted yet and reporting the total and reporting nothing are the same
    number. Moving the disturbance later down the candidate list is what gave them teeth.
  - 0.2.64's throw above the guard in `AbstractTaskPattern.extractAll` → all six task fixtures fail,
    which also proves all six really reach that code.
  - The refill declining everything → the two payment tests fail, the decline test still passes.

- **Two mechanical notes for anyone extending this.** `CraftingGridResultSlot` is package-private
  with a private target method, and Refined Storage is a named module, so `setAccessible` is
  refused; the seam is a second mixin (`CraftingGridResultSlotTestMixin`) that shadows the method
  and exposes it. And the menu the slot comes out of is closed the instant the slot is in hand — an
  open grid menu registers as a network watcher, and the first storage change then pushes a
  `grid_update` packet at a mock player that has no connection to take one, which NeoForge refuses
  with an exception our own guard catches and turns into a silent decline.

## 0.2.91

- **Fixes the phantom `0` row, and the cause was the one thing four rounds of logging never printed
  (issue #15).** `preventSorting`. 0.2.86 identified that flag correctly and fixed the two
  mouse-wheel paths that latched it; the phantom outlived the fix, and every probe since has
  printed `sticky`, `removedFromBacking` and the backing amount while never once printing the flag
  that outranks all three.

- **Latched, `updateExisting` has a third arm that removes nothing:**

  ```java
  boolean canBeSorted = !this.preventSorting;
  if (canBeSorted) { ...remove or update... }
  else if (removedFromBackingList) { LOGGER.debug("{} is no longer available"); }  // returns
  ```

  A resource that left the network keeps its row while its backing entry is gone, and `getAmount`
  renders it as `0`. That accounts for every observed fact at once: `ViewList.remove` was never
  called (0 hits from 0.2.90's probe), our own line said `REMOVED` because it predicted the branch
  from the other two fields, the row was not sticky, Step Crafter was not maintaining it, and any
  key release calls `sort()` and clears it.

- **What 0.2.86 got wrong was the exit, not the flag.** It reasoned that gating the wheel latch on a
  held modifier sufficed, because "holding shift means eventually releasing it, and that release
  reaches `keyReleased`". `keyReleased` **on the grid screen** is the only code in all of Refined
  Storage that clears the flag, so any path that ends the modifier somewhere else latches it for
  the life of the screen. Starting an autocraft is exactly such a path: the preview opens over the
  grid and takes the key events with it, so a shift released there never reaches the grid. Stock
  `keyPressed` latches on *any* key pressed with shift down, so this needs no wheel at all.

- **So the fix stops enumerating paths and restates the invariant once a tick:** the flag may only
  be set while a modifier is actually held. `containerTick` now runs `keyReleased`'s own body when
  neither shift nor ctrl/cmd is down, so a latch left by any route heals within a tick and behaves
  exactly as if the release had arrived. While a modifier is genuinely held nothing changes and
  Refined Storage's intended "hold shift to keep the list still" is untouched. One field compare
  per tick in the common case.

- The diagnostic now logs `preventSorting` on every update and in the phantom report, so if a row
  ever survives this, the log says so in the first line rather than the fifth build.

## 0.2.90

- **The audit caught it, and the answer contradicts itself (issue #15).** One craft, one hit:

  ```
  update iron_ore_hammer@6 by -1 (backing had 1, sticky=false)
    existing row: REMOVED (removedFromBacking=true, sticky=false, backing now 0)
  PHANTOM ROW ... iron_ore_hammer@6 is in the view list, the backing list holds none of it
  ```

  A row cannot be removed and still be there. `ResourceRepositoryImpl` removes one by calling
  `ViewList.remove`, which does `index.remove(resource)` on the same key `viewList.get(resource)`
  had just found a moment earlier — and a `HashMap` that finds a key with `get` always drops it
  with `remove`. So `remove` was never called, and the branch condition
  `removedFromBackingList && !stickyResources.contains(resource)` was false. With
  `removedFromBacking=true` logged, that leaves only one possibility: the `contains` call returned
  **true**.

- **Which is impossible unless something rewrote it, and something did.** Our own line above reads
  the same set on the same object and gets `false`. A call answering differently inside a method
  than outside it is a `@Redirect`, and Step Crafter ships one on exactly this call —
  `MixinResourceRepositoryImpl.checkForMaintainingResources`, returning `sticky || isMaintained` so
  a resource one of its Step Crafters maintains keeps its row. Our probe reads the raw set and
  cannot see the redirect, which is why it has been reporting REMOVED for rows that were kept. Step
  Crafter 0.1.6 is installed in the pack this was reported from.

  It also explains the one fact that killed the plain sticky theory: a SHIFT tap clears the row,
  because `sort()` rebuilds from the backing list and Step Crafter's separate `@ModifyArg` only
  widens the set passed to `createSorted` for resources it is actually maintaining.

- **Four deductions about this bug have already been wrong, so this one gets checked too.**
  `ViewListDiagnosticMixin` logs whether `remove` was called at all, and the phantom report now
  asks Step Crafter, by reflection, what it claims about that exact resource. One reproduction
  distinguishes every remaining possibility:

  - `ViewList.remove CALLED` absent + `MAINTAINING this resource` → confirmed, and it is Step
    Crafter's redirect keeping a row for a resource that left the network.
  - `remove` absent + `not maintaining it` → something else redirects or overwrites that branch.
  - `remove CALLED` and the row still present → the fault is in `ViewList`/key identity after all,
    and every conclusion above is wrong.

- Still default off, still behaviour-free. `ViewList` is package-private so the new mixin targets
  it by name, the same trick as `ProcessingPatternClientTooltipComponentMixin`.

## 0.2.89

- **The 0.2.87 probe came back clean while the phantom was still on screen (issue #15).** A 0.2.88
  session ran two durability crafts with the grid open and `logGridViewDiagnostics` on. Every
  removal reported `REMOVED`; nothing added or kept a row over an empty backing entry:

  ```
  update iron_ore_hammer@61 by -1 (backing had 1, sticky=false)
    existing row: REMOVED (removedFromBacking=true, sticky=false, backing now 0)
  ```

  That is this probe's own documented signal that the row is not created by the three paths it
  watches.

- **Two more candidates died with it.** The row still clears on a SHIFT tap, so it is not sticky —
  `ViewList.createSorted` re-adds every sticky resource on rebuild, which is how autocraftable
  items legitimately show a `Craft` row at zero, so a sticky phantom would *survive* the tap. And
  `MutableResourceListImpl.removeCompletely` drops an entry rather than zeroing it, so the backing
  list cannot hold a zero and a freshly sorted view cannot contain a phantom either. Between them
  that accounts for every path in `ResourceRepositoryImpl`, which is the point: the row is being
  created somewhere else.

- **So the diagnostic stops guessing.** It now checks the invariant directly, after every update
  and every sort: nothing may sit in the view list with an empty backing entry unless it is
  sticky. Any violation is reported as `PHANTOM ROW`, naming the resource, and carries a stack
  trace of where it was noticed. This catches the bug however the row got there, which is the
  property the per-path logging lacked — four theories built by reading `ResourceRepositoryImpl`
  have now been wrong, three of them at the cost of a build and an in-game test each.

- **Reflection, deliberately.** `ViewList` is package-private in Refined Storage's own package, so
  its type cannot be named from a mixin at all, and its index is the only place the view's
  `ResourceKey`s exist — `GridResource` exposes an amount and a name but never its key. The lookup
  is wrapped so a shape change downgrades the audit to silence rather than breaking a grid.

- Still `default off`, still behaviour-free, and now O(view size) per update on top of the existing
  per-resource lines. Turn it on to reproduce, then turn it off.

## 0.2.88

- **A worn tool's remaining uses were stranded when a replacement was crafted (issue #10).**
  Reported as "durability crafting sometimes does not use the damaged tool first", and the
  *sometimes* is the whole of it: the failing case needs the stored tool to hold **fewer** uses
  than the request, so a replacement genuinely has to be made. From the log — one
  `iron_ore_hammer@93` with 3 uses left, 64 gold dust wanted:

  ```
  LP plan for 64x gold_dust: 2 patterns [32x ROOT dust, 1x Pattern[7031...]],
    initial requirements [raw_gold x32, iron_block x2, stick x3]
  ```

  `iron_block x2 + stick x3` is a new hammer. The `@93` is not in the requisition at all, so the
  task never saw it: 32 uses came off the fresh hammer and the old one's last 3 were left behind.

- **Not where the issue thought it was.** The issue proposed a bounded preference in the LP
  objective, on the reading that the solver was choosing between two equal-cost solutions. There
  are not two solutions. Both outcomes are the *same* solve — 32 dust iterations, 1 hammer — and
  they differ only in what the plan then asks the network for, so no objective term could have
  separated them. `initialRequirements` is downstream of the solver and is where the choice
  actually lives. The extraction-order and pristine-versus-damaged theories the issue carried are
  both exonerated by the same reasoning: the requisition never named the tool, so
  `TaskImpl.extractInitialResourcesAndTryStartRunningTask` and `findWornTool` never had a choice
  to make.

- **The fix, in `PlanMatrix.netConsumption`.** Netting production against consumption is the right
  question for an item — craft eight gears, consume eight, ask the network for none. It is the
  wrong question for a class denominated in *uses*, because a use is not fungible with the item
  carrying it. 3 uses in stock, 32 burned, 96 produced: the net is zero and the requisition is
  empty, while the truth is that three of those uses already exist and should be spent first. A
  tool class now asks for the gross uses the plan will burn; the existing clamp to available stock
  caps it at what the network actually has, and `toolsCovering` turns that into whole tools, most
  worn first. When no tool is being crafted, production is zero and the net already equalled
  consumption — so nothing else changes, which the other nine durability scenarios confirm.

- **Headless coverage: "worn tool with too few uses, replacement crafted anyway".** Written before
  the fix and watched to fail with the exact reported shape (`initial requirements [material x8,
  gem x8]`, no crystal). It asserts both halves, because either alone passes something wrong: the
  crystal count alone would accept requisitioning every tool in the network, and the requisition
  alone would accept crafting a replacement that was never needed.

- **`DURABILITY_SCENARIOS` corrected from 6 to 10.** It is maintained by hand and had drifted
  behind the checks it counts, which makes the reported scenario total useless for noticing that a
  check has silently stopped running — the one failure mode this suite is least able to see.

## 0.2.87

- **0.2.86 did not fix issue #15.** It fixed a real Refined Storage bug with the same symptom —
  confirmed in game, both before and after — but the reported phantom outlived it. Recording that
  plainly because the 0.2.86 entry below reads as a fix for #15 and is not one.

- **What the symptom now pins down.** With 0.2.86 installed, the phantom still clears when a key is
  tapped, still disappears when the grid is reopened, and appears as a *second* row for the same
  tool alongside a correct one. So it is client-side only — the server's `GridData` snapshot is
  right — and it is a row in the view list whose backing-list entry is gone, which is why
  `AbstractGridResource.getAmount` renders it as zero.

  Reading `ResourceRepositoryImpl`, three paths can leave that state and two are now excluded by
  the symptom itself: `preventSorting` (fixed in 0.2.86, phantom outlived it) and sticky resources
  (`ViewList.createSorted` re-adds sticky on every rebuild, so a sticky row would *survive* the
  re-sort — this one does not). The remaining candidate is `tryAddNewResource` reached on a
  *removal*: it runs whenever the view list has no row for a resource the backing list did have,
  and re-adds the row from the mapper without consulting the amount, so if the update that got
  there was the removal of the last one, the row it creates is backed by nothing.

- **`logGridViewDiagnostics`, default off.** A read-only probe that changes no behaviour. Refined
  Storage already logs these transitions in `ResourceRepositoryImpl`, but at DEBUG, where a pack's
  log level buries them; this restates them at INFO and adds the one fact its messages omit — what
  the backing list holds *after* the change. The line to look for reports `backing now 0` on a path
  that added or kept a row:

  ```
  [rstweaks][grid] update alltheores:iron_hammer@34 by -1 (backing had 1, sticky=false)
  [rstweaks][grid]   no existing row -> tryAddNewResource alltheores:iron_hammer@34 (backing now 0)  <-- PHANTOM
  ```

  If instead every removal reports `view row REMOVED` and the phantom still appears, it is not
  coming from this class and the search moves to whatever else mixes into the grid view.

  Written this way deliberately: three theories built by reading this code have now been wrong, two
  of them at the cost of a build and an in-game test each. The reason string on a decline line
  answered issue #9 in one line after hours of reasoning had produced two wrong theories, and the
  same move is overdue here.

## 0.2.86

- **The phantom `0` row after a durability craft (issue #15).** After a craft wore a tool, the grid
  kept a row at `0` for the wear level that had just been used up. Tapping SHIFT cleared it; the
  next craft brought it back. Display only — storage was always correct, and nothing was ever lost.

  **This is a stock Refined Storage bug and it reproduces with no addons at all**: open a grid,
  scroll the item list with the wheel, extract the last of any resource, and its row sits at `0`
  until you press and release a key. Confirmed in game, and the same code is on Refined Storage's
  `develop` branch, so 3.x has it too. Reported upstream.

  `preventSorting` exists so the list does not reorder under your cursor mid-interaction, and it is
  documented and configured as a *while SHIFT is down* behaviour — `keyPressed` sets it only when
  `hasShiftDown()` and the `preventSortingWhileShiftIsDown` option both agree. The two mouse-scroll
  handlers honour neither:

  ```java
  private void mouseScrolledInGrid(boolean up, GridResource resource) {
      getMenu().getRepository().setPreventSorting(true);   // first statement
      GridScrollMode scrollMode = getScrollModeWhenScrollingOnGridArea(up);
      if (scrollMode != null) { ... }                      // null unless shift or ctrl
  }
  ```

  The flag is set before anything asks whether a transfer will happen, and with no modifier held
  `scrollMode` is null and nothing else does. So plain wheel-scrolling — the ordinary way to read a
  grid — latches sorting off. The only reset in the class is `keyReleased`, which no mouse ever
  reaches, so it stays latched for the life of the screen.

  With the flag set, `ResourceRepositoryImpl.updateExisting` takes its `else if
  (removedFromBackingList)` branch, logging "no longer available" and leaving the row in the view
  list while the backing entry is gone. `AbstractGridResource.getAmount` reads the backing list
  live, so the orphaned row renders `0`. Any key release calls `sort()`, which rebuilds the view
  from the backing list — which is why the bug presents as being about SHIFT when it is about the
  wheel.

  We latch only when shift or ctrl/cmd is actually held. That is deliberately a superset of "a
  transfer will happen" rather than a copy of the scroll-mode table, which differs by call site and
  by direction; copying it would mean two more private methods to keep in step with Refined Storage
  for no behavioural gain. The condition also guarantees an exit — holding a modifier means
  releasing it, and that release reaches `keyReleased`. A bare wheel had no exit, which is the
  defect.

  **Why it was ours to fix even though the bug is not ours.** Durability-aware planning retires a
  `ResourceKey` on every craft: `tool@N` leaves the network and `tool@N+1` is held inside the task
  by `InternalTaskPatternMixin` until it finishes. Every other pack has the same latch and nothing
  routinely driving a resource to zero, which is exactly why this only ever showed up here. When
  Refined Storage ships its own fix, `AbstractGridScreenMixin` becomes a no-op and can be deleted.

  Two hypotheses were ruled out in code first and should not be re-derived: sticky resources
  (`ViewList.createSorted` re-adds them on every rebuild, so a sticky row would *survive* the
  SHIFT tap — this one does not), and Step Crafter's `MixinResourceRepositoryImpl`, which widens
  sticky in `createSorted` as well as `updateExisting` and so fails the same test.

  The check that settled it: run a durability craft **without touching the wheel**. No phantom.

## 0.2.85

- **Fuzzy mode across different tools.** The other half of issue #9, and the half actually reported
  in game: a fuzzy hammer slot demanded one specific hammer and declared the craft impossible when
  a different one was held.

  ```
  LP planner declined gold_dust: no integer solution --
    required with no pattern and none in storage: [alltheores:copper_ore_hammer]
  ```

  `CraftingGraph.buildClasses` unifies a slot's alternatives by signature — same slot, same class,
  interchangeable — which is right. `DurabilityClasses.merge` then regrouped by `sameTool`, and a
  copper hammer is not a wear level of an iron one, so it split that class back apart.
  `buildEffects` was left holding an assumption it states in a comment and could no longer rely on:

  ```java
  // Every alternative shares a class by construction when they are interchangeable
  final Integer cls = classOf.get(ingredient.inputs().getFirst());
  ```

  So only the first alternative was counted, its stock was zero, and the program was infeasible.

  Tool groups sharing a class are now merged rather than split, and a tool with only one wear level
  — dropped by `groupByTool` as "just an item", which is right in isolation — is pulled in when it
  shares a slot with a tool that *is* worn. If the slot accepts any of these tools then a use of any
  of them is a use, and their uses add up. Guarded to classes where **every** member is durable: a
  slot mixing a tool with an ordinary item would otherwise drag that item into a class measured in
  uses, where its stock would silently stop counting.

- **The executability replay was also wrong**, and it is worth separating from the fix above. Its
  wear substitution looked only at `ingredient.inputs().getFirst()`, so it refused a plan the real
  executor runs perfectly — the executor never sees the layout's first input, it walks the iteration
  inputs, which `calculateIterationInputs` builds from the *plan's* chosen possibilities. The
  non-durable branch immediately below it had always handled alternatives properly. A fixture
  shortcut that was safe while every slot had one input.

  **Both changes were checked for being load-bearing.** With the fixture corrected but the planner
  reverted, the scenario still fails with the original decline; only with both does it pass. Changing
  a fixture to make a test pass deserves that check, since it is indistinguishable from hiding the
  bug.

**#9 was two independent faults under one report** — 0.2.84 fixed the encoded-wear one, this fixes
the different-tools one. Neither would have been found without the other being fixed first.

## 0.2.84

- **Durability crafting did not work with fuzzy mode.** `PatternResolver.getFuzzyInput` replaces
  the encoded stack with the recipe's own ingredient items:

  ```java
  ItemStack[] items = recipe.getIngredients().get(i).getItems();
  return Arrays.stream(items).map(ItemResource::ofItemStack).toList();
  ```

  Those are plain instances, so a damageable tool comes back at **damage 0** however worn the
  encoded one was. The byproduct is not rewritten — it comes from `getRemainingItems()` on the
  encoded input and keeps the real damage. Encode with a crystal at damage 50 and the resolved
  pattern reads `@0 in, @51 out`.

  Everything downstream then draws the obvious and wrong conclusion. `DurabilityClasses.wearStep`
  measures precisely that gap, so one craft is costed at 51 uses instead of 1 and a fresh crystal
  becomes worth a single craft.

  The encoded resource now goes back into the list, at the front. Fuzzy still means what it meant —
  every alternative the recipe accepts is still there — and this only adds the one the player
  actually put in the grid, which non-fuzzy patterns have always used. Front matters twice:
  `CraftingGraph.buildEffects` takes `inputs().getFirst()` to pick the resource class, and
  `wearStep` takes the first input belonging to the tool group. Restricted to durable items, so
  alternative ordering is untouched for every other fuzzy pattern in the game.

- **The planner was not changed, deliberately.** Its arithmetic on `@0 → @51` was always right: a
  gap of fifty-one genuinely means fifty-one durability a craft, and recipes like that exist. The
  lie was introduced before the planner ever saw it. Clamping `wearStep` would have made such a
  recipe immortal in the ledger — 0.2.57's duplication bug returning — so a scenario now asserts
  that layout is still **refused**, specifically to stop that "fix" being attempted later.

**Two corrections to how this was investigated**, both worth recording:

- The first reproduction was **mis-specified**. It asserted `expectPlan = true` for a request
  needing 3,264 uses from a crystal holding 100, then treated the resulting failure as proof of the
  bug. The planner was behaving correctly; the test was wrong. What it actually demonstrated is now
  kept as the "refused, not clamped" guard.
- The replacement scenarios were first added to `scenarios()`, which runs with `Durability.NONE`,
  so nothing durability-related was being modelled at all — the suite caught it. They belong in
  `durabilityChecks`, which installs `FakeDurability`.

  `durabilityChecks` and `shortfallChecks` are **not counted** in the reported scenario total, so a
  check placed there that silently never runs is indistinguishable from one that passes. Both new
  checks were confirmed live by flipping an expectation and watching the suite fail.

## 0.2.83

- **The Max button ignored reusable tools**, reporting either `0` or something that looked like a
  container count. Neither was a miscount — the max path had never had durability applied to it.

  Refined Storage reaches its crafting calculator through **three** listeners, and this mod hooked
  two: `TaskPlanCraftingCalculatorListener` for starting a craft and
  `PreviewCraftingCalculatorListener` for the preview. The third,
  `IsCraftableCraftingCalculatorListener`, backs `getMaxAmount`, so the Max button was answered
  entirely by stock Refined Storage — which matches ingredients exactly and knows nothing about a
  tool being a supply of uses.

  That explains both symptoms precisely. Damage lives in an item's component patch, so `crystal@0`
  and `crystal@37` are different resources; a pattern encoded with a fresh crystal is not craftable
  *at all* against a worn one, and `binarySearchMaxAmount` fails its very first probe, leaving
  `low == high == 1` and returning `0`. Where the ingredient does match exactly it counts whole
  items rather than the uses left in them.

  The fix keeps Refined Storage's algorithm — double until it fails, then binary search the gap —
  and changes only the oracle. The planner's three answers are all used: a plan is yes, a *proved*
  infeasibility is no, and a decline is **not an answer** and falls through to stock RS untouched.
  That last distinction is the whole thing: collapsing it to a boolean would report zero for every
  ordinary recipe.

- **The search was lifted out of the mixin** into `planner/MaxCraftable`, so it can be run without
  Minecraft, and `MaxCraftableSelfTest` covers it with 17 cases — the boundaries around powers of
  two, nothing craftable, declining outright, declining partway, cancellation, and the probe
  ceiling. `./gradlew plannerCheck` now runs 42 scenarios.

  The tests were checked by reinstating the bug they guard: making the oracle treat "declined" as
  "not craftable" fails with *"a declining oracle must yield null, not a number"*, and restoring it
  passes. A test that has never failed is not yet evidence.

- A probe ceiling was added, which stock Refined Storage does not have. Its oracle is cheap enough
  to leave unbounded; ours is a linear program per probe, and an unbounded loop over a solver on
  RS's executor is how a button press becomes a stalled thread.

**Corrects the issue's own hypothesis:** #8 guessed the uncraftable cache in
`AutocraftingNetworkComponentImplMixin` might be the source of the spurious zero. It is not —
`getMaxAmount` builds its own calculator and never goes through `ensureTask`.

## 0.2.82

- **The gametest could never have found its template.** `RSTweaksGameTests` asks for
  `template = "rstweaks:empty"`, but the file shipped under `data/rsperf/` — the namespace this
  mod stopped using at 0.2.41. The lookup could not have resolved, so the one gametest in the
  project was broken from the rename onwards. Nobody noticed because gametests only register
  with `-Dneoforge.enabledGameTestNamespaces=rstweaks`, and nothing has been run that way.

  It also shipped twice: `structure/` and `structures/`, byte-identical. 1.21 singularised the
  data pack directories, so `structure/` is correct and `structures/` was a 1.20 leftover kept
  alongside rather than replaced. Checked against the pack rather than assumed — of the 1.21.1
  mods installed here, 111 entries use `structure/` and 5 use `structures/`.

  Now one file, at `data/rstweaks/structure/empty.nbt`, verified present in the built jar.

  Found while reviewing the source before sharing it, which is a decent argument for reading
  your own resources folder occasionally. Closes #6, and unblocks #2 — there is no point
  writing gametests on a harness whose template does not load.

## 0.2.81

- **Fluid substitution removed.** 26 files and roughly 3,600 lines: the pattern type and its
  mark, the Pattern Grid tab, its layout and renderer, the per-tab matrices, the reversible
  mirror registration, the resolver hook, the tooltip, and the three config entries
  (`fluidSubstitutionPatterns`, `reversibleFluidSwapPatterns`, `convertUnmarkedFluidPatterns`).
  Those three can be deleted from an existing `rstweaks-common.toml`.

  Ultramegaaa's *Refined Fluid Substitution* does this job now, is maintained, and runs on both
  loaders. Keeping a second unmaintained implementation of the same feature in the same pack was
  a conflict waiting to happen.

- **What deliberately survives**, and why it was worth the effort to make it survivable:

  - The **crafting-grid container refill** is untouched. It shared exactly one helper with fluid
    substitution — asking an item what fluid is in it — and 0.2.80 split that into
    `storage/FluidContainers` precisely so this deletion would not take it too. Without that step
    first, removing fluid substitution would have silently killed a working, confirmed feature.
  - The **LP planner's cycle handling** stays. It was motivated by reversible swaps, but a
    degenerate cycle whose net production is zero is the general case, and container recycling —
    one bucket round a loop 64 times is one bucket, not 64 — needs it regardless of which mod
    creates the loop.
  - The **planner's swap scenarios** stay as coverage, renamed in the comments away from our own
    removed mixin. The shape they test is what *any* fluid substitution produces, so it is still
    the case the planner exists for.

- The client mixin config is gone entirely — every mixin in it existed for the tab — and with it
  its `[[mixins]]` entry in `neoforge.mods.toml`. **rstweaks registers no content again**: the
  `rstweaks:fluid_substitution` data component was its only registered object, so the mod is back
  to being purely mixins, which is what it was before 0.2.65.

78 Java files down to 53; 15 gameplay mixins remain, all of them performance or autocrafting
correctness.

## 0.2.80

- **The crafting-grid container refill no longer needs patterns.** It required the network to
  hold a pattern producing the filled container, as a stand-in for "you meant this fluid to be
  convertible". That proxy only ever passed because our own fluid substitution registered such
  a pattern, so parking that feature in 0.2.79 killed this one silently — it would have been
  found next time someone crafted a cake. Both that check and the `fluidSubstitutionPatterns`
  gate are gone.

  What limits it now is whether the network can pay. No full container in storage and not
  enough fluid means nothing happens and the empty container is handed back, which is Refined
  Storage's own behaviour. That is a better guard than the pattern was: it asks whether the
  network *can*, rather than whether the player once encoded something unrelated. The test on
  the items is unchanged and remains the strict part — one container, one remainder, and the
  remainder must be precisely that container emptied.

  **Behaviour change:** the option is default-on and had been dead since 0.2.79, so it goes
  live for everyone here. Neither payment route can create or destroy — both are straight
  trades — but the network now spends fluid where it previously did nothing.

- **Container inspection extracted to `storage/FluidContainers`.** The refill and fluid
  substitution shared `FluidSwap.contents()` only because both need to ask an item what fluid
  is in it, and that shared helper was enough to make one feature die with the other. The
  refill now depends on no fluid substitution code and will survive its deletion.

- **Removed the last `@Unique` inline field initializer**, in
  `AutocraftingNetworkComponentImplMixin`. That shape silently failed to apply in
  `AbstractTaskPatternMixin` and destroyed items for seven versions (see 0.2.64). This one has
  always worked — which is exactly what that one looked like.

Confirmed in game: correct items and fluid consumed per craft, and empty containers correctly
handed back once the network ran out.

## 0.2.79

- **Fluid substitution is parked.** Ultramegaaa released *Refined Fluid Substitution* on
  CurseForge covering the same ground, maintained, on both Fabric and NeoForge. Ours stays in
  and still works — the default was already off since 0.2.52 — but it is no longer developed
  and will probably be removed. The config entry says so and says not to run both.

  No code change beyond that text. Everything else stands on its own: the LP planner,
  durability-aware planning, the performance work and the item-loss fixes.

## 0.2.78

Contributed by a collaborator, reviewed and merged here. Found by running the source through
a second reader, which caught two coupling bugs that had survived our own review.

- **Processing and Fluid Substitution now have independent matrices on *both* sides.** The
  client had only one, so a fluid update overwrote the single client container and switching
  back waited a server tick to restore it — the one-tick flash of the other tab's contents.
- **Tab switches rebind immediately on the client** rather than waiting for the server.
- **Loading a stored Fluid Substitution pattern no longer overwrites Processing.** RS sees the
  stack's base type as `PROCESSING` and calls `copyProcessingPattern`, which clears and writes
  the Processing matrix. A marked stack is now routed to the fluid matrix instead. This was
  destructive: it wiped whatever was on the Processing tab.
- **Tab state moved onto Refined Storage's own synced property channel**, and
  `AbstractContainerMenuMixin` is deleted. That mixin injected into *vanilla*
  `AbstractContainerMenu` — every container menu in the game — to catch two magic button ids
  for one grid. The property channel is less invasive and cannot collide with another mod's
  button ids.

## 0.2.77

- **The Pattern Grid remembers which tab it was on.** Synced to the client as a Refined Storage
  menu property rather than guessed from the matrix contents. The guess could not work: the
  matrix the client receives at open is always RS's Processing one whatever tab is live, and an
  empty fluid tab has nothing to recognise even when the right one is sent.
- **The screen no longer announces "Processing" at open.** That unprompted announcement was
  actively destroying the memory — the server had bound the fluid matrix, the client decided
  from the contents that this was Processing, and told it so, overwriting the flag the block
  entity had just loaded. The server is now told only when the player clicks a tab.

## 0.2.76

- Removed the 0.2.74 diagnostic logging, after confirming in game that the tab keeps its own
  matrix, auto-fill populates, and a pattern encoded that way crafts. The single
  `encoded a fluid substitution pattern` line stays: its absence is what identified the Cable
  Tiers conflict in 0.2.69.

## 0.2.75

- **Clear cleared the wrong matrix on the fluid tab**, wiping the Processing tab instead and
  appearing to do nothing. That left no way out of a stuck fluid tab: auto-fill refuses to
  overwrite a matrix holding something that is not a valid swap, and only 3 of the 162 slots
  are visible in that layout, so anything left in the other 159 blocked it invisibly.

## 0.2.74

- **Auto-fill did nothing on the fluid tab.** Input slots are located by testing their container
  against a marker interface that a mixin applies to Refined Storage's matrix input container.
  0.2.72 replaced the fluid tab's container with a plain `ResourceContainerImpl`, which does not
  carry the marker, so the input list came back empty. The fluid input container is now a
  subclass that implements it — subclassing being the only route, since we cannot mixin onto our
  own class.

## 0.2.73

- **Auto-fill wrote to the wrong container.** It still wrote to RS's `processingOutput` while the
  slots had been rebound to the fluid one, so the computed opposite side landed in the tab the
  player was not looking at. It now asks the slot what it is bound to.
- **Output slots were found by comparing container identity**, which stops being true after the
  first rebind — so switching back to Processing would have found no output slots and silently
  refused to rebind. That list is captured once, before anything moves.

## 0.2.72

- **0.2.71 would not launch.** It shipped a class inside Refined Storage's own package to reach a
  package-private container factory. NeoForge loads mods as named modules on the module path, so
  two modules exporting one package is a hard error at module resolution:
  `Modules refinedstorage and rstweaks export package ...`. It compiles perfectly, which is what
  makes it inviting. Reflection is no better — the package is not opened, so `setAccessible`
  throws.
- The fluid tab's containers are now plain `ResourceContainerImpl` from public API. They lack the
  fuzzy allowed-alternatives of RS's matrix container, which a fluid swap has no use for: a swap
  is one exact container against one exact fluid, and an input offering alternatives is rejected
  outright. Encoding still runs through RS's own container, borrowed for the length of one call.

## 0.2.71

**Did not launch — superseded by 0.2.72.** The fluid tab gained its own matrix: two real
containers on the block entity instead of copying one tab's pattern in and out of the other's as
NBT, with tab switches rebinding that menu's slots rather than serialising. That also fixed the
multiplayer gap, since slots belong to a menu and containers to the block entity, so two players
can hold one grid on different tabs — which the stash could never express.

## 0.2.70

- Removed the 0.2.68 diagnostics after confirming the mark is written and read.

## 0.2.69

- **The 0.2.65 mark had never once been written.** Cable Tiers injects into the same
  `createProcessingPattern` at `RETURN` and finishes with `cir.setReturnValue(...)`, which
  *cancels the method* — so every callback appended after it is skipped. At equal priority ours
  was second and simply never ran. No error, no warning, no failed mixin audit: the injector
  applies perfectly and is never reached.

  Fixed with `priority = 500`, so ours precedes Cable Tiers' default 1000. Running first is also
  correct on the merits — we mark the stack and return normally, Cable Tiers then adds its
  component to the same stack, and both survive.

  **The general lesson: a mixin that applies cleanly is not a mixin that runs.** `require = 1`
  proves the injection point was found, nothing more.

## 0.2.68

- Diagnostic build, no behaviour change. Three builds of reading had produced two confidently
  wrong answers, so this logged the facts that decide it. The gap it revealed — `createPattern`
  firing with `fluidTab=true` while `createProcessingPattern` logged zero times — is what
  identified the Cable Tiers conflict in one launch.

## 0.2.67

- **The tab was read from the wrong place.** `PatternGridBlockEntity`'s `rstweaks$tabOpen` is
  *stash bookkeeping* — which matrix is loaded into the shared containers, written only when a
  swap actually happens — not a record of which tab is on screen. The menu's `rstweaks$fluidTab`
  is the authority, set on every announcement from the client whether a swap follows or not. Two
  facts that agree most of the time and differ exactly when it matters.

## 0.2.66

- **The mark is now required.** `convertUnmarkedFluidPatterns` defaults off, so only patterns
  encoded on the fluid tab are treated as substitutions and the machine-recipe ambiguity is
  closed rather than merely closeable. A pattern encoded before 0.2.65 reverts to ordinary
  Processing and waits for a machine — nothing destroyed, re-encode to fix. Back-compat
  explicitly declined.
- The tooltip follows the same rule, so it can no longer draw a container and a fluid on a
  pattern that is named and resolved as Processing.

## 0.2.65

- **Fluid substitution patterns now say what they are.** A pattern encoded on the fluid tab
  carries a new `rstweaks:fluid_substitution` data component, and the resolver reads it instead
  of guessing from the contents. Guessing could not tell a substitution from a real machine
  recipe that takes a full container and returns the empty one plus its fluid — at the level of
  ingredients and outputs those *are* the same thing — and such a recipe was being settled in the
  ledger with the machine never running.

  The mark **authorises**, it does not describe: the contents are still parsed, because the
  layout needs the container, fluid and amount, and both must agree. It deliberately carries no
  data — storing the swap would go stale if a pack changed a container's capacity.

  This is the mod's first registered content; everything else is mixins.

## 0.2.64

- **BUG FIX — autocrafting tasks were destroying their materials.** Present in every build from
  0.2.57. The `@Unique` field holding the tools taken this iteration was declared with an inline
  initializer that never reached the instance, so the durability handler threw
  `NullPointerException` on its first line — above the config check, so
  `durabilityAwarePlanning=false` could not avoid it.

  Refined Storage treats *any* exception from a task step as completion: `TaskContainer.step`
  logs, sets `completed = true`, fires `taskCompleted` — the toast — and drops the task **with
  its internal storage still in it**. So the craft ate its ingredients, announced success, and
  produced nothing.

  Every `@Unique` field in those mixins is now null-safe, and all three task-step handlers catch
  and fall back to stock RS behaviour, so a future bug there costs the optimization instead of
  the player's items. Found by reading `latest.log` for `removing task` — one grep that would
  have answered it in a minute.

## 0.2.63

- `skipEmptyCompositeExtract` defaults **off**. The 0.2.62 reasoning was wrong on one point: an
  external storage adds nothing to the network's resource list on insert, and the list gains the
  item only via `detectChanges()`, which diffs a fresh inventory snapshot — so the list can be
  *wrong*, not merely late. That desync was cosmetic until 0.2.62 made extraction trust the list,
  at which point items still sitting in a drawer become invisible **and** unreachable.

## 0.2.62

- **Empty-extract skip.** `CompositeStorage.extract` returned 0 without walking a single storage
  when the network's own resource list says it holds none. Aimed at the ~12% of a struggling
  server thread spent asking every storage for things that are not there. Adds no cache — it
  reads the list RS already keeps. Config: `skipEmptyCompositeExtract`.

## 0.2.61

- Removed the 0.2.60 crafting-grid diagnostic logging. No behaviour change.

## 0.2.60

- Temporary logging around the crafting-grid refill. The reported problem turned out not to be a
  bug.

## 0.2.59

- **Stock before tank.** The crafting-grid refill now spends a full container from storage before
  spending fluid: extract the filled, insert the emptied, leave the slot alone. Both halves are
  simulated before either runs, because taking the full container without being able to hand back
  the empty would destroy it. Confirmed in game across all four cases — both available, fluid
  only, buckets only, neither.

## 0.2.58

- **The crafting grid refills containers.** Craft a cake and the three milk buckets stay milk
  buckets; the network pays 3000mB of milk. Refined Storage already pulls a replacement from the
  network for ordinary ingredients in `useIngredient`; it simply never had a path for the ones
  handed back as containers. Config: `refillContainersInCraftingGrid`.

## 0.2.57

- **BUG FIX — durability duplication.** A recipe burning two different durable tools recorded
  only one, so the other's byproduct came back exactly as encoded: a repaired tool, out of
  nothing. Now tracked per tool.

  Note the shape of the miss: the planner's self-test already modelled this correctly, keying
  wear per encoded ingredient. **The test harness was more correct than the code**, so no
  scenario could ever have caught it.

## 0.2.56

- **BUG FIX — item loss in the external slot index**, present in every build since 0.2.3. When an
  extraction pulled from one candidate slot and then met a stale one, the items already taken
  were never reported to Refined Storage — removed from the chest and destroyed. One missing
  line. Under `SIMULATE` the same path under-reported availability instead, which is why it never
  looked like a crash.

## 0.2.55

- Removed the dead two-way arrow code and wrote down why it fails: a mirrored blit via
  `scale(-1,1,1)` renders nothing, near-certainly culled winding.

## 0.2.54

- Attempted a two-way arrow on the fluid pattern tooltip. Did not render.

## 0.2.53

- Fluid pattern tooltip shows the container, an arrow and the fluid, instead of RS's matrix row
  of mostly-blank cells.

## 0.2.52

- Defaults changed: `lpPlanner` and `durabilityAwarePlanning` now default **on**;
  `fluidSubstitutionPatterns` stays off pending more testing. Config text for both rewritten —
  the old warnings described bugs that had since been fixed.

## 0.2.51

- Refined Storage's Processing tab no longer stays lit while the fluid substitution tab is
  selected.

## 0.2.50

- **The two tabs keep separate patterns.** The inactive tab's matrix is stashed as NBT on the
  Pattern Grid block entity and swapped in on a tab change, so it survives closing the grid and a
  world reload. No new containers and nothing new to sync: `ResourceSlot.broadcastChanges` diffs
  each slot against what the client was last told, and `ResourceContainer.toTag`/`fromTag` is RS's
  own persistence, so the round trip carries the fuzzy allowed-alternatives too.

  Superseded by the real containers in 0.2.71/0.2.72.

## 0.2.49

- Diagnostics for the tab signal, which proved the channel was working.

## 0.2.48

- Pattern item renamed **Fluid Substitution Pattern** (was Fluid Crafting Pattern).
- **Auto-fill no longer fires in Refined Storage's own Processing tab.** Starting a machine recipe
  with a bucket used to have the outputs written for you and the pattern named a substitution. The
  client now tells the server which tab is open over a vanilla menu-button click.

## 0.2.47

- **Shortfall reporting.** An impossible craft now says what you are short of — "lava_bucket
  available 4, missing 6" instead of "missing 10000mB lava" — by re-solving with outside supply
  allowed and reading the difference.

  Eligibility is the whole trick: outside supply is offered only to leaves *and* to classes inside
  the target's own SCC, never to the target itself. Allow everything and the solver conjures the
  shallowest item; allow only leaves and a swap cycle reports nothing missing, because the
  container *is* produced — by the mirror pattern.

## 0.2.18 – 0.2.46 — fluid substitution, not individually recorded

**This changelog lapsed across this range and the per-version detail is unrecoverable.** It is
recorded here as one block rather than reconstructed, because inventing plausible entries would be
worse than admitting the gap. Losing this stretch is what prompted `VERSIONS.txt`.

What was built, in rough order:

- **Fluid substitution patterns** — a pattern that only empties or fills a container is settled by
  Refined Storage itself instead of waiting for a machine that does not exist. One word changes it:
  `PatternLayout.external` becomes `internal`.
- **The Pattern Grid tab and its 1↔2 layout**, drawn over the processing matrix.
- **Auto-fill** — put a bucket or a fluid in, get the other side filled in for you. Triggered by
  the input changing rather than by the tab being clicked, so it works however the resource
  arrived, including dragged from EMI, JEI or REI.
- **Reversible patterns** — one encoded pattern registers its mirror, so emptying and filling both
  work from one. This deliberately creates a cycle in the crafting graph, which is why it requires
  the LP planner.
- **Empty containers no longer advertised as craftable** — moved to byproducts, because RS indexes
  patterns by output to decide what is craftable and the network cannot make you a bucket.
- 0.2.30–0.2.31: auto-fill direction fixes.
- 0.2.40: last build under the name `rsperf`.
- 0.2.41: renamed to `rstweaks` — mod id, package, log prefix and mixin prefix.
- 0.2.45: swallowed RS's `PatternCycleDetectedException` to let it build a preview. **Reverted in
  0.2.46** — `PreviewBuilder` has not accumulated the counts when RS throws, so the result was an
  empty preview with Start enabled on an impossible craft.
- 0.2.46: that revert.

## 0.2.17

- **Step Crafter, Cable Tiers and Functional Storage are now optional.** rstweaks used to
  refuse to load without all three, because a mixin config is all-or-nothing: if any
  mixin in it cannot find its target, the whole config fails and every optimization in it
  is lost. One config meant one missing mod took everything down.

  Split into four, each gated on its mod via FML's `requiredMods` on the `[[mixins]]`
  entry — `ModFileParser` reads it and never hands the config to Mixin when the mod is
  absent. A pack without Cable Tiers now loads rstweaks and simply does without that one
  optimization.

  Each config keeps `required = true`. A mod that is *present but reshaped* should still
  fail loudly rather than quietly disabling an optimization you believe is running — and
  the version ranges stay pinned to the current minor line for the same reason. Widen
  them only after checking the injection targets against the new jar's bytecode.

- **The startup and chat lines now list what is actually running**, not everything the mod
  can do. With three optimizations behind optional mods, the old hardcoded list would
  have claimed work that was not happening — and those two messages are the only evidence
  most people ever look at. Computed on first use rather than during construction, since
  `ModList` is not dependable while mods are still being built.

## 0.2.16

- **Tools that wear out** (`durabilityAwarePlanning`, **default off**, needs in-game
  testing). A Mystical Agriculture infusion crystal is consumed by its recipe and handed
  back one point more damaged. Refined Storage records that as a byproduct, but
  `crystal@0` and `crystal@1` are different resources and a pattern stores the exact one
  it was encoded with — so the first craft works and the second has nothing to use.

  Modelled after Applied Energistics, whose `IPatternDetails.IInput.getRemainingKey`
  derives what a recipe hands back from the item *actually consumed* rather than from the
  encoded template. RS keeps no recipe on the pattern, so the wear step is derived from
  the tool the executor recorded taking — equivalent for damage-per-craft recipes, not
  for one with a bespoke remainder.

  Three parts:
  - **Planner** — every wear level of a tool collapses into one class measured in
    **uses**, not items. One crystal with 30 hits left is 30 crafts. That is one extra
    resource class and no extra patterns: 0.57 ms, against 63 ms for the
    one-pattern-per-damage-step alternative and 304 ms at 200 steps.
  - **Storage scan** — the graph is built by walking patterns, which only ever name
    `crystal@0` and `crystal@1`, so a crystal at damage 95 in your network was invisible
    and the planner reported you had none. Storage is now scanned for wear variants, but
    only when the graph contains a durable item at all.
  - **Executor** — `AbstractTaskPatternMixin` substitutes whichever wear level is really
    in the task's internal storage, most worn first; `InternalTaskPatternMixin` ages the
    byproduct to match.

- **The ageing half is not optional, and there is now a test that says so.** Without it
  the pattern returns the crystal it was encoded with on every iteration — 64 crafts, one
  crystal, ending at damage 1, never breaking. That version runs to completion and passes
  every other assertion in the suite. A durability-conservation check catches it, and a
  scenario using a deliberately non-wearing tool asserts the check still rejects:

  ```
  durability was not conserved: started with 100 uses, crafted 0, spent 64,
  ended with 100 (expected 36)
  ```

- Four durability scenarios, each asserting the *quality* of the plan rather than only
  that it runs — one crystal requisitioned for 64 crafts, the worn crystal preferred over
  a fresh one, at most two replacements for 25 crafts on 10-use crystals. The third
  passed the moment it was written, by planning 24 crystals; the assertion is what turned
  it into a real test. 17 scenarios total, verified headlessly.

- `ItemDurability` caches maximum damage per item. The lookup sits inside `extractAll` —
  once per ingredient, per iteration, per task — and answering it means building an
  `ItemStack`, which is not something to allocate on that path.

## 0.2.15

- **The chat summary was suppressing itself.** Whether to report at all was decided by
  the four counters this feature launched with — step requester scans, backed-off
  failures, sided-input lookups, uncraftable rechecks — while the body went on to report
  nine. A session that exercised only the newer optimizations printed *nothing*, which is
  indistinguishable from a mod that failed to load.

  Found while checking whether 0.2.14's wrong-type probe skip was firing: no counter line
  appeared six minutes after joining, despite the LP planner running a dozen times. The
  report is now assembled first and suppressed only if it came out empty.

- **Deltas are now honest.** The first four counters were reported as per-interval deltas
  and the other five as running session totals, in one sentence ending "since last
  report". All ten are deltas now, snapshotted through a single `Counts` record so the
  two can't drift apart again.

- **`/rstweaks stats`** prints the session totals on demand. The periodic summary fires
  every five minutes and stays quiet when nothing changed, which makes "is this actually
  running right now?" an awkward question to answer mid-test.

## 0.2.14

- **External storage no longer probes providers that cannot hold the resource.** One
  External Storage block exposes several providers — items, fluids, and whatever energy
  or source types other mods contribute — and `CompositeExternalStorageProvider` walks
  all of them on every extract and insert without ever looking at the resource type.

  On an instance where Refined Types' Network Energizer pulls power every tick:

  ```
  NetworkEnergizerNetworkNode.extract                  12.03 ms/tick
    CompositeExternalStorageProvider.extract
      ItemHandlerExternalStorageProvider.extract        3.55%   <- asked for energy
      FluidHandlerExternalStorageProvider.extract       0.33%   <- asked for energy
  ```

  Roughly 5.7 ms/tick spent telling an energy request that an item inventory has no
  energy. Each answer is instant; there are just an enormous number of them.

  **Deliberately not a cache.** The tempting version is to remember that a provider
  returned zero and stop asking, but that conflates "wrong type" with "empty right now",
  and a drawer refilled by a pipe would silently stop being seen — the same staleness
  trap the slot index was designed around. Instead the skip rests on a compile-time fact:
  an item handler holds items and never anything else. Nothing to invalidate, no way to
  be wrong. Providers from mods we do not recognise are still asked every time.

  Config: `skipMismatchedStorageTypes` (true). Counter reported in chat as "wrong-type
  storage probes avoided".

## 0.2.13

- **The preview no longer doubles a self-duplicating craft.** Requesting 192 netherite
  templates announced "to craft: 384", because the pattern fires 192 times and outputs 2
  each — true about the machine, false about the outcome, since 192 of them are eaten as
  ingredients. The row for the requested resource now reports the requested amount.

  Only that row. Every other row keeps its gross count, because for an intermediate
  "how many get made" is the useful number, and nothing but the target is consumed by
  its own production.

  Guarded by a new assertion (requested row must equal the request), verified by
  reinstating the old behaviour and watching both template scenarios fail with
  "says it will craft 200 template but 100 were requested".

## 0.2.12

- **Self-duplicating recipes now work.** Netherite templates are 1 template + 7 diamonds
  + 1 netherite ingot → 2 templates, so the recycled resource is the requested item
  itself and it is an *output* of the root pattern. 0.2.9 held back the root pattern's
  byproducts; outputs still went straight to the network, so iteration two had no
  template. Reported from the game as "gave it 3, asked for 3, it made 1 and hung on the
  rest" — and the log agreed:

  ```
  LP plan for 3x netherite_upgrade_smithing_template: 1 pattern [3x ROOT],
    initial requirements [template x1, diamond x21, netherrack x3]
  ```

  The plan was right. One iteration ran, both templates left for the network, and the
  other two had nothing to consume.

  Outputs are now held in the task **only when the same pattern consumes them**.
  Byproducts are held unconditionally — they are never the requested item, so waiting
  costs nothing. Outputs are what you asked for, and a long craft filling the network as
  it goes is worth keeping, so only the self-duplicating case gives it up.

  Not covered: a cycle running through two *different* patterns via an output, since a
  pattern cannot see the rest of its task. The planner's replay models the same rule, so
  those are declined rather than emitted and left to hang.

- **`recycleRootByproducts` is now `keepRecycledResourcesInTask`**, because it no longer
  only governs byproducts. Your old key is ignored and the new one is written with its
  default (true).

- **The self-test replay was wrong the same way the code was, again.** It credited the
  root pattern's outputs back to the task pool, so it confirmed the template plan as
  runnable. It now tracks what leaves for the network separately, and judges success by
  what the network actually gained rather than by what the pool holds.

  Three template scenarios added (one to seed, three to seed, nothing to seed → must
  decline), and the "prove the redirect is load-bearing" guard now runs for both
  redirects: a container plan must deadlock without byproduct retention, and a template
  plan must deadlock without output retention. 12 scenarios, verified headlessly.

## 0.2.11

- **The request screen no longer claims to craft recycled containers.** Crafting 128 rice
  slimeballs with a single bucket announced "to craft: 128 buckets" — arithmetically the
  number of times a bucket is handed back, and completely wrong about what will happen.
  One bucket goes round 128 times; it is one bucket.

  `PlanPreview` counted byproducts as crafted. It no longer does. The bucket still appears
  in the preview under **available: 1**, from the plan's initial requirements, which is
  the true statement and the only number that changes if you have more or fewer. This also
  matches what the column means everywhere else in Refined Storage: "to craft" counts
  crafting steps, and no step produces these.

  I had made that call on purpose and written a comment defending it. Reported from
  in-game, where it plainly reads as a lie.

- **New invariant in the self-test: the preview may not claim to craft something no
  pattern outputs.** Verified by reinstating the old behaviour and confirming all five
  byproduct scenarios fail — buckets, slag, crucibles — rather than trusting a green run.

## 0.2.10

- **Fixes 0.2.9, which could not craft at all.** `@Shadow protected boolean root` failed
  to apply: Mixin does not search the class hierarchy for shadowed *fields*, and `root`
  is declared on `AbstractTaskPattern`, not `InternalTaskPattern`.

  ```
  InvalidMixinException: @Shadow field root was not located in the target class
      com.refinedmods.refinedstorage.api.autocrafting.task.InternalTaskPattern
  ```

  The failure surfaced as "Start turns to pending and nothing happens" rather than a
  startup crash because `InternalTaskPattern` is not loaded until the first craft, so the
  mixin was not applied — and therefore could not fail — until then. **A clean startup
  log does not verify this mixin. Only starting a craft does.**

- The check is gone rather than fixed. It was never needed: for a non-root pattern
  `returnOutput` already does `internalStorage.add`, so doing it in the redirect is the
  same operation by a shorter path, and dropping the check drops the shadow with it.

- Verified the injection point against the compiled class this time rather than the
  decompiled source — `step` calls `PatternLayout.byproducts()` exactly once
  (`invokevirtual`, offset 90), and its four parameters are all public Refined Storage
  API, which is what the redirect handler must capture to reach the internal storage.

## 0.2.9

- **Found why Refined Storage really demands 64 buckets for 64 rice slimeballs. It is
  not the planner — it is the executor**, and no planner can work around it.

  `InternalTaskPattern.returnOutput` routes on one condition:

  ```java
  if (this.root) rootStorage.insert(output);  else internalStorage.add(output);
  ```

  and `step()` sends outputs **and byproducts** through it. When the pattern producing
  the requested item is also the one handing the bucket back — which is what container
  recycling *is* — the bucket goes to the network the instant it is made. A task stops
  drawing from the network once it reaches RUNNING, so iteration two has no bucket and
  never will.

  This is precisely the reported symptom: one slimeball worked, more than one failed.
  The 0.2.8 plans were correct (`bucket x1` for 18 slimeballs); they could not run.

- **`recycleRootByproducts`** (new, default true) keeps the root pattern's byproducts in
  the task's internal storage. Only byproducts — the item you asked for still reaches the
  network as each one is crafted. Byproducts arrive on completion instead of during the
  craft, since the task returns its whole internal storage when it finishes *and* when it
  is cancelled, so nothing is lost, only delayed.

  `lpPlanner` now refuses to run when this is off, because every plan it emits assumes
  the byproduct comes back.

- **The self-test replay was wrong in exactly the same way the planner was.** It credited
  root byproducts back to the pool, so it confirmed plans that could not run. It now
  models the real routing, and two guards were added:
  - with recycling off, the planner must decline;
  - a plan built *for* recycling must **deadlock** when replayed without it. If that ever
    passes, either the redirect does nothing or the replay has stopped modelling
    Refined Storage — and the second is what let this reach the game.

  8 scenarios, verified headlessly.

## 0.2.8

- **Fixed the bug that made LP-planned crafts sit forever.** Rice slimeballs consume a
  water bucket and hand the bucket back, so the bucket's *net* consumption across the
  plan is zero — and net consumption was exactly what the plan asked the network for.
  Refined Storage steps patterns against the task's internal storage, filled once from
  `initialRequirements` and never topped up, so a plan asking for no buckets had no
  bucket to fill, could not run its first iteration, and never produced the bucket that
  would have let it. The task sat in `EXTRACTING_INITIAL_RESOURCES` looking exactly like
  a crash. Observed in-game on a 20 TPS server, so it was never a performance symptom.

  A cycle needs **working capital**: one container in flight, returned and reused. Net
  consumption of zero and required capital of zero are different claims and only the
  first was true. `PlanMatrix.workingCapital` now replays the plan against the
  requirements themselves and grows them until it runs, which finds the minimum — one
  bucket for rice slimeballs, where stock Refined Storage demands 64 and the equations
  alone suggested none.

  The simulator could not have caught this: it was replaying against everything the
  network holds, which included the bucket the emitted plan then failed to request.

- Two further faults found by the new tests, both in that repair loop:
  - Growth **doubled past what the network stocks and then gave up**. Cakes need three
    buckets in flight and there were exactly three; doubling asked for four and threw
    away a plan that worked. Now clamped to stock, and only abandoned when asking for
    everything there is still will not run.
  - It seeded **whatever was cheapest to unblock, including crafted intermediates the
    network holds none of**. Asking to extract more of something that does not exist
    gets nowhere. `PlanSimulator` now reports every shortfall, not just the cheapest,
    and the loop picks the cheapest one that can actually be supplied. This is what a
    graph with two recycled containers needs.

- **New `/rstweaks selftest` suite: LP plan executability.** Six scenarios covering a
  recycled container with one in stock and with plenty, a container recycled through two
  steps, two independent containers, a byproduct with no cycle, and a cycle with no
  container to bootstrap (which must decline). Each emitted plan is replayed against the
  real pattern layouts starting from nothing but its own requirements, so a plan that
  cannot run fails here instead of in someone's base.

  The replay deliberately does not reuse the planner's own simulator or its
  resource-class abstraction — a mistake in how resources are grouped shows up rather
  than being confirmed by its own author.

- **The planner can now be tested without Minecraft.** `HeadlessPlannerCheck` runs the
  suite in a plain JVM against the Refined Storage API, so a fix is verified between
  builds instead of only after a restart and a manual craft. Shipping a plan that could
  not execute is precisely what happens without that. `Config.intOrDefault` supplies
  declared defaults when no config file has been loaded.

  Run it with: `.\gradlew.bat -I cp.gradle printCp -q` to refresh `cp.txt`, then
  `java -cp "$(cat cp.txt)" com.wraithhawit.rstweaks.test.HeadlessPlannerCheck`.

`lpPlanner` stays **off by default**. Netherite templates and Occultism bound books have
not been retested, and the planner has now been wrong twice in ways that only showed up
in a real pack.

## 0.2.7

- **An accepted LP plan now logs the plan, not just its size.** It used to print
  `N patterns`, which cannot answer the only question worth asking when a craft then
  stalls: what did it decide to do. It now lists iterations per pattern, marks the root,
  and prints the initial requirements — a plan that demands an initial resource nothing
  can supply leaves the task in `EXTRACTING_INITIAL_RESOURCES` forever, which in-game is
  indistinguishable from a freeze. Once per accepted craft, so not a hot path.

## 0.2.6

- **Refined Storage's autocrafting DEBUG logging was 71.8% of the server thread**
  (`AutocraftingLogSpam`). `InternalTaskPattern`, `TaskImpl` and `AbstractTaskPattern`
  log once per pattern per iteration — "Stepping", "Stepped", "Inserting", "Extracted" —
  each formatting a full `ItemResource` including its `DataComponentPatch`. NeoForge
  writes `debug.log` at DEBUG by default, so this is on for every player, and it is done
  synchronously on the tick loop.

  Measured on a creative test world running compression crafts: **412,256 lines and
  209 MB of `debug.log` in 29 seconds** — 7 MB/s of blocking disk writes. Message
  building plus `RandomAccessFile.writeBytes0` came to 85.9s of a 120s profile. Server
  was at **2.3 TPS / 424 ms per tick**; everything every mod actually *did* was the
  other 28%.

  Fixed by raising the level of `com.refinedmods.refinedstorage.api.autocrafting.task`
  to INFO. Raising the level rather than filtering is the point: log4j checks it in
  `logIfEnabled` *before* building the message, so nothing is allocated, formatted or
  written. A filter would run too late and still pay for the message. Warnings and
  errors are unaffected, and no other logger is touched.

  Config: `silenceAutocraftingDebugLog` (true). Set false when debugging a crafting bug
  and you want the step-by-step trace back.

  This one is worth reporting upstream on its own — it is a stock Refined Storage cost
  with no mod interaction involved.

## 0.2.5

- **The config file is readable now** (`ConfigFormatter`). NightConfig writes every
  comment line as `"#" + line`, so a blank line in a `.comment(...)` block comes out as
  a bare `#`, and with comments as long as ours the file was an unbroken wall of `#`
  with one option running straight into the next. The writer has no setting for this —
  the only real blank lines it emits are before `[section]` headers.

  So a blank line is inserted between entries after the file is written. It runs on
  config load, rewrites only if the result would differ (so it is a no-op from the
  second launch on and does not fight the file watcher), and changes nothing but line
  breaks — TOML ignores blank lines, and no key, value or comment text is touched.

  The bare `#` lines *inside* a comment block stay as they are: they're what keeps a
  paragraph attached to the key it documents, and if they were blank too, a break
  within a comment would look identical to a break between options.

## 0.2.4

- **`lpPlanner` now defaults to OFF.** It was shipped enabled while still unverified
  against real recipe graphs, and it broke crafting on a live server: netherite
  smithing templates and Occultism bound books both failed.

  The failure makes sense in hindsight. The planner engages only on byproduct or
  cyclic subgraphs, and template duplication (1 template + materials → 2 templates)
  is the canonical cycle — the exact case Nodrance warned about ("you might use up
  your only smithing template before duping it"). Verified against synthetic
  patterns is not the same as verified against a real pack.

  Existing config files keep whatever value they already have; set
  `lpPlanner = false` by hand to recover. None of the other optimizations depend on
  it, and all of them stay on.

## 0.2.3

- **Slot index for external inventory extraction** (`ExternalSlotIndex`,
  `ItemHandlerExtractableStorageMixin`). RS finds a resource by scanning every slot,
  and it *simulates* constantly, so the scan runs far more often than real
  extractions. Measured at **42.9 ms/tick** on a struggling server — the single
  largest cost on the server thread, and the reason a network of ten external
  storages asks all ten for every resource.

  The index maps resource → candidate slots. **It never decides whether an item is
  extractable**: it only suggests slots, and each is re-read live and compared with
  `isSameItemSameComponents` exactly as before. A wrong index costs one slow scan,
  never a wrong answer.

  Absence is the one thing a slot read cannot verify, so it is bounded by time —
  `externalIndexTtlTicks`, default 20. Reporting "this inventory has none" from a
  one-second-old index is consistent with RS itself, whose external storage cache is
  only refreshed every 5–40 ticks by `detectChanges()`.

  Config: `externalStorageSlotIndex` (true), `externalIndexTtlTicks` (20),
  `minSlotsToIndex` (64 — below this a linear scan wins).

- **`/rstweaks selftest` now covers extraction.** A differential test builds a real
  `ItemHandlerExtractableStorage` over an `ItemStackHandler` and runs each scenario
  with the index on and off, asserting both the returned amount and the resulting
  inventory match. It targets one failure mode specifically: over-reporting under
  SIMULATE, where the fallback scan re-examines a slot the indexed pass already
  counted and tells RS more is available than exists. That failure crashes nothing —
  the craft just stalls later, far from the cause.

## 0.2.2

- **`getSlots()` no longer called once per slot** while scanning external inventories
  (`ItemHandlerExtractableStorageMixin`). RS writes its extraction loop as
  `for (int slot = 0; slot < itemHandler.getSlots(); slot++)`, and Java re-evaluates
  that condition every iteration — the JIT cannot hoist an interface call to an
  arbitrary implementation. Scanning a 5,000-slot controller made 5,001 calls.

  Only free when `getSlots()` is a field read, which it often isn't: Sophisticated
  Storage's `CachedFailedInsertInventoryHandler.getSlots()` resolves a `Supplier`
  chain each time. On a struggling server this was 2.58% + 1.28% of the whole server
  thread, ~6 ms/tick.

  Fixed in the caller rather than per storage mod, so it covers every
  `IItemHandler` — drawers, barrels, backpacks — at once.

  The cache is invalidated after every real (non-simulated) extraction, because
  extraction can resize the handler: pulling from a Drawer Controller whose handler
  has gone away triggers `invalidateSlots()`. Since nothing else runs on the server
  thread mid-method, our own `extractItem` is the only thing that can change the
  count, making this exact rather than merely likely-safe.

## 0.2.1

- **Drawer Controller connectivity check is now O(1)** (`ControllerInventoryHandlerMixin`).
  Functional Storage's `ControllerInventoryHandler` ran `List.contains` over every
  connected handler on each `getStackInSlot`, `insertItem` and `extractItem`, purely to
  confirm the handler was still attached. With a Refined Storage External Storage
  pointed at a Drawer Controller this was 13.97% of the server thread — the largest
  single frame — at ~9.3 ms/tick plus ~3.4 ms in insert/extract.

  Caches **handler membership only**; stack contents are still read live on every call,
  so external writes from hoppers, pipes or players are seen immediately. Reuses
  Functional Storage's existing invalidation — `ConnectedDrawers.rebuild()` is the sole
  place the handler list changes and already calls `invalidateSlots()`.

  Adds a required dependency on `functionalstorage [1.21.1-1.5.8,1.21.1-1.6.0)`.

## 0.2.0

- **Version is now visible.** Read from jar metadata at runtime, shown in the startup
  log and chat. Two earlier test rounds were misread because neither of us could tell
  which jar was actually running.
- **Split the LP planner's decline reason** into distinct causes. It previously
  collapsed "infeasible", "budget exhausted" and "simulation gave up" into one
  message, which is the difference between knowing what to fix and guessing.
- Infeasible plans now **name the blocking resource** — the one required with no
  pattern and none in storage — plus graph shape (`patterns=N, classes=M`).
- Seed-repair failures name the seeded resources.

## 0.1.0

Everything up to and including the linear planner. Shipped as a single version across
roughly eight distinct builds, which is what prompted the versioning above.

**Measured: 5.29 → 19.98 TPS.**

- **Step Requester backoff** — per-slot escalating backoff (1s doubling to 10s) after
  a failed craft attempt. Was 77.8% of the server thread; 147 → ~3 ms/tick. The
  satisfiable path is untouched.
- **Cable Tiers sided-input lookup** — hoisted an invariant map out of a per-slot
  loop, dropped two stream pipelines. 1.580 → 0.110 ms/tick.
- **Uncraftable recheck cache** — caches negative `ensureTask` results for 3s,
  removing the `binarySearchMaxAmount` storm where each probe ran a full crafting
  calculation.
- **Pattern-plan copy-on-write** — shares ingredient maps between plan snapshots
  instead of deep-copying. Spike time 5.6× down; live `CraftingTree` objects went from
  140,387 to none.
- **LP crafting planner** (`lpPlanner`, opt-in) — linear-programming planner for
  byproduct/cycle subgraphs so recycled containers net out. Solver verified
  standalone; declines safely to stock RS on anything it cannot solve.
- `/rstweaks selftest`, gametests, chat counters.
