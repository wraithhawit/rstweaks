# Attribution

Work in this repository that came from someone else, what was taken, and under what terms.

## Nodrance — LP autocrafting prototype

`com.wraithhawit.rstweaks.ledger.Scheduler` is a port of the ordering search from Nodrance's
`ExecutionPlanner`, part of his linear-programming autocrafting prototype for Refined Storage.

**What was taken:** the algorithm. A backtracking greedy that repeatedly asks which recipes are
affordable right now, prefers the ones sitting on a cycle, applies the widest batch it can, and
rolls back to a narrower one when an ordering dead-ends. The candidate ordering, the
`[all, half, one]` batch attempts, and the merging of consecutive steps outside a cycle are his
design decisions, kept because they are good ones. The insight that a plan with a cycle in it has
to duplicate the seed before spending it — "you might use up your only smithing template before
duping it" — is his framing of the problem.

**What was not taken:** everything around it. His `LpStepPlanCalculator`, `LpCraftingSolver`,
`LpFuzzyExpander`, `RecipeSanitizer` and `LpTaskDispatcher` are not here. This project has its own
solver (`planner/PlanMatrix`, `Simplex`, `BranchAndBound`, `PlanSimulator`) and its own model of a
recipe, so the port is the search only.

**What was changed, and why:**

- Re-typed onto integer columns and plain maps instead of `SanitizedRecipe`/`ResourcePool`, so it
  holds no Minecraft or Refined Storage types and runs in a plain JVM.
- Affordability reads the planner's per-iteration effect, which counts ordinary resources gross and
  pooled ones — tools, and later charge or stored fluid — net. A direct port charges the full
  thousand uses of a crystal to run one craft, and then stalls.
- Cycle membership is plain reachability rather than his `RecipeAnalyzer`, because a pruned subgraph
  is dozens of recipes and the quadratic version can be read and believed.
- A search budget replaces the cancellation token, and running out of it is reported **as a budget**
  rather than as impossibility. A scheduler that silently gives up is indistinguishable from one
  that was never installed.

**Licence.** His jar carries Refined Storage's MIT licence, which permits this with the notice
preserved. This file is that notice.

**Also worth sending back to him:** `LpTaskDispatcher.createSnapshot()` returns `Map.of()` for
patterns and `List.of()` for completed patterns, and omits `pendingSteps` entirely. Refined Storage
restores a task through `new TaskImpl(snapshot)`, so a dispatcher task that outlives a save comes
back holding its items with no work left to do. He was mid-refactor and said so; this is what that
looks like from outside.

## Refined Storage

This mod is an addon. It reads Refined Storage's API and patches its bytecode through Mixin; it does
not redistribute it. Refined Storage is MIT licensed and remains under its own terms — see the
`LICENSE` file's section 4.
