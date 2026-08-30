# Changelog

The version prints at startup (`[rstweaks] vX.Y.Z loaded ...`) and in the chat join
message, so a test result can always be tied to an exact build.

Patch digit bumps on every build handed over for testing.

`VERSIONS.txt` is the short form of this file — one or two lines per version. Both are
maintained; this one carries the reasoning, that one is the index.

## 0.17.0

**Byproduct aging was the largest thing left, and most of it was ours.**

0.16.0 landed — **408,664,800 steps skipped whole**, durability path **31.03% → 26.87%** (profile
`ajw2GTmG3M`), verified at 442,767,424 checks with zero divergences.

With whole steps gone, what remains concentrates in the steps that actually execute, and byproduct
aging rose to the top:

```
rstweaks$tryKeepByproductsInTask   31.58% of thread
  InternalTaskPattern.aged         27.68%
    ItemDurability.afterUses       51.87% of that = 14.4% of thread   <- ours
    wearStep                       21.10%         =  5.8%             <- RS
    rstweaks$consumedTool          18.83%         =  5.2% of thread   <- ours
```

`afterUses` built an `ItemStack`, copied its component map and rebuilt an `ItemResource` on **every
call**, to compute something completely determined by its input. Now cached for `uses == 1`, which
is what a wear step asks for; anything else allocates as before.

### Keyed on the resource, not on `(item, damage)`

The aged resource is built from the old one's stack, so it **carries the whole component patch
forward**. An `(item, damage)` key would hand a named or enchanted tool the plain tool's answer and
silently strip its components mid-craft — the issue-9 family of bug, and invisible.

A new scenario pins exactly that collision, and it is **break-tested**: keying the cache on `Item`
fails

```
durability diverged in 2 of 209 scenarios:
  a named tool does not age into the unnamed one | the name survives aging
```

and keying it on the resource passes all 209.

The tool-breaks case is deliberately **not** cached: it happens once per tool, that path allocates
nothing anyway, and caching it would mean a null sentinel to speed up the rare branch.

### `consumedTool` finally gets 0.12.0's hoist

It resolved the encoded side's tool family once per entry in the consumed list instead of once for
the loop — the same mistake 0.12.0 fixed in `findWornTool` and left here, because nothing was
measuring this loop at the time.

Both changes are ours, both are pure functions of immutable inputs, and neither touches Refined
Storage's core — so this one is back inside the safe zone after two versions in the risky one.


## 0.16.0

**Skip the whole step, not just our part of it.**

Profile `L7lGOu8YyD`: 0.15.0's replay works exactly as designed — **236,864,784 decisions replayed of
322,780,160 calls (73.4%)**, dead on the ceiling — and bought **1.01 points**, 32.04% → 31.03%. I
predicted more than 0.14.0's 4.24 and was wrong for the third time.

The reason is a floor, not a mistake. `replayDecision` alone is **10.42%** of the server thread for
236.9M replays: about **53ns each**, on a method called **2.7 million times a second**. At that
volume nothing per-call is cheap, so the next win cannot come from making the call cheaper. It has
to come from not making the call.

### The step itself

`InternalTaskPattern.step` returns `IDLE` the instant `extractAll(SIMULATE)` is false, having done
nothing else. When internal storage has not changed since such a step, it reaches the same
conclusion by the same route — so returning `IDLE` up front is not an approximation of what Refined
Storage does, it is what Refined Storage does.

And it skips what we could never otherwise touch: `calculateIterationInputs` (28.24% inclusive) and
`extractAll` (34.56%).

### Verified in the bytecode, because the idea rests on it

A failed simulate must have no side effects:

- `calculateIterationInputs`' mutating branch is gated on `Action.EXECUTE` — the simulate pass reads
  the ingredient budget and writes nothing
- `step` returns before touching outputs, byproducts, wear, or the root storage
- `TaskImpl.stepPatterns` only asks whether the result is `COMPLETED` and whether `isChanged()`, so
  `IDLE` is its ordinary "nothing happened" path

### It returns a value it cannot name

`PatternStepResult` is package-private, so `StepSkipMixin` cannot reference the type or its `IDLE`
constant. It doesn't need to: it **captures the instance** from a real return, recognising it by
`Enum.name()`, and hands that same object back.

Fail-safe in the right direction. Until an `IDLE` has actually been observed there is nothing to
return and nothing is skipped; and if RS ever renames the constant, the optimization disappears
rather than misbehaving.

### The verifier again

`verifyStepSkip` (default **off**) lets every skippable step run and asserts it really did return
`IDLE`. The same discipline cleared 0.15.0's replay at **118,470,766 checks, 0 diverged** — stronger
evidence than the fixture can give, since the task-engine suite contains no repeated failing
simulates by construction.

Mixin confirmed applying to `InternalTaskPattern`; 32 gametests pass, task engine included.

### Verified in game

```
steps skipped whole: 0  (verifier: 442,767,424 checked, 0 DIVERGED)
```

442 million skippable steps allowed to run and checked against what the skip would have returned.
Every one really did return `IDLE`. The `0 skipped` on that same reading is the verifier doing its
job -- it lets every skip run so it has something to compare against.


## 0.15.0

**The loop was the cost, not the scan.**

0.14.0 skipped the storage scan on a repeated failing simulate and left the machinery around it
running. Profile `m4dQmEhBRW`, inside `trySubstituteWornTool` (31.82% of the server thread):

| | share of that subtree |
|---|---|
| loop overhead (self) | **30.46%** |
| `findWornTool` — the scan 0.14.0 removed | 1.60% *of thread* |
| `getAll` + `get` + `isDurable` | 9.64% |
| `rememberedSubstitute` | 2.00% *of thread* |

Walking every input and asking a map about each one, to arrive at an answer already in hand.

A valid repeat now **replays** the whole decision: restore the consumed list, apply the remembered
swaps, return. No walk, no durability question, no per-ingredient lookup. The swaps still have to be
applied, because `calculateIterationInputs` builds a fresh input list every step.

### The consumed list is copied, not aliased

`rstweaks$consumed` is cleared at the top of every call, so holding a reference to it would replay an
**empty** list — and an empty consumed list means byproducts come back as encoded. That is a
repaired tool: durability created out of nothing, which is the 0.2.57 bug.

### No test can cover this, and that is the honest problem

The task-engine fixture treats a step that makes no progress as a deadlock and fails the scenario,
so it contains **zero failing simulates by construction** — measured, not assumed. That leaves a
change on the item-correctness path with no automated coverage, which is not something to ship on
reasoning alone.

### So it ships with its own verifier

`verifyReplayedDecisions` (default **off**) recomputes every replayed decision and compares **both**
the swaps and the consumed list, logging the first 20 divergences in full:

```
worn-tool reuse (failing simulate): N decisions replayed of M calls (X%); K recomputed
replay verifier: 12,481,003 checked, 0 DIVERGED
```

It costs the entire saving while it runs — the point is to answer the question, not to be fast.
Anything other than `0 DIVERGED` means `reuseFailedSimulate` comes back out.

### Counters are a matched pair now

`simulateDecisionsReplayed` / `simulateDecisionsComputed` are both **per call**. The previous pair
mixed a per-call numerator with a per-resource denominator, which would have produced a confident
nonsense percentage — a worse failure than no counter at all.

### Verified in game

```
replay verifier: 118,470,766 checked, 0 DIVERGED
```

118 million replayed decisions recomputed and compared — swaps **and** consumed list — on a real
insanium craft, with not one divergence. That is stronger evidence than any fixture could have
produced, which is the whole argument for shipping a verifier instead of the reasoning behind it.

(The `0 decisions replayed` on the same reading is expected: the verifier deliberately takes the slow
path so it has something to compare against.)


## 0.14.1

**0.14.0's counter was never printed, and its changelog said it was.**

`failedSimulateScansAvoided` is incremented on the hot path and appears in no report. So the single
number that says whether the largest optimization in this series is firing **could not be read from
inside the game** — and the 0.14.0 entry's claim that `/rstweaks stats` reports it is simply false.

This is the **fourth** time this project has shipped something whose presence is indistinguishable
from its absence, and it happened two versions after 0.13.1 was written to fix exactly that. Worth
recording rather than quietly patching.

`/rstweaks stats` now prints the two caches on **separate lines**:

```
worn-tool reuse (execute): 86,907,741 scans avoided of 86,907,741 eligible (100.0%); missed 0, 0
worn-tool reuse (failing simulate): N scans avoided of M (X%); K rescanned because storage or inputs had changed
```

Not folded together, deliberately. They have different denominators and different failure modes, and
a single line hiding a zero is how this happened in the first place.

### What 0.14.0 actually did

Profile `m4dQmEhBRW`:

| | 0.13.x | 0.14.0 |
|---|---|---|
| `findWornTool` self | 4.01% | **1.60%** |
| durability path | 36.28% | **32.04%** |

The series now reads **40.15 → 41.11 → 38.82 → 37.03 → 36.28 → 32.04**. That 4.24-point step is
larger than the previous four versions put together, and it is the first one that came from deleting
work rather than making work cheaper.

What is left is mostly not ours: `calculateIterationInputs` is the largest remaining item at 27.36%
inclusive, and `HashMap.hash` the largest leaf at 24.85% self — both Refined Storage's own.

No behaviour change.


## 0.14.0

**The failing simulate is cached — and the 1,052 disagreements are handled exactly, not gambled on.**

0.13.3's diagnostic answered the open question outright. In every logged disagreement the `wanted`
key was **identical**, and what moved was storage:

| # | before | now |
|---|---|---|
| 1–2 | *no substitute found* | `→ @275` (storage has 1) |
| 3–4 | `→ @{}` fresh, storage has 350,496 | `→ @275`, storage has 1 |
| 5 | `→ @275`, storage now has 0 | `→ @494`, storage has 1 |

The crystal wears, a more-worn level appears in shared storage, and `findWornTool`'s deliberate
"most worn first" rule flips the pick — fresh, then `@275`, then `@494` — even with 350,496 fresh
crystals sitting there. Case 1–2 is the dangerous one: the previous answer was *no substitute at
all*, and a blind cache would have turned that into a craft that refuses to run.

`calculateIterationInputs` is ruled out. It never once handed back a different `wanted`.

### Every one of those is a storage mutation

So `MutableResourceListImplMixin` counts them — `add`, `remove`, `clear` — and a failing simulate
reuses the previous answer **only** while the storage version *and* the input count are unchanged.
Two `O(1)` reads. Exact rather than statistical, invalidating on precisely the 1,052.

### The prize

169,947,478 repeated failing simulates on a real insanium craft, with a single pattern rescanning
the task's entire internal storage **701,697 times in a row**. About 73% of iterations fail their
simulate and never execute, which is exactly why the execute-side cache could only ever reach a
fifth of the scans.

### Safe by construction, not by the mixin having landed

A storage that does not implement `VersionedResourceList` reuses nothing and rescans as before.
`reuseFailedSimulate` (default on) switches it off, and it is ignored while either probe runs so a
probe never measures a cache against itself.

### The mixin is proven to apply, and break-tested

A new gametest builds a real `MutableResourceListImpl`, asserts it implements
`VersionedResourceList`, and exercises `add`, `remove` and `clear` **separately** — `0 → 3`.

Deleting the increment from the `remove` injector fails that test by name:

```
internalstoragecountsitsownmutations failed: remove did not bump the storage version
  -- this is the mutation that the 1,052 observed disagreements were made of
```

Restoring it passes all 32. A counter that applies but misses one mutation is the single way this
design can be *wrong* rather than merely useless, so each mutation is pinned on its own rather than
asserting the number merely moved.

### Not measured in game

The fixture has zero failing simulates, so **no automated test can show the cache firing**.
`/rstweaks stats` reports `failedSimulateScansAvoided`; that number and a fresh profile are what
will say whether this was worth building.


## 0.13.3

**What are the 464?**

0.13.2's probe, on a real insanium craft:

```
simulate repeats: 75,360,365, 75,359,901 agreed, 464 disagreed;
                  longest failing streak 701,697
```

**The payoff is enormous** — one pattern rescanned the task's entire internal storage **701,697
times in a row** for the same answer, and there are 75 million of these repeats against 27 million
execute-side calls. This is roughly four times the prize the execute-side cache was chasing.

**And the invariant is 99.99938%, not 100%.** That is exactly the shape that gets a wrong answer
shipped, because the number looks close enough to round up.

### Two causes would produce 464, and they need different fixes

| cause | what it looks like | fix |
|---|---|---|
| internal storage changed between the two simulates — RS steps many patterns per tick inside one task, and a sibling executing mutates the shared storage | equal `wanted` keys, different amounts | version the storage, invalidate on mutation |
| the **inputs** changed — `calculateIterationInputs` is recomputed every step and takes the `Action`, so the wanted wear level itself can differ | a different `wanted` key | versioning storage would not catch this at all |

I had inferred the first and demonstrated neither.

### So it logs the evidence

A disagreement now prints both decisions with, for every resource named on either side, what internal
storage holds and what the inputs ask for:

```
[rstweaks] simulate repeat disagreed (#3, streak 41207)
  before:
    wanted ItemResource[...crystal, damage=41] (inputs ask 1, storage has 0)
      -> ItemResource[...crystal, damage=52] (storage has 1)
  now:
    wanted ItemResource[...crystal, damage=41] (inputs ask 1, storage has 0)
      -> ItemResource[...crystal, damage=53] (storage has 1)
```

Capped at 20 lines. This is the hottest path in the mod, and a rare event logged without a bound is
how a diagnostic becomes the outage.

Rides on `simulateRepeatProbe` — no new config key to set. No behaviour change.

### Not building the cache yet

It needs a new mixin on RS's `MutableResourceListImpl` to be exact rather than statistical. Worth
knowing which cause is real before adding coupling to a core RS class.


## 0.13.2

**The reuse is perfect. The ceiling was the ratio, and I misread it.**

0.13.1's counters, from a real insanium craft:

```
worn-tool reuse: 33,271,287 scans avoided of 33,271,287 eligible (100.0%);
                 missed 0 nothing-remembered, 0 revalidation-failed;
                 125,013,928 not eligible
```

**A 100% hit rate, zero misses of either kind.** My 0.13.0 conclusion — "the reuse fires about 1% of
the time" — was wrong, and it was wrong because I inferred a hit rate from a profile instead of
counting it. The fast path was working the whole time.

### What is actually small

"Not eligible" can only be the SIMULATE pass, since both flags were on. So the real shape is
**3.76 simulates per execute**: `InternalTaskPattern.step` returns `IDLE` when
`extractAll(SIMULATE)` is false, so roughly **73% of iterations fail their simulate and never
execute**.

The execute-side cache could therefore only ever reach a fifth of the scans — and it collects all
of that fifth. 0.13.0 moved 0.75 points because that is everything the idea had to give.

### So the target moves to the other four fifths

Refined Storage re-steps one pattern up to 175,552 times a tick, so a pattern that cannot proceed
rescans the task's whole internal storage for an answer that has not changed since the last time it
asked.

`simulateRepeatProbe` (default **off**) counts whether consecutive failing simulates reach the same
substitution, and the **longest failing streak** — the payoff figure, since a cache that saves one
rescan is not worth writing and one that saves a thousand is. It does *not* disable the execute-side
cache; the two are on different passes and do not interact.

It needs no new state to detect a repeat: `rstweaks$simulatedSwaps` is nulled by every EXECUTE, so
finding it still populated at the top of a SIMULATE means the previous simulate was never consumed
by one — which is exactly a failing repeat.

### Honest about coverage

**The fixture produces zero repeats, measured.** Every task-engine scenario is built so its patterns
*can* proceed, so no simulate fails and the probe never fires there. The gametest pins that repeats
reconcile as agreed plus disagreed, and explicitly does not claim to prove the probe fires — that
would be the exact trap `theSubstitutionProbeActuallyFires` exists to avoid. Only a real craft can
prove this one.

No behaviour change.


## 0.13.1

**0.13.0 underdelivered, and I could not say why. That is the bug this fixes.**

Profile `2Dwcfddcxn` (0.13.0, 1M insanium): the durability path went **37.03% → 36.28%**, against an
arithmetic prediction of removing roughly 28% of it.

The tree says where it went wrong:

```
trySubstituteWornTool                    36.28%  of the server thread
  rstweaks$findWornToolReusing           46.08%  of that
    findWornTool  (the full scan)        94.67%  of THAT
    MutableResourceListImpl.get           0.92%  <- the revalidation, i.e. the fast path
```

**The reuse fires about 1% of the time in a real craft** — while the gametest shows it firing, 83
scans avoided. Config was checked and is correct: `substitutionProbe = false`,
`reuseSimulatedSubstitution = true`. So the fast path is being entered and the lookup is missing.

### The actual mistake was shipping it unreadable

`substitutionScansAvoided` existed in 0.13.0 and **was never surfaced anywhere**. The only way to
discover the hit rate was a spark profile and a round trip — and even then a profile cannot say
*which* branch missed. A counter nobody can read is not a counter, and this project has now made
that mistake four times.

So the miss is split three ways and reported unconditionally by `/rstweaks stats`:

| branch | meaning |
|---|---|
| `nothing-remembered` | the SIMULATE pass recorded no substitute for that resource |
| `revalidation-failed` | it did, but the substitute is no longer in storage in the amount needed |
| `not eligible` | SIMULATE pass, reuse switched off, or the probe is on |

```
worn-tool reuse: 12,441 scans avoided of 1,204,880 eligible (1.0%);
                 missed 1,192,439 nothing-remembered, 0 revalidation-failed; 1,204,880 not eligible
```

Those two miss counters distinguish the two live hypotheses in one reading. `nothing-remembered`
dominating means the SIMULATE pass is not recording what I think it records. `revalidation-failed`
dominating means storage is changing between the passes after all — which would also mean the
63-million-pair probe result needs re-reading.

No behaviour change. This version exists to make the next answer one command away instead of one
release away.


## 0.13.0

**The probe came back clean, so the cache is built.**

0.12.1 asked the question instead of assuming the answer. On a real 1M insanium craft:

```
substitution probe: 63,247,889 pairs, 63,247,889 agreed, 0 disagreed,
                    0 execute-without-simulate
```

Refined Storage runs every crafting iteration twice — `calculateIterationInputs` + `extractAll`
with `SIMULATE` to test it, then the identical pair with `EXECUTE` to do it — and `SIMULATE` does
not mutate internal storage. So the second `findWornTool` walks the same storage looking for the
same tool. It was **57.24% of this mixin's 37.03%** of the server thread in profile `IiXxJ4Mk4j`.

The EXECUTE pass now reuses the substitute SIMULATE chose. Half those walks are gone.

### Sixty-three million agreements is evidence, not a proof

So it is not trusted blindly. Before the remembered substitute is used it is re-validated against
internal storage with a single `O(1)` lookup, and every one of these falls through to the full
scan:

- a resource that was not in the remembered decision
- a substitute no longer present in the amount needed
- an EXECUTE with no SIMULATE in front of it

**The fast path can only ever return an answer the slow path would also have returned.**
`reuseSimulatedSubstitution` (default on) switches it off and restores the double scan exactly.

The probe **disables** the cache while it is on. With the cache serving the EXECUTE pass, the probe
would be comparing the remembered answer against itself and would report agreement no matter what,
and a measurement that cannot fail is not a measurement.

### Break-tested

A new differential runs the task engine with the reuse on and asserts `substitutionScansAvoided`
actually moved — **83 scans across 6 scenarios**. Without that counter the second run would just be
the rescanning run again, which is the trap `batchedSteppingMatchesSerial` already exists to avoid.

Returning the wrong half of the remembered pair makes a required test fail; restoring it makes all
30 pass.

### Not yet measured in game

The arithmetic says this removes roughly 28% of the durability path. The last four versions all had
sound arithmetic and moved the total by about a point and a half each, so treat that as a
hypothesis until a profile says otherwise.


## 0.12.1

**A probe for the biggest remaining win, instead of a guess at it.**

Profile `IiXxJ4Mk4j` confirms 0.12.0 did what it claimed:

| self time | 0.11.2 | 0.12.0 |
|---|---|---|
| `HashMap.hash` | 22.74% | 20.12% |
| `ItemResource.equals` | 11.90% | 9.95% |
| `ItemDurability.maxDamage` | 8.86% | **gone** |
| durability path total | 38.82% | **37.03%** |

Four versions now: 40.15 → 41.11 → 38.82 → 37.03. Every fix real, every fix worth about a point
and a half.

### Where the duplication actually is

The 2.0.9 bytecode of `InternalTaskPattern.step`:

```java
inputs1 = calculateIterationInputs(SIMULATE);
if (!extractAll(inputs1, internalStorage, SIMULATE)) return IDLE;
LOGGER.debug("Stepping {}", pattern);
inputs2 = calculateIterationInputs(EXECUTE);
extractAll(inputs2, internalStorage, EXECUTE);
```

**Refined Storage runs every iteration twice** — once to test it, once to do it. So the worn-tool
scan walks the task's entire internal storage twice for one iteration's worth of work, and
`SIMULATE` does not mutate storage, so the second walk is looking at the same world.

Caching the first answer for the second pass would halve the 37% this mixin costs.

### Why this is a probe and not the cache

`calculateIterationInputs` **takes the `Action` as a parameter**, so it is entitled to return
different resources for the two passes. Assuming it doesn't is precisely the mistake 0.11.2 made —
"almost every candidate is a completely different item" was true in general and false for the only
case that mattered.

So `substitutionProbe` (config, default **off**) measures the invariant instead. It changes no
behaviour: it compares the swap decision reached on SIMULATE against the one reached on EXECUTE and
counts agreements, disagreements, and execute-without-simulate. `/rstweaks stats` reports it.

A disagreement count of zero over a long real craft is what would justify building the cache.

### Proven to fire

The probe only writes on the EXECUTE half of a pair, so "switched on but never reached" and
"switched off" produce identical output — and this project has now shipped three things whose
presence was indistinguishable from their absence. A new gametest asserts the counters move *and*
that every pair is classified as one thing or the other: **89 pairs, 89 agreed** in the fixture.

It deliberately does not assert zero disagreements. That would pin the fixture's answer, not the
game's, and the game is the thing being asked.


## 0.12.0

**The hoist — and the correction to 0.11.2's premise.**

Profile `qRRh2NJvYs` (1M insanium, 0.11.2) settles the question 0.11.2 posed. Three versions of
constant-factor work on the durability path:

| | 0.11.0 | 0.11.1 | 0.11.2 |
|---|---|---|---|
| durability path total | 40.15% | 41.11% | **38.82%** |

It is the shape, not the constants.

### Where the time actually is

```
substituteWornTool                 38.82%  of the server thread
  findWornTool                     57.24%  of that  (22.2% of the thread)
    sameTool                       57.10%  of that
    usesLeft                       21.10%
  isDurable                        12.53%
```

with `HashMap.hash` still **22.74% self** and `ItemResource.equals` **11.90%** — JIT-inlined
`ItemResource`/`DataComponentPatch` hashing from the family-cache lookups.

### 0.11.2's reasoning was wrong for the case that matters

It added the item-first rejection on the grounds that *"almost every candidate is a completely
different item"*. That is true in general and **false for exactly the craft this code exists to
serve**: wearing a tool down fills the task's internal storage with many wear levels of the *same*
item. So the cheap reference comparison matches, and both family lookups run anyway. `sameTool`
stayed at 12.7% of the server thread.

### The fix

`sameTool` needs the **wanted** side's family on every comparison, and it cannot change across the
scan. `findWornTool` now resolves it once through the new `Durability.toolFamily` and passes the
token into a three-argument `sameTool`. Half of every family lookup in the hottest loop in the mod
was re-deriving a constant.

The two-argument form stays, and the interface defaults delegate to it, so any implementation that
does not override is exactly as correct as before and merely no faster.

Also: `maxDamage` takes a plain `get` on the hit path rather than `computeIfAbsent` — 8.86% self,
pure call volume from `isDurable`.

### Tested, and break-tested

A 169-scenario differential in `DurabilitySelfTest` asserts the hoisted and slow forms agree over a
matrix of wear levels, different tools, a non-durable item, and a same-item-different-components
pair — against the **real** `ItemDurability`, since the expensive half is `DataComponentPatch`
hashing and only Minecraft has one. The durability gametest goes 35 → 204 scenarios.

Dropping the `NOT_A_TOOL` guard makes exactly the 9 stone pairs diverge, which is how I know it
tests anything.

`FakeDurability` overrides the new methods **deliberately**. Left on the interface default it would
have delegated to the old path, and every test using it would have passed without once exercising
the new one.

### Found while breaking it

A failing suite with a wide matrix built a message over Minecraft's 1024-character component limit,
and `helper.fail` threw `IllegalStateException` inside the reporter — so a legitimate FAILURE came
out as a crash, at the one moment the diagnostic matters most. Now bounded, with the full list still
going to the log.


## 0.11.2

**The tool scan rejects on the item first.** With the cheaper costs around it gone, profile
`r3z1C0CsZx` put `findWornTool` at **25.85%** of the server thread. It walks the task's entire
internal storage calling `sameTool` on every resource, once per ingredient, per iteration — and
`sameTool` did two family lookups, each hashing and equality-comparing a `DataComponentPatch`.

Almost every candidate is a completely different item. `Item` is a registry singleton, so comparing
that first is a reference comparison and it rejects nearly everything before any hashing happens.
Sound because a family is derived from the resource with its damage removed, so two resources in one
family necessarily share an item — rejecting on a different item can never skip a real match.
`findWornTool` checks storage availability only *after* `sameTool`, so rejected candidates now cost
one reference comparison and nothing else.

### Where 0.11.1 actually landed

Both of its fixes worked and the total did not move, which is worth recording plainly:

| | 0.11.0 | 0.11.1 |
|---|---|---|
| `CompositeFilter.filter` | 6.46% | gone |
| `ItemResource.equals` | 24.03% | 11.40% |
| `findWornTool` | 1.43% | 25.85% |
| durability path total | 40.15% | 41.11% |

The expensive `isDurable` had been **masking** the scan. Removing it did not reduce the work, it
revealed where the work always was.

## 0.11.1

Three corrections, all of them from profiles of 0.11.0 rather than from reasoning.

### `isDurable` was going through the wrong cache — my regression from 0.10.1

Profile `8GrQL66Tfd` (insanium, so tools everywhere) against `K7rNc1lhrw` on 0.10.0:

| | 0.10.0 | 0.11.0 |
|---|---|---|
| `findWornTool` | 29.06% | **1.43%** |
| `containsAll` | 9.26% | 3.87% |
| `ItemResource.equals` | 6.19% | **24.03%** |
| `substituteWornTool` total | 43.35% | 40.15% |

The int-family change did what it was meant to — the storage scan collapsed from 29% to 1.4% — and
then the cost **relocated into the cache lookup itself**. `FAMILY` is keyed on `ItemResource`, so
every `get` hashes and equality-compares a `DataComponentPatch`, and 0.10.1 routed `isDurable`
through it.

`isDurable` only ever needed `maxDamage > 0`, which is keyed by `Item` — an identity hash on a
registry object. It goes back to that. Only `sameTool` needs the family, and now that the scan is
gone it is asked far less often.

Worth stating plainly: 0.10.1 claimed a win it did not deliver. Overall the durability path went
43.35% → 40.15%, not because the idea was wrong but because I put the expensive lookup on the
hottest call.

### Raising a logger's level cannot skip a log4j filter

0.11.0's `quietTaskLogging` set those two RS loggers to INFO. It ran, and the cost went **up**
(3.80% → 6.09%). The profile says why:

```
6.50%  Log4jLogger.debug
6.42%    Logger$PrivateConfig.filter
6.38%      CompositeFilter.filter
0.01%  LoggerConfig.log        <- nothing is actually written
```

Log4j consults the configuration-wide filter **before** the level check, so a pack with a filter
chain pays on every call whatever the logger's level is. Nothing was being written; all of it was
the decision not to write.

So the setting now does what its name always implied: a mixin redirects the six `LOGGER.debug`
invocations in those two classes to nothing. `require = 0` on both, so Refined Storage moving them
costs the optimization and not the launch. Warnings and errors are untouched.

### The durability guard now comes first

The 0.11.0 early-out still ran the consumed-list clear and the config read before deciding there
were no tools — 1.43% of the server thread on a compression craft, entirely to reach a `return`.
The cached answer is checked first now; on a tool-free pattern the whole handler is one field read.

## 0.11.0

Three things the last clean profile (`evmko3bHZl` — netherrack, batching off) pointed at, in the
order they appear in it.

### Batching is throughput-neutral now, which is what it always should have been

0.10.3 stopped batching from freezing a world by capping it per tick, but that was a bound bolted
on rather than the mistake fixed. The mistake was that a batch of N consumed **one** of Refined
Storage's `steps`, so it multiplied the work per tick instead of making it cheaper.

A batch of N now **credits the next N−1 calls** to `step()` and returns each of them immediately
without doing anything. So a batch costs N steps, exactly as N serial iterations would have, and
the iterations per tick are precisely what they were before — the same work, in one extraction
instead of N. It cannot repeat what happened, because the arithmetic no longer allows it.

That also puts `maxBatchedIterationsPerTick` in its proper place: a backstop and an emergency brake
if the crediting is ever wrong, rather than the thing standing between you and a stalled server.
Raised 8192 → 65536 accordingly, since blunting it no longer buys safety.

This is the fix I should have written first. Replacing `stepPattern`'s loop was the obvious way and
I rejected it as invasive; crediting gets the same result without touching a package-private return
type or reimplementing anything.

### Durability substitution stands down when there is nothing durable

`trySubstituteWornTool` runs twice per iteration and was **3.57% of the server thread on a craft
with no tools in it at all** — an allocation and a walk of every ingredient, every time, to conclude
there was nothing to do. Now decided once per task pattern.

Read off the **ingredient budget**, not the pattern layout, and the distinction is the point: the
planner allocates concrete resources per slot, so a pattern encoded with `crystal@0` can have
`crystal@50` in its budget. Checking the layout would miss exactly the substitution the mixin exists
to perform.

### Refined Storage's per-iteration debug logging

`InternalTaskPattern.step` and `AbstractTaskPattern.extractAll` carry six `LOGGER.debug` calls
between them, once per crafting iteration, ~10⁵ a tick under a multiblock crafter. In a pack with
debug logging enabled — All the Mods 10 is — those lines are formatted and written, not discarded.
`CompositeFilter.filter` alone was **4.85% of the server thread**, before the formatting or the I/O
behind it.

`quietTaskLogging` (default on) raises those two loggers to INFO. Only DEBUG on those two classes is
affected; warnings and errors from Refined Storage arrive untouched. It reaches into logging that is
arguably the pack's business, so it says what it did once at startup and can be switched off.

## 0.10.3

**Batching froze a world for 114 seconds. This is the bound that was missing.**

A netherrack craft — the first craft batching ever accepted — produced a single server tick of
114,516 ms. ModernFix's watchdog caught it, and the thread dump named the code without ambiguity:

```
java.util.HashMap.merge
  rstweaks$lambda$rstweaks$tryBatch$3
  java.util.LinkedHashMap.forEach
  rstweaks$tryBatch(InternalTaskPattern.java:641)
  rstweaks$batchStep
  InternalTaskPattern.step
  TaskImpl.stepPattern
```

### The mistake

`TaskImpl.stepPattern` is:

```java
int steps = stepBehavior.getSteps(pattern.getKey());
for (int i = 0; i < steps; i++) { pattern.getValue().step(...); }
```

**`steps` is Refined Storage's throughput budget for the tick** — a multiblock crafter hands it
around 10⁵. Batching made a single one of those calls do up to `maxBatchedIterations` (1024)
iterations, while still consuming **one** step. So the work per tick was multiplied by the batch
width instead of being made cheaper: up to 10⁸ iterations in a tick where there had been 10⁵.

Batching is supposed to buy *fewer extractions for the same work*. I removed the throttle instead.

It was invisible until now for the reason everything else has been: the crystal chain has
byproducts, so batching refused every pattern and did nothing at all. Netherrack was the first
chain it could accept, and it took the whole thing on the first tick.

### The fix

`maxBatchedIterationsPerTick`, default **8192**, refilled once per server tick and drawn down by
every batch across every task. When it runs out, batching stands down for the rest of the tick and
Refined Storage's own stepping continues, bounded by `steps` as it always was.

Deliberately lower than what serial does in a tick, so this cannot increase per-tick work under any
setting — it can only make some of that work cheaper. Raise it once there is a profile showing the
benefit and the tick cost side by side.

The right long-term hook is `stepPattern` itself, where a batch of N could consume N of `steps` and
be exactly throughput-neutral. That is a bigger change to a riskier place, and it is not what a
world that just froze needs first.

**If you are running 0.10.2 or earlier with `batchedExecution = true`, turn it off.** It is the
first condition in the mixin, so the path disables completely.

## 0.10.2

**Batching ran for the first time, and cost 1.33% to do nothing.** Profile `4JnFBQWZwg` is the
first with `rstweaks$batchStep` in it at all — proof the flag was on, where two earlier runs
believed to be batching tests were not. The craft was the crystal chain, so every pattern has a
byproduct and batching refused all of them exactly as designed. The rest of the profile is
unchanged from the batching-off run of the same craft, which is the right answer for a refused
craft: no benefit, no harm.

Except it was not free. `canBatch` walked every ingredient against every output and did a
durability lookup per input, **on every step**, to reach a conclusion that cannot change — the
layout is immutable and whether an item wears out is a property of the item. At a hundred thousand
steps a tick that is 1.33% of the server thread spent saying no. It is decided once per pattern
now.

### And the reason two runs were wasted

There was no way to tell whether batching was on. It refuses every byproduct pattern, so on a
crafting-tool chain it is *correctly* silent — which looks identical to being switched off. That
cost two profiling runs and a "wait, did I run it with batching enabled?" that I could not answer
from the log.

- The startup line now lists **batched execution** among the active features.
- `/rstweaks stats` reports iterations, batches and the average width — and says
  `batching on, nothing batched yet` when the count is zero, because zero is the interesting
  answer.

Shipping a feature whose presence is indistinguishable from its absence is the same mistake as a
differential test between two identical things, and this is the third time this project has paid
for it.

## 0.10.1

**A tool family is an integer now.** 0.9.1 cached `withoutDamage` and removed the `ItemStack`
allocation from every tool comparison; profile `K7rNc1lhrw` shows what that left behind:

```
19.37%  java.util.HashMap.hash                  (self)
11.80%  ItemDurability.maxDamage                (self)
 8.97%  java.util.AbstractCollection.containsAll
 5.79%  ItemResource.equals
```

`sameTool` was still doing `withoutDamage(a).equals(withoutDamage(b))`, and comparing two
`DataComponentPatch`es is that `containsAll`/`equals` pair. `maxDamage` did a map lookup on every
call. `findWornTool` asks both once per resource in the task's internal storage, per ingredient,
per iteration.

So each resource now resolves once to an `int` family — same item, same components except damage —
and `sameTool` is a lookup each plus an integer comparison. The distinction that matters is baked
into the family rather than recomputed: two differently enchanted tools land in different families,
because they are not interchangeable wear levels of one another.

Broken on purpose before being believed: making every tool share a family fails
`a pickaxe is not an axe` and `a differently-componented one is not interchangeable either` — the
two assertions that exist for issue #9.

### Where the durability fix actually stands

Measured properly this time, crystal craft on both sides, batching off on both:

| | 0.9.0 | 0.10.0 |
|---|---|---|
| `extractAll` | 70.63% | 48.97% |
| `substituteWornTool` | 68.53% | 43.35% |
| `findWornTool` | 62.71% | 29.06% |
| `ItemStack.<init>` self | 13.7–20.8% | gone |

An earlier draft of this file claimed that fix took the cost to nothing. It did not: that reading
compared a netherrack craft against a crystal one, and netherrack has no tools in it, so the code
being measured never ran. Two workloads, not two versions. The numbers above are the honest ones.

## 0.10.0

**The LP planner was gated out of the one case it is best at.** Requesting 320
`allthecompressed:netherrack_9x` in game gave "this request took too long to calculate, and was
cancelled". The log said why:

```
[rstweaks] LP planner declined allthecompressed:netherrack_9x:
  no byproducts and no cycle -- stock RS already plans this correctly
```

It does plan it correctly. It just cannot finish. The gate asked *does Refined Storage get this
wrong?* when the question that mattered was *can Refined Storage complete this?*

### Why the tree cannot, stated exactly

`CraftingTree.calculateIngredient`:

```java
long remaining = ingredientState.amount() * this.amount.iterations();
while (remaining > 0L) { ... }
```

**One loop iteration per expanded item.** A nine-times-compressed block is 9⁹ ≈ 387 million, so 320
of them is about 1.2×10¹¹ — and Refined Storage spends its five-second timeout on the *server
thread*, so the world stops for a hundred ticks before telling you no.

The solver is O(patterns). New `compressionProbe` measures it rather than asserting it:

```
tiers  requested   base stock       outcome      ms
9      320         plenty         9 patterns    0.71
9      1           plenty         9 patterns    0.93
9      1000000     plenty         9 patterns    0.40
9      320         none        no integer sol   0.40
```

One, 320 and a million all cost the same, which is the design's central claim holding in
measurement: the requested amount is a right-hand side, not a dimension of the problem. And with no
stock it proves impossibility in 0.4 ms — where the tree cannot answer at all, it can say *which
resource you are short of*.

### The gate now asks the right question

The planner also takes a request when its **expansion** — amount × base items per unit, computed
saturating over the pattern DAG — reaches `lpPlannerExpansionThreshold`, default 10 million.
Below that, nothing changes and stock Refined Storage keeps every craft it has today:

```
tiers  requested  expands to      gate
1      64         576             left to RS
3      64         46656           left to RS
5      320        18895680        TAKEN
9      320        123974556480    TAKEN
```

Two scenarios pin it, deliberately in both directions: a five-tier chain at 320 must be **taken**,
and a three-tier chain at 64 must be **left alone**, because a threshold that takes everything is
not a threshold. Raising the threshold out of reach makes the first fail, which is how I know it
discriminates.

### Credit where it is due

This came out of Nodrance's own notes on his LP planner, which the user passed along. His design
routes *most* crafts to LP behind a `shouldUseLpSystem` compatibility check — the opposite polarity
from ours, which only took what RS got wrong. His summary of what LP supports ("all crafts the
traditional algorithm can't calculate, that don't involve loops") is the shape ours should have been
aiming at, and his "if no solution exists, tell the user what's missing" is exactly what a timed-out
request should have given you.

## 0.9.1

**The profile said the bottleneck was us.** Spark `zyyN62neOS`, taken on a large craft with batching
off, breaks the 99% in `InternalTaskPattern.step` down for the first time:

```
 70.63%  AbstractTaskPattern.extractAll
 68.53%    rstweaks$substituteWornTool      <- ours
 62.71%      findWornTool
```

Self time is `ItemStack.<init>` at 13.7%, plus another 25% of component-map copying between
`ensureMapOwnership`, `verifyComponentsAfterLoad` and `isPatchSanitized`. Every earlier profile
attributed this cost to Refined Storage stepping one iteration at a time. It is not: **two thirds of
the server thread was our own durability substitution**, and phase 05 was aimed at the wrong target.

### What it was doing

`findWornTool` scans the task's whole internal storage calling `sameTool` on every candidate, and
`sameTool` called `withoutDamage` on **both sides of every comparison** — each one building an
`ItemStack`, mutating its component map, and rebuilding an `ItemResource`. `damage()` built an
entire stack to read a single integer, once per candidate.

So `withoutDamage` is now cached per `ItemResource` — a record, so it has real value equality;
keying that cache on `ItemStack` would silently make it an identity map that never hits, which is a
mistake this project has already paid for once. And `damage()` reads the component patch directly,
falling back to a per-item cached default only when the resource carries no damage at all.

### And `ItemDurability` had no tests

Which only became obvious while rewriting it. The task-engine scenarios install a `FakeDurability` —
they are about the planner and the executor, not about how damage is read — so the class that
answers this in game had no coverage. `DurabilitySelfTest` is a gametest against the real registry,
35 assertions, and its central one is **differential**: the patch-reading answer must equal what
`toItemStack(1L).getDamageValue()` says, so the slow path it replaced serves as the oracle. All 28
gametests pass.

No behaviour change intended and none observed. The next profile is what says whether it worked.

## 0.9.0

**Phase 05 executes.** `BatchedStepMixin` runs many iterations of one pattern in a single
extraction and insertion instead of Refined Storage's one-at-a-time loop — the loop that is 94–99%
of the server thread in both profiles of this pack. **Off by default** (`batchedExecution`), the
same posture `lpPlanner` shipped with, because this is the first thing in this mod that changes
*when items move* rather than how they are found.

### What it refuses to touch

Wider than the arithmetic strictly requires, because the cost of being wrong here is a player's
items:

- **anything with byproducts** — every container, every catalyst, every tool;
- **anything using an item that wears out** — `AbstractTaskPatternMixin` substitutes a worn tool
  from inside `extractAll`, and a batched path does not call it;
- **anything that consumes what it produces** — self-duplication, where iteration two needs what
  iteration one made.

Everything else falls through to Refined Storage's own stepping unchanged. A pattern it cannot
prove safe costs the optimization and nothing else.

### Two bugs found before it ever ran

**The budget was spent on a draw that was rejected.** The loop drew an iteration from the ingredient
budget, then checked whether the task's storage covered it, and broke out if not — having already
spent the draw. The committed budget would have been one iteration further on than the work actually
done, quietly removing ingredients from the rest of the plan. Found by re-reading the commit path,
not by a test; the draw is now applied only after the storage has been shown to cover it.

**`@Shadow` does not walk the class hierarchy.** `pattern` and `ingredients` live on
`AbstractTaskPattern`, and shadowing them from a mixin on the subclass fails at *apply* time — and
with `injectors.defaultRequire = 1`, that failure took the entire task engine down rather than just
the optimization. The superclass's own mixin now exposes them through `TaskPatternInternals`, the
same arrangement `WornToolAware` already uses. The injection itself is `require = 0`, because an
optimization that cannot apply should stand down, not crash somebody's pack.

### Verified in game, and made to fail first

A new gametest runs the whole task-engine suite — real crafting tasks, real task engine — with
batching switched on, and asserts the counter moved. That second half is the test: every scenario
already passes with batching off, so without it a green run would just be the serial run again.

Then the guard was removed on purpose. The suite failed with exactly the right words:

```
byproduct returned at the end: slag ended at 0, not the 16 the plan
accounts for (0 stored + 16 made - 0 used)  <-- ITEMS DESTROYED
```

Restored: 27 gametests pass, 15 iterations batched across 6 scenarios.

**What is still unproven:** the scenarios are small, so nothing here has batched at the widths a
real pack reaches, and none of it has run beside Cable Tiers or a multiblock crafter. That needs a
real launch, and the profile is the only thing that can say whether it was worth doing.

## 0.8.2

**Withdrawing yesterday's withdrawal.** 0.8.1 said batching a self-feeding pattern was futile
rather than dangerous. That is true of stock Refined Storage and **false of this mod**, and reading
`InternalTaskPatternMixin` while planning the executor is what turned it up.

That mixin ages a byproduct from the tool actually consumed, so a crystal comes back one step more
worn each iteration instead of being handed back at the damage the pattern was encoded with —
without it, a worn tool is a repair station. Batch N iterations and that ageing happens **once, for
N tools**: the progression collapses and the tool stops wearing out. With that mixin and without
this rule, a batch is a durability duplication glitch.

### And the rule would not have caught it

`crystal@0` and `crystal@1` are different `ResourceKey`s. They are the same **column** only after
`Pools` folds a tool's wear levels together, so the set intersection is empty in the space the
executor actually works in, and a wearing tool looks perfectly batchable.

So the API now makes the requirement impossible to miss rather than documenting it:

- `feedsItself(needs, produces)` is public and named, with the column-space precondition stated on
  it.
- `decide(needs, available, left, cap, feedsItself)` lets a caller that holds resource keys answer
  the question from `Durability` instead.
- The test asserts **both** directions: pooled into one column a wearing tool runs serially, and the
  same tool in resource space does *not* look self-feeding — which is the assertion that would have
  caught me writing the executor the obvious way.

Nothing executes yet; this is still the decision half. But it is the second time in two versions
that the interesting finding came from the interaction between this rebuild and a tweak this mod
already ships, rather than from Refined Storage.

## 0.8.1

**Phase 05, the decision half.** `BatchPolicy` answers one question: how many iterations of a
pattern may run as a single extraction and insertion. Nothing executes yet — this is arithmetic in
a plain JVM, and the executor that acts on it is the next slice.

The rule is one line: **a pattern whose inputs intersect its own outputs feeds itself across
iterations**, so a batch demands up front what only the previous iteration can supply. A worn tool
is the clearest case: iteration two wants the `crystal@1` iteration one handed back, and the plan's
ingredient budget lists it precisely because it expects that. Containers are not in that set and
batch happily — the empties come back from a different pattern on a different step — and the rule
tells them apart without knowing what either of them is.

### I had the reason for that rule wrong, and the test said so

I wrote this class claiming batching a self-feeding pattern could destroy items. Then I deleted the
rule to check the safety property caught it, and **it stayed green**. Working out why corrected the
reasoning:

An extraction is bounded by what the task is holding, so a batch can never take more than exists. A
catalyst batched against sixty-four crystals extracts sixty-four and returns sixty-four — exactly
where serial ends up. A worn tool's batch simply fails its extraction simulation and nothing
happens. **Futile, not dangerous.** The rule earns its keep by not paying for that failed attempt on
every step of every crafting tool, which is a performance claim, not a safety one.

So the docs now say that, and the suite says which assertion actually pins the rule: the one that
reads the decision's stated reason, not the property that looks like it should. The property still
earns its place — it pins that a batch never exceeds what serial could run, and gives up no
throughput on the patterns it *is* allowed to batch, over three thousand random patterns including
self-feeding ones.

**Where the real item-loss risk lives**, now that it is not here: a throw partway through a batched
extraction, on a path where `TaskContainer.step` treats any exception as completion, logs it, fires
the toast and drops the task's internal storage. That is the executor's problem and it is the thing
to be careful about next.

## 0.8.0

**Phase 04: the flows get an order.** A solved plan says "run this forty times and that twelve
times". It does not say *when*, and for anything with a cycle in it the order is the whole problem —
you must duplicate the smithing template before you spend it, and empty a bucket before you can fill
the next. `Scheduler` puts the time back: a backtracking greedy that takes the widest batch it can
afford right now, and rolls back to a narrower one when an ordering dead-ends.

Ported from Nodrance's `ExecutionPlanner`; see the new `ATTRIBUTION.md` for what was taken, what was
not, and what changed. **Nothing is wired to it yet** — no dispatcher, no task object, no
persistence. That is deliberate: his `LpTaskDispatcher.createSnapshot()` omits its own pending steps,
so a dispatcher task that outlives a save comes back holding items with no work left to do, and
there is no reason to inherit that risk before the arithmetic is trusted.

### The one thing that had to change in the port

Affordability reads the planner's per-iteration effect, which counts ordinary resources **gross** and
pooled ones **net**. A direct port charges the full thousand uses of a crystal to run one craft, then
finds 999 left and stalls — the same gross/net distinction 0.7.2 established, arriving again in a
place I did not expect it. That the ledger model had already made the distinction available is the
first time this rebuild has paid rather than cost.

### What the tests actually pin, stated exactly

The property is an **independent replay**: walk the emitted steps against the starting stock, and if
any column ever goes negative the order is wrong. Five hundred random container cycles plus the
hand-built cases — the cake cycle on three buckets, the seed duplicated before it is spent, a greedy
batch that is a trap, a plan nothing can start. 24 checks.

**And what they do not pin.** A mutation run put the score at 62%, and reading the survivors rather
than chasing them: the sort order, the batch sizes tried, and cycle detection are **heuristics**, and
mutating them keeps every correctness test green because the search still finds a valid order — it
just backtracks more on the way. Their cost claim is pinned instead: every random cycle must be
ordered within 32 attempts.

Two things I tried and could not make bite, said plainly rather than left implied. I built a scenario
meant to expose a rolled-back branch leaking its outputs into the search's inventory, and it did not
fail under mutation — my trace of when the leak matters was wrong, and I do not currently have a case
that proves the produced-side rollback is load-bearing. The step-merging path is likewise close to
unreachable: consecutive same-recipe steps outside a cycle turn out to be rare, because a batch takes
everything affordable in one go.

Backtracking itself *is* covered, and only because the mutation run said it was not: every scenario
before that one succeeded on its first choice, so `rollback` and `shrink` could both have been
deleted with the suite still green.

## 0.7.3

**The one thing no headless suite can ask.** 0.7.2 quietly promoted `ItemRemainder` to a production
path — `CraftingGraph` reads `Remainder.Holder` on the autocrafting thread now — and that adapter
had never been executed against a real item in its life. Every headless suite installs a fake, and a
fixture agreeing with itself is not evidence about the adapter.

So it is a gametest: `craftingRemaindersAreReadFromTheGame`, 17 assertions against the real item
registry. Milk, water and lava buckets leave a bucket; a honey bottle leaves a glass bottle; stone,
diamonds and an empty bucket leave nothing. A diamond pickaxe and a netherite axe leave nothing
either — wear is `Durability`'s question, and answering it in two places is how two rules start to
disagree. A resource that is not an item is refused rather than thrown at. And the cake case runs
end to end — registry to `ItemRemainder` to `PatternTransforms` — to see one slot with a fate rather
than an ingredient and an unrelated byproduct.

Broken on purpose before being believed: with the adapter always answering "nothing", 7 of the 17
fail, naming each container. Restored, all 26 gametests pass.

No behaviour change. `./gradlew runGameTestServer`, about a minute, no human.

## 0.7.2

**The planner now computes its effects through the ledger model.** `CraftingGraph.buildEffects` used
to read a pattern's three flat lists and apply four separate rules to them — an ingredient is
consumption, an output is production, a byproduct is production *unless* its class is a tool, and a
tool ingredient costs a wear step measured by hand. That is now one rule, a slot with a fate, and all
four fall out of subtraction. `ClassPools` is the bridge: the planner's resource classes and the
ledger's columns turn out to be the same structure seen twice, so a class becomes a column addressed
by its representative resource.

**Every emitted plan is byte-identical.** The 36 plans the planner suite produces were captured
before the swap and diffed after: no change, not one iteration count or requirement. That is the
result worth reporting, and it is a *refactor*, not a fix — the LP planner already handled
containers and catalysts correctly. What changes is that it now says so in one rule instead of four,
and the four were where the bugs used to live.

### Gross for ordinary columns, net for pooled ones

The one piece of real design here, and it is not arbitrary in either direction:

- **Gross is what working capital needs.** A catalyst is consumed and produced in equal measure. It
  nets to nothing, but you must still *own* one before the craft can run, and the gross figures are
  how it reaches `initialRequirements`.
- **Net is what a pool needs.** A tool column is denominated in crafts remaining, so a half-worn
  crystal can still run the recipe; charging the gross thousand would say it cannot.

The hand-written version encoded exactly this by skipping the tool byproduct. Both directions are
now proven load-bearing by breaking them: forcing gross everywhere makes four durability scenarios
decline, and forcing net everywhere loses the catalyst — `requisitions [] matching 'master_crystal',
expected 1` — and breaks self-duplication.

### One rule, one copy

`DurabilityClasses` loses 102 lines: its own `isWearAndReturn` and both `wearStep` overloads. The
wear-and-return rule now lives once, in `ToolPools`, and `DurabilityClasses` calls it. Two copies of
"is this tool actually handed back" is exactly the duplication that drifts, and the half that drifts
is the half nobody is running.

`ToolPools` stays as the standalone path — patterns without a graph, which is where phases 04 and 05
will live — while the planner uses `ClassPools`. Its docs now say which is which instead of
promising a merge that has happened.

### One behaviour difference, and nothing tests it

A pattern whose **output is an already-worn tool** used to be credited a full `maxUses`; it is now
credited what that tool actually has left. That is a correction — the old number would over-supply
the pool — but no scenario in the suite covers it, and I would rather flag it than let a green run
imply otherwise. Building a faithful scenario needs the wear-substitution semantics that
`durabilityAwarePlanning` still gates, so it belongs with that work rather than guessed at here.

`plannerCheck` still stays out of `check`: it is seconds rather than instant, which was always the
real reason, and re-typing this did not change it.

## 0.7.1

**The differential check, and what it caught immediately.** 0.7.0's ledger suite was built from
patterns written to exercise it. This one is built from nothing: `LedgerParitySelfTest` takes
`PlannerExecutabilitySelfTest`'s scenarios — the rice slimeball, the cake cycle, the fluid swap,
every one of them a case that was a real bug once — asks the **shipping** planner for a plan, and
audits it three ways. It runs in `plannerCheck`, not `check`, because it drags in the planner and
`Config`.

1. **The inference may not invent or destroy.** A fate is only a reattribution, so on any unpooled
   column the ledger's totals must equal the raw `PatternLayout` read the way Refined Storage reads
   it.
2. **The plan must be funded** — the rice-slimeball bug as arithmetic instead of a replay.
3. **The plan must produce what was asked for.**

It passed on the first run, with 30 checks over 10 real plans. **That pass was worthless**, and the
only reason we know is that the next step was to break the inference on purpose and watch it fail.
It didn't fail. Two different deliberate breaks passed.

The probe said why, and it is worth writing down: **every slot in every plan came back
`becomes = NOTHING`.** Not one fate was ever inferred. The container scenarios all need
`Remainder`, which had no implementation, so the ledger was agreeing with Refined Storage by
behaving exactly like Refined Storage. A differential test between two identical things is a mirror.

### So `Remainder` now exists

`ItemRemainder` reads `getCraftingRemainingItem` — the game's own answer to what a milk bucket
leaves behind — cached per item, installed beside `ItemDurability` at mod construction. Nothing
reads it yet, and it is installed anyway so the adapter is exercised by every launch instead of
only by the slice that wires the planner on.

**What it reaches:** vanilla crafting containers. Buckets, bottles, anything declaring a
`craftRemainder`. **What it does not:** a container returned by a *machine* — a crucible from a
smelter, an empty can — declares no such relationship anywhere, so those byproducts stay
unattributed, exactly as Refined Storage leaves them today. The headless fixture is held to the
same limit deliberately: it maps the bucket family and nothing else, because a fixture more
generous than the real adapter would be testing a capability this mod does not have.

**A remainder is a candidate, not a conclusion.** It is only ever used to match a byproduct the
pattern already lists, which is what makes it safe against the mods that roll dice when handing an
ingredient back — Refined Storage froze one draw at encode time, and if our answer disagrees with
it, nothing matches and the slot is simply consumed.

### And now the suite bites

With fates actually firing, the same deliberate breaks fail loudly:

- double-crediting a byproduct → four scenarios throw;
- dropping an unattributed byproduct → `two independent containers: the ledger and the raw layout
  disagree by {can=-40, crucible=-40}`, plus the funding check catching the same thing from the
  other side.

There is also a `fates > 0` assertion on the run itself now, so the day this suite goes back to
proving nothing, it says so instead of going green.

## 0.7.0

**The ledger model, phase one: the algebra.** Nothing in game changes in this version. This is the
core the autocrafting rebuild is meant to stand on, built and tested on its own before anything is
wired to it, because the alternative is discovering the model is wrong from a player's storage.

### The one mistake it fixes

A Refined Storage pattern is three flat lists — ingredients, outputs, byproducts — and that shape
throws away the only fact that matters: **which ingredient became which byproduct**. Once the link
is gone nothing downstream can recover it, so `CraftingState.addOutputsToInternalStorage` ignores
byproducts entirely while `InternalTaskPattern.step` faithfully returns them. The plan buys N
crystals; the craft hands N crystals back. That is where the 712-million Master Infusion Crystal
request came from, and five of the six autocrafting failures on the list are the same sentence with
different nouns.

The primitive is a **slot with a fate** — `(resource, amount, becomes)`:

| kind | goes in | becomes | net |
|---|---|---|---|
| consumed | 4 inferium | nothing | −4 |
| catalyst | master crystal | master crystal | 0 — free |
| worn tool | crystal@0 | crystal@1 | −1 use |
| container | honey bucket | bucket | −honey, bucket kept |

Four cases Refined Storage models four different ways, three of them wrongly. Here they are one
case, and **a catalyst needs no code at all**: it is the slot whose `becomes` equals its input, so
it cancels itself out of `Transform.net` and never reaches the constraint matrix.

### Pools, not item counts

A 1000-use crystal is not a thousand items and not a thousand recipes. It is one pool measured in
uses, and `Pools` says it in two methods: which column a resource pays into, and what one of it is
worth there. The wear step is then never assumed and never even written down — `crystal@0` at 1000
units becoming `crystal@1` at 999 costs one use *by subtraction*, and a recipe that burns five
costs five for the same reason. Assuming one would make such a tool last five times too long, which
reads as a working feature and is a duplication bug.

Nothing in that is about damage. Charge, blood and stored fluid are the same shape, so the mods
that keep power in a crafting ingredient become one implementation of `Pools` rather than a
permanent known gap.

A pool has to be **earned**, though, and `ToolPools` enforces it: uses are fungible and destruction
is not, so ten crystals with a hundred uses each cannot satisfy a recipe that eats one whole
crystal. One destructive pattern anywhere in the set and the family stays ordinary items.

### Conservation is the test

`initial + produced − consumed == final`. Every case above is a conservation failure wearing a
costume — upward is a duplication glitch, downward is items destroyed — so one property covers all
of them and the ones nobody has thought of yet. `./gradlew ledgerCheck`, wired into `check`: 66
assertions including two thousand random plans replayed a second, independent way.

### The part I do not trust yet, said plainly

`PatternTransforms` has to **infer** the ingredient→byproduct link, because the data to state it
does not exist. Three rules in order — same resource, same tool with fewer uses left, the game's
own crafting remainder — and anything they cannot explain stays exactly what Refined Storage
already thought it was. A failed inference costs an optimisation, never an item. Everything it
could not explain lands in `Result.notes()` rather than being swallowed: a planner that silently
declines is indistinguishable from one that was never installed, and this project has paid for that
twice.

`Remainder` has **no game-side implementation yet** — the default answers "nothing", which is the
model Refined Storage ships. So the container cases are proven against fakes and nothing else.

### The suite passed on its first run, which meant nothing

Two tests in 0.6.0 were green with the feature ripped out, so this one was mutation-tested before
being believed: `./gradlew mutationTest -PpitTarget=com.wraithhawit.rstweaks.ledger.*`. The first
run killed 74% and the survivors were real holes — a partial container return whose *consumed* half
nothing checked, a `totalsMatch` that only ever had to say yes, a remainder credited without the
pattern handing it back, the equal-wear case that would make a tool immortal. Nine more scenarios
later it kills 88%, test strength 98%, and the six survivors are equivalent mutants (a label
string, a `> 0` on a delta that is never zero).

### Licence

`LICENSE` gains a standing grant: the maintainers of any mod this project mixins into — Refined
Storage, Cable Tiers, Step Crafter, Functional Storage, Sophisticated Core, and anything targeted
later — may take any of this code, ship it under their own licence, and **name themselves the
author**. No attribution required, irrevocable for anything already published. The best outcome for
a tweak here is that it stops needing to exist; the licence should not be what stands in the way.

## 0.6.0

**Per-slot control, on both halves.** Asked for after
[RS Insert Export Upgrade](https://modrinth.com/mod/rs-insert-export-upgrade) (1.20.1) — and it
turned out to be two different features, because that mod's checkmarks are on **your inventory
slots** while the obvious reading of "toggle individual slots" is the **filter slots**. Both were
wanted, so both are here.

### Filter slots carry a mode

Each of the nine gets **both / insert only / export only / off**, drawn as a 10×10 marker in the
slot's bottom-right corner — the same corner and the same geometry Refined Storage uses for
`renderExportingIndicators`, so it looks like something an Exporter would do. Clicking the marker
cycles it.

This closes a real gap. Until now the two side buttons applied to the whole list, so with both on
every listed resource was pinned at its amount, and *"file away my cobblestone, and keep me stocked
with torches"* needed two grids to say. Now it is one screen.

Getting a click onto the marker took a **seam rather than a new gesture**. A filter slot's clicks
are all spoken for: plain click opens the amount screen, shift-click clears the filter, click with
an item sets it. `AbstractBaseScreen.canInteractWithResourceSlot(slot, mouseX, mouseY)` is Refined
Storage's own hook for precisely this — returning false over the marker hands back those ten pixels
and leaves every other click in the slot behaving exactly as it did.

One floor worth knowing: Refined Storage's `ResourceAmount` refuses an amount of zero, so a listed
entry always keeps **at least one**. "File away every last one" is `BLOCK` mode with the resource
left off the list, which is what BLOCK is for.

### Player inventory slots carry a tick

A **"choose slots"** side button turns your inventory into a picker: click a slot to include or
exclude it from auto-insert, and excluded slots are washed red. This is the RS Insert Export Upgrade
model, and it is finer-grained than `inventoryInterfaceInsertFromHotbar` — protect two hotbar slots
and free the rest, rather than all or nothing.

A **mode rather than a modifier**, deliberately. Those are real inventory slots you need for
dragging items into filters, and quietly changing what a plain click does to somebody's own
inventory is how a screen eats a stack. With the mode off, the screen behaves exactly as before.

The mask and the config answer different questions and neither overrides the other: the config is a
server-level policy about whether this feature may touch hotbars at all, the mask is the player's
choice of which slots within what the server allows.

### Plumbing

Two new fields on the component and one new packet. Refined Storage's property mechanism would have
carried these for free, but a property is an `int` and the inventory mask is thirty-six bits, so
half of it would not fit and the other half would be two properties pretending to be one. The menu
data record now carries the whole state rather than a field at a time, since the per-slot settings
are not data slots and this is their only delivery. Defaults preserve the old behaviour exactly:
every filter slot `BOTH`, every inventory slot allowed.

### Both new tests were wrong first

Worth writing down, because they passed. `perSlotModesSplitTheTwoDirections` and
`anExcludedSlotIsLeftAloneAndStillCounts` were both **green with the feature ripped out**, because
the scenarios happened to produce identical results either way: the keep budget absorbed the
excluded stack whichever path ran, and the mode test's two entries had nothing to do in either
configuration. A test that cannot tell the two apart is not a weak test, it is a green light for
nothing at all.

Rewritten until the controls failed. The excluded slot now holds sixty-four against a keep of
sixteen, so an unrestricted pass would file forty-eight of them; the mode test now puts iron below
its amount with stock available (an unrestricted pass would top it up) and gold above its amount (an
unrestricted pass would file it away), with a third ordinary entry so a pass that did nothing at all
cannot pass by accident. 25 gametests pass.

## 0.5.2

**A grid worn in a Curios slot now works.** Reported from the world during the 0.5.1 test pass:

> there's curios slots where we can put the grids in to keep them out of the inventory and the
> pickblock doesn't work from there

It didn't, and neither did the Inventory Interface. Every scan in `iface/` walked
`player.getInventory()`, and a grid in a Curios slot is not in it — so a player who had tidied their
grid away out of the inventory, which is the entire point of a Curios slot, had silently switched
both features off.

**Refined Storage already models this and we were not asking.** Its Curios integration registers a
`SlotReferenceProvider` through `RefinedStorageApi.addSlotReferenceProvider`, and RS keeps a
`CompositeSlotReferenceProvider` that knows about all of them — `InventorySlotReferenceProvider` is
merely the one it starts with. The API lets anyone *add* a provider and nobody read the composite
back, so getting at it takes two accessor mixins: `@Invoker` for the proxy's `ensureLoaded`, and
`@Accessor` for the impl's field. Universal Grid reaches the same field through the same pair, which
is a fair sign this is the seam rather than a way around one.

All three scan sites — the ticker, block pick's grid lookup, and the client-side "is a packet worth
sending" check — now ask Refined Storage where the grids are instead of assuming. The consequence
is bigger than Curios: a backpack or trinket integration nobody here has heard of works too, and
this mod still names neither Curios nor Universal Grid, for the same reason the supported-item list
is ids rather than classes.

Client-safe, and not by luck: Refined Storage runs this same composite on the client for its own
open-grid keybinds (`useSlotReferencedItem`), so every registered provider is already expected to
answer for a client player.

**One thing got better on the way.** The insert pass used to protect the grid's own slot by
comparing an index; it now asks the slot reference `isDisabledSlot`. That is the question the method
exists to answer — it is how a menu greys out the slot its item came from — and a grid in a Curios
slot correctly answers false to every inventory index, having none to protect.

**Testing.** Curios is not in the dev run, so `aGridOutsideTheInventoryStillWorks` exposes the grid
through a provider of our own registered into the same composite: a provider is a provider, and
Curios has no special status in it. What that tests is the thing that was actually wrong — whether
the pass asks or assumes. Confirmed to fail with the inventory scan put back (64 kept where 16 was
asked for). 23 gametests pass.

**Decided, not deferred.** The configuration screen still opens on sneak + right-click *in the air*,
so a grid in a Curios slot has to come out to be configured. That is the intended behaviour, settled
on the test pass that reported the Curios gap: configuring is a thing you do once, and the setting
rides on the item, so it survives the round trip. A keybind would avoid the round trip — Refined
Storage and AE2WTLib both use one to reach slot-referenced items — and is not worth a keybind
nobody asked for. Do not re-raise this as a bug.

## 0.5.1

**Block pick no longer works in spectator**, and 0.5.0's claim that nothing else does this was
wrong.

**AE2WTLib has had network pick block for a long time.** `MinecraftMixin` injects into
`Minecraft.pickBlock` at `INVOKE_ASSIGN` on `Inventory.findSlotMatchingItem`, captures the locals
with MixinExtras `@Local`, and fires on the same `i == -1` seam ours does — and, like ours, does
not cancel:

```java
if (player.getAbilities().instabuild) return;
if (player.isSpectator()) return;
if (i != -1) return;
AE2wtlibEvents.pickBlock(itemstack);
```

That second line is the guard we did not have. **`getAbilities().instabuild` is false in
spectator**, so the creative check does not cover it, and a spectator could pick blocks out of any
network they were carrying a grid for, from anywhere they could see. Now refused in the client
injection to save the round trip, and refused on the server because that is the side that decides.

**There is deliberately no gametest for it.** A gametest's mock player is `GameTestHelper$2`, an
anonymous subclass that overrides `isSpectator()` to return false whatever the game mode is set to:
`getGameModeForPlayer()` reports `SPECTATOR` while `isSpectator()` reports false, on the same
object, in the same statement. The test that found this passed for the wrong reason, and a test
that cannot enter the branch it names is worse than no test — it claims the coverage. The class
javadoc says so, so nobody adds it back. 22 gametests pass.

**Two differences from AE2WTLib, neither changed here.** Their packet carries the `ItemStack` the
client computed; ours carries the `BlockPos` and derives the item from the server's own copy of the
block, which is the difference between a request that can be checked and one that has to be
trusted. And theirs is opt-in per terminal — a `PICK_BLOCK` data component, default off — while
ours is a global config, default on. Ours is the only network pick block an RS grid has, so
default-on is the right side of that trade; theirs sits beside a mod that already has several ways
to do it.

**They coexist, imperfectly.** Both mods inject into `Minecraft.pickBlock` and neither cancels, so
a player carrying both an RS grid and an AE2 terminal with pick block switched on gets a stack from
each. Nothing is destroyed — our destination is chosen before anything is extracted and refuses
rather than overwriting — but two stacks arrive where one was asked for. Left alone rather than
guessed at: the fix depends on which one somebody wants to win, and their default-off makes the
collision unlikely to be hit by accident.

## 0.5.0

**Block pick, out of the network.**
[refinedmods/refinedstorage-quartz-arsenal#4](https://github.com/refinedmods/refinedstorage-quartz-arsenal/issues/4)
— the other half of the pair 0.4.0 came from. Upstream files it separately because it is a
different interaction with a different failure mode, which is still true; it is also three lines of
setup once the grid resolution from 0.4.0 exists.

In survival, vanilla's pick-block key can only reach into your own inventory. `Minecraft.pickBlock`
calls `findSlotMatchingItem`, gets `-1`, and does nothing. **That `-1` is the whole feature.** With
a Wireless or Portable Grid on you, that case now takes a stack out of the network instead — which
is what [refinedstorage#1381](https://github.com/refinedmods/refinedstorage/issues/1381) asked for,
in the words of somebody tired of opening a grid every thirty seconds while building.

Deliberately **not** tied to the Inventory Interface filter. That filter is a standing instruction
about what to do while nobody is looking; this is a key you pressed about the block in front of
you, and making it obey a filter would mean the answer to "why did that not work" is a screen you
have to go and read. RS1 also asked for a mode that *maintains* a stack of the picked block — that
already exists, as 0.4.0's auto-export with an amount, so it is not duplicated here.

**Why a mixin, and why it does not cancel.** NeoForge 21.1 fires no event around `pickBlock`: the
client event package has `InputEvent.Key`, `InputEvent.MouseButton` and
`InputEvent.InteractionKeyMappingTriggered`, and none of them is this. Watching the key ourselves
in a client tick would race vanilla for the same press and get the modifier and screen-open rules
wrong on the way. So: a `@Inject` at `HEAD` of `Minecraft.pickBlock` that **does not cancel**. The
case it acts on is the one where vanilla does nothing at all, so letting the original run to its
own no-op costs a few comparisons and means this injection cannot be the reason pick block stops
working — including for whatever else is patched into it.

The conditions are re-derived in the injection rather than captured out of vanilla's locals. That
is the trade: a local capture is pinned to where vanilla happens to put its branches, and the whole
method is nine lines of getters a `HEAD` injection can read for itself. Nothing is written and
nothing is decided there — the derivation only settles whether a packet is worth sending.

**What the server does not take on trust.** The packet carries *where the player was looking*, not
what they want, and the server derives the item from its own copy of the block. A client that sent
an item stack could ask for a stack of anything, with any components on it, and the only thing
between that and the network would be whether the network happened to contain it. On top of that:
the chunk must be loaded, the block must still be there, and the player must be able to reach it —
`canInteractWithBlock`, the same question that decides whether the block could be broken, with a
one-block allowance because the client decided what it was looking at several frames ago. Then the
ordinary rules: the grid must resolve, the player must hold `EXTRACT`, and the extraction is
charged at Refined Storage's own rate.

**The one thing worth getting right.** Vanilla's creative equivalent, `Inventory.setPickedItem`,
overwrites the selected hotbar slot when it cannot find anywhere to move its contents. In creative
that costs nothing; here it would destroy something you mined. So the destination is chosen
**before** anything is extracted, and a full inventory refuses the pick rather than performing it.
No stack leaves the disk that has nowhere to land.

**Testing.** `BlockPickGameTest`, four tests, three of them about refusing: no stock, out of reach,
and a full inventory. The full-inventory one was confirmed to fail with vanilla's overwrite put
back. The portable-grid fixture the 0.4.0 tests built is now shared as `PortableGridFixture`. 22
gametests pass.

**Not covered by any of them:** the client mixin, which a dedicated server never loads. Its target
and all three of its shadowed fields were checked against the remapped runtime class
(`private void pickBlock()`, `hitResult`, `player`, `level` — all present with matching types), and
mixin application failure is loud here because every config is `required` with `defaultRequire 1`.
But applying is not running. This one needs a client launch to be believed.

## 0.4.1

**The keep budget now counts the slots auto-insert refuses to take from.**

0.4.0 walked the inventory once, skipping protected slots — the hotbar, the held item, the grid
itself — before spending the per-entry keep budget. So "keep 16" meant sixteen *outside* those
slots plus whatever was inside them: ten iron in the hotbar and sixty-four in the bag settled at
twenty-six, not sixteen.

That is worse than an off-by-some, because auto-export counts the **whole** inventory. The two
halves of one feature disagreed about the same number, and the maintained stock settled wherever
the hotbar/bag split happened to be rather than at the amount on the filter slot.

A first pass now spends the budget against the protected slots, and the insert pass files away
what is left over. The protected slots are still never taken from; they just stop being invisible.

`theKeepBudgetCountsTheHotbarItWillNotTakeFrom` pins it, and was confirmed to fail against 0.4.0's
code — 26 where 16 was asked for. 18 gametests pass.

## 0.4.0

**The Inventory Interface.** The first thing this mod adds rather than makes cheaper:
[refinedmods/refinedstorage-quartz-arsenal#3](https://github.com/refinedmods/refinedstorage-quartz-arsenal/issues/3),
implemented here. A Wireless or Portable Grid now files items away and restocks you while it sits
in your inventory.

The issue has been open upstream since RS2 started, with no assignee and an `art-necessary` label.
That label is the reason it never moved, and it is not a reason it had to stay still: Refined
Storage already ships every sprite this needs except two, and the filter screen it needs is one
Refined Storage draws for its own Exporter.

**The design.** Upstream asks for auto-insert, auto-export, and "filtering options". The RS1
requests it cites say what people actually wanted —
[refinedstorage#2573](https://github.com/refinedmods/refinedstorage/issues/2573) ("a filter in the
portable grid so it automatically inserts items from the world") and
[refinedstorageaddons#4](https://github.com/refinedmods/refinedstorageaddons/issues/4) ("a bag
that auto-inserts item drops, whitelist/blacklist") — and both are about a grid that works **while
it sits in your inventory**, not while its screen is open.

So: **one** filter list of nine slots, and the amount on a filter slot means the same thing in
both directions — how many of that resource to keep on you.

| | |
|---|---|
| Auto-insert, `ALLOW` | files away the listed resources, keeping the amount on each |
| Auto-insert, `BLOCK` | files away everything *except* the listed resources, which it leaves alone entirely |
| Auto-export | tops the listed resources back up to their amount |

Auto-export reads the list whichever mode the filter is in, because topping up is a whitelist by
nature. That is what makes `BLOCK` + export coherent rather than contradictory: "these are mine,
do not put them away, and keep me stocked with them". Both toggles on is a maintained stock — 64
cobblestone on you, no more, no less, which is the "maintains a stack in your inventory" behaviour
RS1 asked for, falling out of the two toggles rather than needing a third mode.

**What it refuses to touch.** Auto-insert is the half that can ruin an afternoon: a `BLOCK` filter
is a standing instruction to file away everything you did not name, and what you did not name
includes the blocks you are placing. It never takes the held item, armour, the offhand, or another
grid, and by default nothing from the hotbar at all. The hotbar rule is the one that is a judgement
rather than a safety rail, so it is the one that is config
(`inventoryInterfaceInsertFromHotbar`, off).

**Compatibility, and why it is by id.** Supported items are an allowlist
(`inventoryInterfaceItems`): Refined Storage's Wireless and Portable Grids, Quartz Arsenal's
Wireless Crafting Grid, Universal Grid's Wireless Universal Grid, and the creative variants of
each. The obvious class test — anything extending `AbstractNetworkEnergyItem` — also catches the
Wireless Autocrafting Monitor and the Wireless Security Manager, neither of which has an inventory
to interface with, and would silently adopt every future network-bound item an addon adds. Ids
also mean nothing here references a class belonging to either addon, so neither becomes a
dependency, neither needs a mixin config, and an id from a mod you do not have simply matches
nothing. It is config, so an addon grid nobody here has heard of is a line in a file rather than a
build.

**The gesture is an event, not a mixin, and that is the same compatibility argument.** Sneak +
right-click in the air is free on all four items: crouch-right-click on a *block* is already taken
(a wireless grid binds, a portable grid places) but that is `RightClickBlock`, a different event.
`AbstractNetworkEnergyItem.use` would have been the obvious mixin target and would have covered
Refined Storage's own grids — but an addon is free to override `use`, and Quartz Arsenal and
Universal Grid both do, so the mixin would have silently not run for exactly the two mods this was
meant to support. `PlayerInteractEvent.RightClickItem` fires ahead of `Item.use` for every item
there is, so there is nothing to override.

**How little of it is ours.** The state is a data component attached to *Refined Storage's* stacks
— any component can be attached to any stack, so carrying our configuration on somebody else's
item needs no fork and no cooperation. The screen extends `AbstractResourceContainerMenu`, which
means Refined Storage's `ResourceSlotChangePacket`, `ResourceSlotAmountChangePacket` and
`PropertyChangePacket` already serve it: all three dispatch on the base class, so editing a filter
slot, setting its amount and toggling a button all arrive without this mod registering a packet.
The filter-mode and fuzzy buttons are Refined Storage's own widgets driven by its own property
types. The background is its `generic_filter.png`, which is why the filter is nine slots and one
row. Storage is `NetworkItemHelper.createContext` for the wireless grids — the same call
`WirelessGridItem.use` makes, so range, binding and dimension are decided by Refined Storage — and
`DiskInventory.resolve` for the portable one. Energy drains through Refined Storage's own paths at
its own configured rates, and a transfer whose fee cannot be paid does not happen.

Two sprites are ours to draw. Two things are registered, both in our namespace: a data component
and a menu type. Uninstall this mod and a Wireless Grid is a Wireless Grid.

**One new mixin**, an `@Accessor` for `PortableGridBlockItem.type`. Refined Storage answers
"creative or not" three ways and all three are out of reach: `isCreative` is private,
`createEnergyStorageInternal` (the one that substitutes `CreativeEnergyStorage`) is private, and
the public `createEnergyStorage(stack)` deliberately reports the stored number rather than the
effective one. `getRenderInfo` does answer it but reaches the client-only storage repository on the
way. Without the accessor a creative Portable Grid reads as flat and the feature silently does
nothing on it.

**Cost.** One pass every `inventoryInterfaceIntervalTicks` (20 by default), staggered across
players by entity id. A pass on a player carrying no configured grid is 41 data-component lookups;
nothing resolves a network, touches a disk, or allocates until a stack turns up carrying a
configuration that is switched on.

**Testing.** `InventoryInterfaceGameTest`, four tests, against a real creative Portable Grid with a
real disk: `ALLOW` keeps the amount and files the surplus, `BLOCK` protects the list and files
everything else, export tops up to the amount and stops, and an empty `BLOCK` filter — "file away
everything", the most destructive setting this has — leaves the held item and both grids alone. 17
gametests pass.

The first draft of those tests was wrong in a way worth writing down: it set the inventory up and
waited for `PlayerTickEvent` to arrive. It never does. A gametest's mock player is placed in the
level and logs in, but is never ticked — the server reports zero players for the whole run. The
tests did not fail loudly; they reported that the feature did nothing, which is indistinguishable
from the feature being broken, and it took a probe on an unrelated listener to tell the two apart.
They now post a real `PlayerTickEvent.Post` on the real bus, which still fails if the listener was
never registered.

**Block pick** (`quartz-arsenal#4`) is deliberately not in this. Upstream lists it under #3 as an
extra but files it separately, and it is a different interaction with a different failure mode.

## 0.3.1

**The external storage provider layer now has gametest coverage. It had none.**

No gameplay change.

**How the gap was found.** Grepping `Mixing <X>Mixin` out of a `runGameTestServer` log and
diffing against the mixin package: 22 of 32 mixins actually loaded during a full gametest run.
Mixin *application* failure was already covered — every config sets `"required": true` with
`injectors.defaultRequire = 1`, so a mixin that cannot apply crashes the server at startup. The
invisible case is a mixin that applies cleanly and never runs, which is exactly what Cable Tiers
once did to our injections.

Of the ten that never loaded, five targeted Refined Storage itself and were simply never reached.
Three of those are now covered.

**`ExternalStorageProviderGameTest`** builds a real network — creative controller, Crafting Grid,
an External Storage block facing a vanilla chest — and checks that items serve and insert through
the composite, and that the chest's contents really change. A plain chest is enough: Refined
Storage builds the composite over *every* registered provider factory regardless of what the
target block offers, so a chest yields an item provider and a fluid provider side by side.

The assertion that matters runs the same request with `skipMismatchedStorageTypes` on and off and
demands the identical answer. An optimization that changes an answer is not an optimization.

| now covered | why it mattered |
|---|---|
| `CompositeExternalStorageProviderMixin` | a `@Redirect` on **both** `extract` and `insert` — the same layer where a stale index destroyed an in-progress extraction in every build up to 0.2.55 |
| `ItemHandlerExternalStorageProviderMixin` | declares "serves items only" |
| `FluidHandlerExternalStorageProviderMixin` | declares "serves fluids only" |

`ExtractionSelfTest` does not cover any of this — it builds over `ItemHandlerExtractableStorage`,
one level below the provider.

**The first draft of the equivalence test was wrong, and failed against a working mixin.** It
extracted an *item*. `CompositeExternalStorageProvider.extract` returns on the first provider that
yields anything, so an item request against a chest short-circuits at the item provider and never
reaches the fluid one — there is nothing to skip. Extracting a *fluid* is the faithful case, and
the one actually measured in the wild: Refined Types' Network Energizer pulling power every tick
made every item inventory on the network answer a question it could not possibly answer.

**Still uncovered**, and honestly so: `PreviewCraftingCalculatorListenerMixin` and
`TimeoutableCancellationTokenMixin` target Refined Storage and are still never loaded by any test.
The rest are client-only mixins (which a dedicated server never loads) or addon mixins for mods
that `stageGameTestServerMods` deliberately does not stage.

All 13 gametests pass.


## 0.3.0

**Mutation testing, and the four test gaps it found.**

No gameplay change. The mod behaves exactly as 0.2.125 did; everything here is test
infrastructure and the tests it justified.

`./gradlew mutationTest -PpitTarget='<glob>'` runs PIT over a package. It breaks our own
code on purpose — flips a comparison, deletes a call, changes a constant — and reports
which breakages no test catches. That is a measurement of the safety net, not of the mod:
it can never catch a mixin regression or a pack-integration break, which is where most of
this mod's real bugs have come from. Those stay the gametests' job.

**Three obstacles, all documented in `build.gradle` so they are not rediscovered.**

1. PIT cannot drive a `main()` that calls `System.exit` — that kills its minion JVM. So a
   JUnit source set now sits in `src/test` beside the `Headless*Check` gradle tasks, which
   are unchanged and still the thing you run day to day.
2. PIT's class globs sweep in the mixins, and loading a mixin outside a Mixin environment
   kills the coverage minion with a bare `UNKNOWN_ERROR`. Needs an explicit exclusion.
3. `pitest-junit5-plugin` pulls JUnit Platform 1.9.2, and PIT prepends its own launch
   classpath, so the old `ReflectionUtils` wins over the one the tests compile against and
   discovery dies on `NoSuchMethodError`.

`HeadlessBackoffCheck`, `HeadlessPatternOrderCheck` and `HeadlessTraceCheck` now expose a
`run()` returning a `Result` — the shape `PatternOrderSelfTest` and `MaxCraftableSelfTest`
already used — so their 145 scenarios can be measured. Their `main()` methods print and
exit exactly as before, and all five gradle `*Check` tasks report identical scenario counts.

**What it found.** Four gaps, all now closed, and **no code defects** — everything it
pointed at was correct, merely unguarded:

- `Rational` had **no direct tests at all**, only whole-plan scenarios. Those work almost
  entirely in whole numbers, so the two things the class exists for — exact fractions across
  simplex pivots and a canonical sign — were barely exercised. Nothing ever built a
  `Rational` with a negative denominator, so both negations in `of()` could be deleted
  silently. 50% → 76%.
- `BranchAndBound` could **drop the push of its second child** and still return a valid
  plan, just a costlier one. `4x+5y>=13, min 2x+3y` is the discriminating case: the up
  branch gives `[4,0]` at cost 8, and the true optimum `[2,1]` at cost 7 only exists down
  the other side. 64% → 84%.
- `SlotBackoff` could **drop `applyCostFloor` on the slow-*success* branch** — the branch
  this class was written for, per its own 2026-08-23 measurement. The existing 96 scenarios
  use elapsed times small enough that the ladder already dominates the floor, so removing
  the floor changed none of their numbers.
- `BudgetedCancellationToken` never checked that **`cancel()` reaches the delegate**, and
  every scenario started its clock at zero, where `now - startedAt` and `now + startedAt`
  are the same number. 73% → 100%.

**Left open, deliberately.** `Simplex`'s pivot that drives a still-basic artificial out of
the basis survives mutation; a probe showed it executes *exactly once* across the whole
planner suite and deleting it fails no test, so the guarantee it exists to provide is
unverified. Constructing an LP where skipping it corrupts the plan is real work and was not
faked. `Rational.ceil()` and `toDouble()` have zero callers — dead code, not a coverage gap.
`PlanMatrix` (48%) and `LpCraftingPlanner` (32%) are not cheap wins: their entry points take
a `CraftingGraph.Graph` built from RS `Pattern`/`TaskPlan` objects, so raising them means
authoring new crafting scenarios, not building a harness.

49 JUnit tests across 9 classes; 71% of mutants killed across planner, backoff, pattern and
gate. Not wired into `check` — see the note there on why a score is a tool, not a gate.


## 0.2.125

**Two of our tweaks got upstreamed, so they now stand down on their own.**

Ultramega shipped both of the optimizations we wrote against his mods:

| his release | his changelog | ours it replaces |
|---|---|---|
| Step Crafter `1.21.1-0.1.7` | "Improved Step Requester performance by adding a timeout on failed requested crafts" | step requester backoff |
| Cable Tiers `1.21.1-0.6.14` | "Improved performance of sided input" | tiered autocrafter lookup |

This is the outcome we wanted. It also left a problem, because both releases fall *inside*
our declared version ranges, so nothing refused to load — our mixins simply kept applying on
top of his.

### Ours did not sit beside his; it replaced him

Both mixins inject at `HEAD` and cancel. On 0.6.14, `findSidedInputPatternState` computes our
answer and returns, so his rewritten body never executes — including whatever correctness
work went in with it. That method had a duplication bug fixed as recently as 0.6.12, which is
not a method to be shadowing by accident.

### And the Step Crafter one actively defeated his

0.1.5 and 0.1.7 both null-check the result of `PatternResourceContainerImpl.get(slot)`, so our
redirect returning null was safe in both. What changed is where the branch *goes*:

```
0.1.5:  ifnull -> skip this slot, continue
0.1.7:  ifnull -> failedTaskTimeouts.remove(slot), continue
```

In 0.1.7 a null means "this slot is empty, clear its timeout". Our redirect returns null for
exactly the slots we are sleeping — so on every sleeping tick we cleared his backoff for the
slots that were failing. His timeout could never accumulate while ours was installed. No
crash, no log line, the two fixes quietly cancelling into one. Reported to him.

### The gate

`AddonMixinGate` is an `IMixinConfigPlugin` on the two addon configs. `requiredMods` in
`neoforge.mods.toml` already decides whether they load at all, so only the version is in
question by the time it runs, and it reads that from `LoadingModList` — mixin plugins run
during class transformation, where `ModList` means nothing, as the comments in `RSTweaks`
have said for a long time.

**Older versions keep our fix, deliberately.** Not every ATM10 install is up to date and this
mod has to stay drag-and-drop; raising the minimum versions instead would have been less code
and would have broken exactly the people we most want it to work for.

**An unreadable version applies the mixin and warns.** Guessing the other way would silently
withdraw an optimization the user believes is running, and this mod's whole stance is that it
fails loudly instead — the same reasoning as `required=true` on the configs.

### The comparison is hand-written, and that is the part worth doubting

These are strings like `1.21.1-0.6.14`, and both obvious approaches misplace `0.6.9`: string
order puts it *above* `0.6.14`, and Maven's rules treat everything past the dash as a single
opaque qualifier. Either mistake leaves the mixin applied on precisely the release that
supersedes it, silently. So `isAtLeast` reads each run of digits as a number and compares
those, which also makes a `+build` suffix harmless.

`UpstreamGate` is free of Minecraft, NeoForge and Mixin types so this is testable in a plain
JVM — new `gateCheck`, 18 assertions, wired into `build`. As ever it cannot show the plugin is
wired in or that Mixin calls it; that needs a launch against a real Step Crafter.

### Reporting

The startup line and chat message no longer claim a stood-down tweak is running, which was
already the rule for an absent mod and is now the rule for a superseded one. The plugin logs
which tweak stood down, for whose version, quoting his changelog line — so the log says the
fix was upstreamed rather than leaving a silent gap where an optimization used to be.


## 0.2.124

**0.2.123 bounded how *often* the freeze happened. It did not remove it, and the very first
profile said so.**

The escalating automation budget capped at `craftingCalculationTimeoutMs` — RS's own 5,000ms — so
the top rung of the ladder was still a full five-second freeze. The log caught it climbing on one
repeated request:

```
  200ms wasted calculating 64x kubejs:allthemodium_solar_sail_package
  400ms   "
  800ms   "
1,600ms   "  -- 1,519,575 tree nodes
3,200ms   "  -- 3,096,759 tree nodes
5,005ms   "  -- 2,980,768 tree nodes
```

Every rung behaved exactly as designed, and the design was wrong at the top. **Making a freeze
rarer is not the same as fixing it**, and shipping the first without noticing the second is the
kind of thing a "much better!" profile hides.

`stepRequesterCalculationMaxBudgetMs` (default **1000**, `0` restores RS's timeout) gives
automation its own ceiling. Worst case an automated calculation can now cost is **twenty ticks**,
once per backoff cycle, instead of a hundred.

**What that costs, stated plainly:** a craft that genuinely needs more than a second to *plan*
will never be started by automation. It stays perfectly craftable by hand — a player request is
not bounded by this at all — and `N long calculations cut short` in `/rstweaks stats` is what
tells you it is happening. Raise the setting if automation stops keeping something in stock;
that trades freeze length back for planning depth, deliberately.

`backoffCheck` 90 → **96**, including a case that walks the ladder rung by rung against the
figures actually observed in game.

## 0.2.123

**The five-second freeze is fixed, not rationed.**

Everything before this reduced how *often* the world froze. This bounds how *long* it can freeze
at all.

Refined Storage gives every crafting calculation `TIMEOUT_MS = 5000` — five seconds **of the
server thread**. `craftingCalculationTimeoutMs` has been able to lower that since 0.2.112, and
was deliberately never lowered, because a cancelled calculation reports `MISSING_RESOURCES`,
indistinguishable from "cannot be made" — so cutting it globally silently refuses crafts that
would have worked.

**That reasoning holds for a player.** Someone clicking craft is waiting on an answer, and a
wrong "no" is a bug they cannot diagnose.

**It does not hold for automation.** A Step Requester is not waiting on anything; it asks again
on a schedule regardless. Refusing it once costs a retry. Freezing the world for five seconds
costs everyone. Measured 2026-08-23: one slot spent the full 5,000ms and 4,574,498 tree nodes to
conclude that a resource with no pattern behind it still had no pattern.

So `stepRequesterCalculationBudgetMs` (default **200**, `0` disables) applies **only** to Step
Requester calculations. Player-initiated crafts keep the full budget, untouched. Worst case for
automation drops from 5,000ms to 200ms — **four ticks**.

### Why this is not the permanent cap that once broke late-game crafts

`rstweaks-cap-is-not-a-proof` records a cap that blocked crafts with no path back. This one
**escalates**: a slot cancelled *on our budget* gets double next time, up to
`craftingCalculationTimeoutMs`, so a genuinely large craft is planned after a few attempts rather
than never. Any cheap success resets it to the start.

Two properties make that safe, and both are pinned by tests:

- **Only our own budget escalates.** A calculation that failed on its own merits gets nothing
  extra — otherwise every impossible craft would climb to the cap and automation would be back to
  freezing the world.
- **The ceiling is RS's own timeout.** Automation never gets more than a player would.

`BudgetedCancellationToken` also consults the token it wraps, so ours is only ever an *additional*
bound — anything that would have stopped the calculation before still does.

New chat line: **`N long calculations cut short`** — each one a five-second freeze that did not
happen.

`backoffCheck` 73 → **90**, including the token driven on an injected clock so expiry is
deterministic, and a case asserting a delegate cancellation is *not* mistaken for a budget expiry.
Confirmed to fail with the budget check removed.

## 0.2.122

**Groundwork for the memoization idea — and the reason that idea is not being shipped.**

### The memo, investigated and rejected as unsound

The obvious fix for `allthemodium_essence` running out 736,899 times in one calculation is to
remember that a `(resource, amount)` request already failed and skip re-exploring it. Reading
`CraftingTree` closely, that is not safe:

- Alternatives all start from the **same** parent state — `child()` does `parentState.copy()`,
  and while cycling alternatives the parent keeps its own state, so a failed alternative's
  extractions are discarded.
- But on final failure, `cycleToNextIngredientOrFail` **adopts** the failed child's state.

So a failure can be recorded deep in a branch that had already extracted heavily, and later
consulted from a state holding *more* of the relevant resources — where the same request would
have succeeded. The memo would then refuse a craft that works, reporting `MISSING_RESOURCES`,
which is indistinguishable from "cannot be made". No error, no log line, just a recipe that
quietly stops being offered.

That is exactly the failure recorded in `rstweaks-cap-is-not-a-proof`, and it is not worth a
throughput win. A sound version needs the memo keyed on a fingerprint of the crafting state,
which costs more than it saves on the path it would run on.

### What ships instead: measure the branching factor

The other explanation for 822,202 nodes on one resource is that it is reached through many
**distinct patterns**, each exploring its own failing subtree. `Pattern` identity is a per-item
UUID, so two pattern items encoding the same recipe are two genuine alternatives to the
calculator — and deduplicating *those* is provably sound, because identical layouts produce
identical subtrees.

Whether that is what is happening here is a question with an answer, so the trace now reports it:

```
822,202 nodes (87%) making allthemodium:allthemodium_nugget  via 12 patterns
```

One pattern and the line stays quiet; many and it says how many. `>=64` means it stopped
counting, which is answer enough.

This is deliberately a measurement rather than a fix. Three times this session a number was
inferred instead of observed and was wrong; deduplication is only worth building if the count
says duplicates exist.

`traceCheck` 17 → **20**.

## 0.2.121

**Two fixes the first real traces exposed within minutes of shipping 0.2.120.**

**1. The percentages were against the wrong denominator.** Counts only cover nodes visited after
the detail threshold, but the percentage was computed against the *total* node count. A
calculation that had just crossed the line reported:

```
104,327 tree nodes
  4,315 nodes (4%) making allthemodium:allthemodium_nugget
```

That resource was not 4% of anything — it was ~99% of the 4,328 nodes the breakdown actually
saw. The number was arithmetically correct and completely misleading, which is the worst kind.
Percentages are now relative to observed nodes, and the header says so when the breakdown is
partial: `948,510 tree nodes (breakdown covers the last 12,004)`.

**2. The threshold was set above the thing it was meant to show.** The same session produced
stalls of **91,807** and **97,967** nodes that got no breakdown at all, sitting either side of a
104,327-node one that did — and all three turned out to be the identical problem. A threshold
above what you are trying to see is worse than no threshold.

Now `traceDetailThresholdNodes`, default **20,000**, down from a hardcoded 100,000.

`traceCheck` 14 → **17**, with the new case pinning the denominator specifically.

### What the traces found

Worth recording, because it is the first thing in this investigation that points at a fix inside
Refined Storage rather than around it:

```
1,243ms wasted calculating 64x kubejs:allthemodium_beam_package -- 948,510 tree nodes
  822,202 nodes (87%) making allthemodium:allthemodium_nugget
   23,330 nodes  (2%) making allthemodium:allthemodium_ingot
        0 nodes  (0%) making mysticalagriculture:allthemodium_essence  -- ran out 736,899 times
```

`ingredientsExhausted` fires only when `patternRepository.getByOutput(resource)` is **empty** —
no pattern in the network produces it at all. That is a static fact for the whole calculation.
Refined Storage rediscovers it **736,899 times in a single request**, re-walking the nugget
subtree each time, because `CraftingTree` memoises nothing between branches.

## 0.2.120

**Breaking down the five-second stall, instead of only timing it.**

Refined Storage answers a stalled calculation with `MISSING_RESOURCES` and nothing else. No
resource, no indication of where the time went, and the same answer it gives for "this genuinely
cannot be made". Acting on a stall has therefore meant guessing — three times, wrongly, in this
session alone.

The information was already there. `CraftingCalculatorListener` — which RS drives on every
calculation — calls `childCalculationStarted` exactly once per node the tree visits, and
`ingredientsExhausted` whenever a branch gives up, naming the resource it ran out of. Counting
those needs no new work, only somewhere to put the numbers.

`traceSlowCalculations` (default on) logs, for any calculation past
`stepRequesterSlowCalculationMs`:

```
[rstweaks] 4,999ms wasted calculating 64x alltheores:tin_ingot -- 1,412,880 tree nodes
[rstweaks]   903,114 nodes (64%) making alltheores:tin_ingot  -- ran out 12,044 times
[rstweaks]   288,410 nodes (20%) making alltheores:raw_tin
[rstweaks]   ... and 9 more resources
```

That turns an unactionable stall into a named resource and a place to look.

**What makes it safe to leave on.** The node counter is one `long` increment on a path RS
already runs. The *per-resource* breakdown is a map write per node, which is not free — so it
does not begin until a calculation has visited **100,000 nodes**. An ordinary craft finishes far
below that and pays only the increment. Past it, the calculation is already pathological and the
overhead is irrelevant against the seconds it is about to burn. The resource name is passed as a
supplier rather than a string, so an `ItemResource` is never even formatted below the threshold.

That guard is the whole reason this ships enabled, and it is exactly the kind of thing that
breaks later without anyone noticing — the feature keeps working, it just quietly starts costing
a map write on every craft. So `traceCheck` pins it: **14 assertions**, wired into `build`,
confirmed to fail **5 of 14** with the threshold removed, including the one asserting the name
supplier is never invoked.

A stall that reports *fewer* than 100,000 nodes is itself a finding, and says so explicitly
rather than printing an empty table: it means the time went somewhere other than tree search,
and every branching-factor theory is wrong for that case.

## 0.2.119

**The timeout counter added one version ago never counted anything.**

0.2.118 tested `elapsedMs >= Config.craftingCalculationTimeoutMs` exactly. Over a full soak it
reported nothing at all, while the very same line read `4,999ms session peak`.

A calculation that burns the entire budget measures *just under* it. RS polls its cancellation
token rather than interrupting the thread, so the check lands slightly before the wall, and the
elapsed nanoseconds are then truncated by integer division into milliseconds. 5,000ms of budget
reliably measures as 4,999.

Now allows 50ms of tolerance. That is not padding to make the number look busy — nothing else in
this workload lands within 50ms of the ceiling by chance, and the alternative is a diagnostic
that reads zero forever while the thing it measures is happening.

Worth recording as a pattern rather than a one-off: this is the third time this session a number
has been derived instead of measured, and been wrong. The first was reading a log line's
frequency as a call rate; the second was concluding calls were cheap because a threshold never
fired. Both times the fix was to count the thing directly — and this is the failure mode of
*that* fix, a counter whose own condition was never checked against a real observation.
`4,999 < 5,000` is exactly the sort of thing a test with a real distribution would have caught
and a hand-written assertion never would.

## 0.2.118

**Reporting fix. No behaviour change.**

0.2.117 printed the session-wide slowest calculation beside per-window deltas, which produced
lines like:

```
187 craft calculations (0.69ms mean, 5,000ms slowest, 129ms total)
```

187 calls totalling 129ms cannot contain a 5,000ms call. `slowest` is a running maximum and a
maximum cannot be subtracted the way the other counters can, so it was never a per-window figure
— it is the whole session's peak. That is precisely the "made 'since last report' untrue for half
the line" flaw this class's own javadoc records having fixed once already.

It is now labelled explicitly:

```
187 craft calculations (0.69ms mean, 129ms total; 5,000ms session peak)
```

And the question the peak was being misread to answer — *is it still hitting the ceiling?* — gets
a counter that genuinely is a delta: **`N hit the calculation timeout`**. Compared against
`craftingCalculationTimeoutMs` rather than a literal 5000, so lowering that setting keeps the
line meaningful.

Worth reading closely when it appears. A cancelled calculation reports `MISSING_RESOURCES`, which
is indistinguishable from "cannot be made" — so every count here is also a craft that may have
been refused when it would have worked given more budget.

## 0.2.117

**The root cause, three versions late.** Everything before this rationed the cost. This removes
the thing that created it.

Refined Storage 2.0.9 keeps each output's candidate patterns in a `PriorityQueue` and reads them
back with:

```java
holders.stream().map(holder -> holder.pattern).toList()
```

**`PriorityQueue` guarantees ordering only for its head.** Java documents that its iterator and
spliterator traverse in no particular order. So the first pattern really is the highest priority
and *everything after it is in raw heap-array layout* — a function of the sequence patterns
happened to be added in and whichever sift operations followed.

That is not cosmetic, because `CraftingTree.calculateChild` consumes that list **in order**,
returns on the first pattern that succeeds, and explores every failure to exhaustion while
copying the whole crafting state at each node. An arbitrary order is an arbitrary cost.

And it explains the question that started this: *why did the same Step Requesters, asking for the
same things, only start timing out after the patterns moved?* Moving patterns into one provider
re-adds every one of them, rebuilding every per-output heap in a new sequence. Same patterns,
same count, same recipes — new search order. Measured: **0.199 → 20.249 ms/tick**, with some
calculations going from sub-millisecond to burning RS's entire 5,000ms timeout.

`sortPatternsByPriority` (default on) sorts properly. Two design notes:

- **Priority alone would fix nothing.** `List.sort` is stable, so equal priorities keep their
  encounter order — the heap-array order we are escaping — and in an ordinary network every
  provider sits at priority 0. The tiebreak *is* the fix.
- **The tiebreak is the pattern's UUID, not an insertion counter.** RS's own later builds use
  insertion order; that still reshuffles when a pattern moves between providers, which is
  precisely the event that caused this. A UUID is intrinsic and persisted, so a pattern keeps its
  place in the search order regardless of where it has lived.

The resulting order is arbitrary but *fixed*. That is the point: cost stops depending on
insertion history, and **provider priority becomes a real lever** — raising one now genuinely
searches it first, instead of merely owning the head of a queue nobody reads in order.

Reaching RS's private `PatternHolder` record uses the documented seam — a mixin implementing an
interface of ours — because `setAccessible` is refused across RS's module.

New `patternOrderCheck` (**29 assertions**), including a deliberate reproduction of the JDK
behaviour itself, so if a future JDK ever orders its iterator the suite says so and this mixin
can be deleted. Plus a **gametest against RS's real repository**, because only a running game
applies the mixins — confirmed to fail with the sort disabled:
`insertion sequence 1 produced a different search order`.

That gametest earned its place immediately. `PatternHolderAccess` was first written inside
`com.wraithhawit.rstweaks.mixin`, which compiled, unit-tested and built perfectly, then threw
`is in a defined mixin package ... and cannot be referenced directly` at class-load time. Mixin
owns that package. The interface lives in `..pattern` now, as `CraftingGridResultSlotAccess`
already did.

New chat line: **`N pattern lists reordered`**, counting only lists whose order actually changed.

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
