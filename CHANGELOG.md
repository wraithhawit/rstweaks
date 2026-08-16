# Changelog

The version prints at startup (`[rstweaks] vX.Y.Z loaded ...`) and in the chat join
message, so a test result can always be tied to an exact build.

Patch digit bumps on every build handed over for testing.

`VERSIONS.txt` is the short form of this file — one or two lines per version. Both are
maintained; this one carries the reasoning, that one is the index.

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
