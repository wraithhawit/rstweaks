# rstweaks — Refined Storage tick-time optimizations

Mixin mod targeting Refined Storage **and its addons** in All the Mods 10,
developed against a copy of a live world and driven by spark profiles.

## Download

**[Latest release →](https://github.com/wraithhawit/rstweaks/releases/latest)**

Drop the `.jar` into your `mods` folder. **Delete any older `rstweaks-*.jar` first** —
do not rely on load order to pick the newest. Install it on the server; in
singleplayer that means installing it normally, since the integrated server is where
almost all of this runs.

Confirm it actually loaded before judging any result — the log line is
`[rstweaks] vX.Y.Z loaded`.

| | |
|---|---|
| Requires | Minecraft 1.21.1, NeoForge, **Refined Storage 2.0.9** |
| Optional | Step Crafter, Cable Tiers, Functional Storage — extra tweaks apply only if these are present, and nothing breaks if they are not |
| Config | `config/rstweaks-common.toml`, every option documented in the file itself |
| In game | `/rstweaks stats` shows what has actually fired |

The Refined Storage version range is exact on purpose. Injection points are matched by
method descriptor against the specific build in `libs/`, so a different Refined Storage
is refused at load rather than allowed to fail somewhere less obvious.

Bug reports and questions go in [Issues](https://github.com/wraithhawit/rstweaks/issues).
For anything involving crafting, set `silenceAutocraftingDebugLog=false` and attach the
log — it saves a round trip.

## Environment (must stay in sync with the instance)

| | |
|---|---|
| Instance | `D:\Minecraft Instances\All the Mods 10 - ATM10(1)` (a disposable copy) |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.234 |
| Refined Storage | **2.0.9** |
| Step Crafter | **0.1.5** (declares itself as `1.21.1-0.1.5`) |
| spark | 1.10.124 |
| Profiling world | `saves\survival-rstweaks` → junction → `F:\Downloads\rstweaks\worlds\survival-rstweaks` |

All targeted mods are compiled against the exact jars copied into `libs/`, not
CurseForge maven coordinates. Mixin injection points are matched by exact method
descriptor, so the jars we compile against must be the identical builds the game
loads.

## Round 1 — Step Requester autocrafting storm

### Finding

Baseline 120s profile of `survival-rstweaks`:

```
91.6%  Level.tickBlockEntities
 81.6%  NetworkNodeBlockEntityTicker.tick             [refinedstorage]
  77.8%  StepRequesterNetworkNode.doWork              [stepcrafter]
   77.8%  AutocraftingNetworkComponentImpl.startTask  [refinedstorage]
    77.8%  CraftingCalculatorImpl.calculate
     75.5%  CraftingTree.calculate → calculateIngredient → calculateChild (recursive)
```

`MutablePatternPlan.copy` alone was **46.5% of the entire server thread**, almost
all of it inside `HashMap` copy constructors — which is why `HashMap.putMapEntries`
/ `putVal` / `hash` together were 57.7% of self time. A concurrent
`/spark heapsummary` corroborated it: 140,387 live `CraftingTree`, 842,015
`MutablePatternPlan`, 1.2M `ResourceState`, 2.2M `OperationResult`.

### Cause

`StepRequesterNetworkNode.doWork()` runs every tick. When a filter slot's request
cannot be satisfied, `startTask()` runs a full recursive crafting calculation,
returns empty, the slot is marked `NOT_ENOUGH_INGREDIENTS` — and nothing records
that the attempt failed. Next tick the guard conditions are identical, so the whole
calculation runs again, 20×/second, indefinitely.

The satisfiable path is already cheap: once a task is in flight
`isAlreadyRunningTask()` short-circuits the slot. It is specifically an
*unsatisfiable* request that burns the tick.

### Fix

Per-slot escalating backoff, applied only after a genuine failure
(`StepRequesterNetworkNodeMixin`):

| Case | Effect |
|---|---|
| Craft can start | **Unchanged** — starts on the same tick as vanilla |
| Task already running | **Unchanged** |
| Slot satisfied | **Unchanged** |
| Craft cannot start | Retries after 1s, doubling to a 10s cap while it stays impossible |

A sleeping slot is skipped in full — no status write, no calculation. Any success
resets the slot to no delay. Once a slot reaches the 10s cap, and only then, it also
checks whether you edited it in the GUI and retries immediately if so.

Config (`config/rstweaks-common.toml`):

- `stepRequesterFailureBackoffTicks` — default 20 (1s)
- `stepRequesterMaxBackoffTicks` — default 200 (10s); set equal to the above to
  disable escalation

### Not the cause

Disk I/O, despite D: being full at the time: `RandomAccessFile.writeBytes0` was
0.21% of the profile. Freeing space on D: remains worth doing for save safety, but
it was not contributing to the lag.

## Round 1 result

**TPS 5.27 → 16.93** (635 → 2,035 ticks per 120s).

| | before | after |
|---|---|---|
| StepRequester `doWork` | 77.83% | 4.75% |
| `MutablePatternPlan.copy` | 46.54% | 1.20% |
| `HashMap.putMapEntries` (self) | 29.43% | 0.78% |
| RS self time | 79.29% | 13.63% |
| live `CraftingTree` objects | 140,387 | off the list |

Other mods' percentages rose (mekanism 1.37→5.30, cabletiers 0.14→2.99). That is
not new cost: percentages are relative to the same 120s of wall time, and 3.2× more
ticks now run in that window, so the same per-tick work appears more often.

## Round 2 — Tiered Autocrafter sided-input lookup

### Finding

Refined Storage probes each external pattern sink to ask whether it *would* accept
an iteration's inputs. Every task step, for every pattern:

```
7.71% TaskImpl.stepPattern                            [refinedstorage]
 4.56%  ExternalTaskPattern.acceptsIterationInputs     [refinedstorage — a probe]
  4.01%   getSinkThatIsAcceptingResources
   3.92%    TieredAutocrafterBlockEntity.accept        [cabletiers]
    3.85%     findResult
     2.68%      findSidedInputPatternState  (1.72% of it in stream().toList())
```

Cable Tiers' *own* self time is only ~3% of the thread and no single frame exceeds
0.1% self — the cost is allocation, not logic.

### Cause

`findSidedInputPatternState` walks every pattern-container slot and, per slot,
materialises the slot's sided resources through a stream pipeline and then calls
`resourcesMatchesIgnoringIndex`, which builds a grouping map for the slot **and
rebuilds the map for `resources`**. That second map depends only on `resources`,
which is invariant for the whole scan — so it was rebuilt once per slot for nothing.

### Fix

`TieredAutocrafterBlockEntityMixin` replaces the lookup with an equivalent that
hoists the invariant map out of the loop, merges into a reused map instead of
allocating per slot, drops both stream pipelines, and skips empty slots before the
data-component lookup.

Behaviour is identical: the original compared key sets then every value, which is
exactly `Map.equals`, and both sides are built with the same grouping semantics.
Empty stacks carry no components, so the early-out cannot skip a slot the original
would have matched. No config — this is a pure-equivalence change with nothing to
tune.

## Fuzzy mode — investigated, not a problem

Measured across both profiles, every fuzzy-related frame combined is **under 0.5%**
of the server thread, and the largest single one (`PipeType.deepFuzzyCompare`,
0.29%) belongs to **Pipez**, not Refined Storage.

`FuzzyResourceListImpl` maintains a `Map<normalizedResource, Set<actualResource>>`
index that is updated incrementally on add/remove, so `getFuzzy` is a single hash
lookup rather than a scan. RS's own fuzzy frames measure: `getFuzzy` 0.02%,
`add` 0.13%, `addToIndex` 0.10%, `remove` 0.02%.

**Leave fuzzy mode on.** Turning it off would cost you functionality and buy
nothing measurable.

## Round 3 — the uncraftable-recheck storm

### How RS autocrafting actually works

1. A requester calls `startTask` or `ensureTask`.
2. `CraftingCalculatorImpl.calculate` walks patterns recursively, building a
   `CraftingTree`; `TaskPlanCraftingCalculatorListener` turns it into a plan. This
   is where the `MutablePatternPlan.copy` HashMap churn lives.
3. On success a `TaskImpl` is added to that pattern provider's own `TaskContainer`
   and stepped once per tick by `PatternProviderNetworkNode.doWork`.

### The defect

`ensureTask` — what an Exporter with an autocrafting upgrade calls whenever its
resource is missing — is brutal when the answer is "no":

1. `calculatePlan` runs a full recursive calculation;
2. it fails, so `ensureTaskForCraftableAmount` calls `binarySearchMaxAmount`, which
   doubles 1 → 2 → 4 → … until the amount is no longer craftable, then binary
   searches the gap. **Every probe is another full recursive calculation.**
3. then a third calculation for the amount it settled on.

Nothing caches the outcome, so the next tick repeats all of it — the same shape of
defect as round 1, reached through a different door.

Worse, `ensureTaskForCraftableAmount` passes `CancellationToken.NONE` into the
binary search instead of the caller's `TimeoutableCancellationToken`. The timeout
meant to bound this work is discarded at exactly the point where the most work
happens.

### Fix

`AutocraftingNetworkComponentImplMixin` caches negative results per resource for
`uncraftableRecheckTicks` (default 60 = 3s). Any non-negative outcome clears the
entry immediately.

Keying on the resource alone while ignoring the requested amount is sound rather
than approximate: `MISSING_RESOURCES` is only returned when the binary search found
a maximum craftable amount of zero, or when the plan failed even at that maximum.
Both mean nothing is craftable, at any amount.

This needs a clock, and network components have no access to the level or server —
hence `ServerTicks`.

## Round 4 — copy-on-write for pattern plans

### Finding

The spike profile (`--only-ticks-over`, 25.3s of laggy ticks sampled from 300s) is
dominated by crafting calculation, and `MutablePatternPlan.copy` is the **largest
single self-time frame** in it at 6.71%. Working the numbers, each doomed Step
Requester calculation costs roughly **94 ms** — a visible tick hitch every time the
backoff expires.

Round 1 reduced how *often* calculations run. It did nothing about how expensive
each one is.

### Cause

The calculator explores a tree and snapshots the whole plan at every child
calculation so a failed branch can be discarded. `MutableTaskPlan.copy` deep-copies
every `MutablePatternPlan`, each of which allocates a fresh `LinkedHashMap` per
ingredient index. Nearly all of it is wasted — most snapshots are discarded
untouched, and those that aren't are usually written at a single index.

### Fix

`MutablePatternPlanMixin` shares the inner ingredient maps on copy, and
`addUsedIngredient` takes a private copy of an index's map before its first write
since the last share.

The trade is justified directly by measurement: at baseline `copy` was 46.5% of the
server thread against `addUsedIngredient`'s 4.6%, so moving work from copy-time to
write-time is roughly ten-to-one even if every shared map is eventually written.

**Correctness invariant:** *no plan ever mutates a map another plan can observe.*
Sharing happens only in `copy()`, which clears the source's ownership claim, so both
the source and the new copy must take their own map before writing. Two plans
sharing map `M` at index `i` each copy `M` on their own first write. Reads
(`getPlan`) never mutate; `iterations` is a primitive copied by value.

Targeted by name (`@Mixin(targets = ...)`) because `MutablePatternPlan` is
package-private.

**Kill switch:** `lazyPatternPlanCopy = false`. Safe to toggle at runtime in either
direction, including with tasks in flight — the copy-before-write guard stays active
regardless, so disabling only makes `copy()` eager again, which is redundant rather
than incorrect. The toggle is mirrored into a plain `static volatile` field because
the mixin reads it far too often for a config-spec lookup.

### Round 4 result

| | baseline | after r1 | after r4 |
|---|---|---|---|
| **TPS** | 5.29 | 16.96 | **19.98** |
| Spike time per 300s | — | 25.35s | **4.56s** |
| Heap total | 7,966 MB | 8,945 MB | **5,117 MB** |
| live `CraftingTree` | 140,387 | 1,332 | gone |
| live `MutablePatternPlan` | 842,015 | 995 | gone |
| live `OperationResult` | 2,231,977 | 1,330,779 | gone |

Copy machinery in the spike window went 1.70s → 0.43s, on top of the window itself
shrinking 5.6×. `/rstweaks selftest` passes all 10 scenarios.

Remaining spike cost is ~22% disk I/O (`IOUtilities.atomicWrite`,
`NbtIo.writeCompressed`) — world autosave on a drive with 1.43 GB free. That is now
the best remaining action, and it is not a code change.

## Testing

Three harnesses, in the order you should reach for them.

```
.\gradlew.bat plannerCheck
```

The solver, in a plain JVM, in about a second. Everything made of arithmetic belongs
here: 46 scenarios covering the LP planner and the max-craftable calculation. It cannot
test a single line of `mixin/` — nothing transforms Refined Storage's bytecode in a bare
JVM, so anything mixin-dependent run here exercises stock RS and passes regardless.

```
.\gradlew.bat runGameTestServer
```

The paths that only fail in a running game. Boots a dedicated server with Refined Storage
staged into its mods folder, runs every `@GameTest` in the `rstweaks` namespace with the
mixins applied, and exits non-zero if any fail — about a minute, no client, no world, no
hand actions. Six tests:

| Test | What it holds down |
| --- | --- |
| `craftingTaskDeliversItsOutput` | Six crafting tasks stepped to completion through the real task engine, with every resource audited against `stored + made - used`. A throw in a task step makes Refined Storage call it *completed* and destroy the internal storage, which is what 0.2.57–0.2.63 did to 26 crafts out of 26 in one session. |
| `externalExtractionMatchesUnindexed` | 24 extractions from an external inventory, including a stale slot index. What physically leaves has to equal what was reported — the gap 0.2.55 lost items through. |
| `craftingPlanCopyOnWrite` | The copy-on-write plan optimization must not change the plan. |
| `paysWithAStoredContainer`, `paysWithFluidWhenNoContainerIsStored`, `leavesTheEmptyContainerWhenTheNetworkCannotPay` | The Crafting Grid refill, against a network built from real blocks: both payment routes and the decline. |

Every one of those was confirmed to **fail** with the bug it targets put back into the
product code. That is the only thing that makes a green run mean anything, and it is not
a formality — the first version of the stale-index fixtures disturbed the wrong slot and
passed happily with 0.2.55's item-loss bug reinstated.

```
/rstweaks selftest
```

The same assertions, inside a world you are already in. Needs no launch flag, so it is
the one to reach for when the question is about a specific pack rather than about this
mod's code.

Differential test: runs each crafting calculation twice, once with
`lazyPatternPlanCopy` enabled and once disabled, and asserts the resulting
`TaskPlan`s are identical. Testing the invariant — *the optimization must not change
the result* — rather than hand-computed expected values, which would only ever cover
recipes the author thought of.

Ten scenarios target the shapes where sharing a mutable map goes wrong: a shared
intermediate reached down two branches, a diamond needing the same sub-recipe twice,
one resource consumed at several ingredient indices, partial availability forcing
backtracking, wide recipes, deep trees, alternative inputs, and an unsatisfiable
request. A sharing bug surfaces as an ingredient amount doubled, halved or missing.

Each scenario declares whether it should produce a plan at all, and a mismatch is
reported as a broken fixture. This is not theoretical: the first version of these
fixtures added the storage source *before* inserting into it, and since
`RootStorageImpl` snapshots a source's contents at `addSource` time, every scenario
returned an empty plan — two empty results comparing equal and reporting a confident
pass while testing nothing.

NeoForge only registers gametests when `-Dneoforge.enabledGameTestNamespaces=rstweaks` is
on the command line, which `runGameTestServer` sets for you. `/rstweaks selftest` needs no
launch flag and runs the identical checks.

## In-game reporting

On join, and periodically afterwards, rstweaks reports in chat:

```
[rstweaks] Active: Step Requester backoff, Tiered Autocrafter lookup
[rstweaks] 12,431 crafting calculations skipped, 87 failed attempts backed off,
         45,102 fast pattern lookups since last report.
```

These are counts of work **avoided**, chosen over a bare "loaded" line because a
startup message only proves the jar was read — a non-zero skip count proves the
injections actually fired on the hot path. A summary is suppressed entirely when
nothing changed, so an idle network stays quiet rather than repeating zeroes.

Config:

- `chatNotifications` — default `true`; set `false` to silence
- `chatNotificationIntervalSeconds` — default `300`; `0` reports only on join

The counters in `Stats` are plain `long`s, not atomics, and are written from the
very hot paths being optimized — an atomic increment on the sided-input lookup
would be a measurable tax on a path that runs thousands of times per tick. They are
diagnostics, not accounting. The periodic timer counts ticks rather than wall time
so that a lagging server stretches the interval instead of firing a burst of
reports once it catches up.

## Scope

In scope — confirmed to declare a `refinedstorage` dependency in their manifest:

| Mod | Version |
|---|---|
| Refined Storage | 2.0.9 |
| Cable Tiers | 0.6.13 |
| Step Crafter | 0.1.5 |
| ExtraDisks | 4.0.15 |
| ExtraStorage | 5.0.9 |
| Refined Types | 0.3.2 |
| RSPolymorph | 1.2.0 |
| Universal Grid | 0.3.2 |
| RS Quartz Arsenal | 1.0.8 |
| RS Mekanism Integration | 1.1.1 |

Explicitly **not** in scope — AE2-ecosystem or standalone despite looking
storage-adjacent: Megacells, ExtendedAE, ExpandedAE, Advanced AE, ME Requester,
Ender Drives, AppFlux, AppMek, ArsEng, Logistics Networks, all `ae2*`, plus Pocket
Storage, DimStorage and Storage Delight (no RS dependency).

**Cable Tiers already mixins into Refined Storage** — 14 server-side mixins
including `MixinAutocrafterBlockEntity` and `MixinAbstractExternalStorageBlockEntity`.
Check for injection conflicts before touching either.

## Build and install

```powershell
cd F:\Downloads\rstweaks
.\gradlew.bat build
copy build\libs\rstweaks-0.1.0.jar "D:\Minecraft Instances\All the Mods 10 - ATM10(1)\minecraft\mods\"
```

## The iteration loop

1. Launch ATM10(1), load **`survival-rstweaks`** (shown as *survival* with the folder
   name beneath it — not plain `survival`).
2. Confirm `[rstweaks] loaded` in the log, and no mixin errors.
3. Stand somewhere representative, let the world settle ~30s.
4. `/spark profiler start --timeout 120 --thread *`
5. Paste the link.

Note: `--alloc` is unavailable on Windows (it needs async-profiler, Linux/macOS
only). Use `/spark heapsummary` and `/spark gcmonitor` for the allocation picture
instead.

### Comparing rounds

Take every profile identically — same world, same standing position, same duration,
same settle time. Otherwise round-to-round deltas are noise. Keep every link;
before/after pairs are the only evidence a mixin did anything.

## Analysis tooling

Spark share links are a JS viewer; the payload lives at
`https://spark-usercontent.lucko.me/<code>` as raw protobuf. The scratchpad scripts
(`pb.py`, `analyze.py`, `blame.py`, `heap.py`) decode it schema-free. Layout worked
out empirically:

```
root.1 = metadata   root.2 = ThreadNode   root.3 = ClassSource (class → mod)
ThreadNode:     1=name, 3=flat StackTraceNode pool, 4=times, 5=root refs
StackTraceNode: 3=class, 4=method, 6=line, 7=desc,
                8=times (packed float64 ms, per window), 9=children_refs (packed varint)
heapsummary:    root.2 = { 2=instances, 3=totalSize, 4=className }
```

Field 8 is packed **doubles**, not varints — decoding it as varints yields
plausible-looking but meaningless numbers.

## Layout

```
libs/                     RS + addon jars, compile-only
worlds/survival-rstweaks/   the actual world data (junctioned into the instance)
decompiled/               Vineflower output for RS, Step Crafter, Cable Tiers
src/main/java/.../mixin/  optimizations
src/main/resources/
  rstweaks.mixins.json      mixin registry; add each class here
  META-INF/neoforge.mods.toml
```

`rstweaks.mixins.json` has `"required": true` and `defaultRequire: 1`, so a mixin that
fails to apply crashes the game loudly instead of silently doing nothing. That is
deliberate — a silently-skipped injection would otherwise read as "the optimization
didn't help" in the next profile.

## Next target

If round 1 confirms, the follow-up is Refined Storage core itself:
`MutablePatternPlan.copy` / `MutableTaskPlan.copy` deep-copying `HashMap`s at every
crafting-tree node. That benefits all RS autocrafting rather than one block, but it
is deeper surgery and should be measured on its own.
