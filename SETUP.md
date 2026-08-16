# Moving rstweaks to another machine

## What's in this bundle

| | |
|---|---|
| `src/` | all rstweaks source, including the LP planner and solver |
| `libs/` | RS 2.0.9 + 10 addon jars — required to compile, must match the pack exactly |
| `gradle*`, `*.gradle`, `gradle.properties` | build files and wrapper |
| `README.md` | full write-up: every optimization, measurements, analysis tooling |
| `SETUP.md` | this file |
| `nodrance/` | his modified RS jar, decompiled `lp` package, and the design chat |
| `rstweaks-common.toml` | current tuned config — copy into the instance's `config/` |
| `rstweaks-0.1.0.jar` | last built jar, drop straight into `mods/` |

Deliberately **not** included:

- `worlds/` (5.2 GB) — the profiling world copy
- `build/`, `.gradle/`, `decompiled/` — all regenerable
- The ATM10 instance itself (35.9 GB)

## First build on the new machine

```powershell
cd <wherever you put rstweaks>
.\gradlew.bat build
```

The first run downloads NeoForge and decompiles Minecraft: several minutes plus
roughly **3 GB** of downloads. If the cabin connection is poor, copy
`C:\Users\<you>\.gradle` from the desktop first — that skips the entire download.
It is the single biggest time saver and the only thing here that really depends on
bandwidth.

Needs a **JDK 21**. Gradle's toolchain will fetch one automatically, but that is
another download; if the desktop has one at `C:\Program Files\Java\jdk-21.x`,
installing the same version up there avoids it.

## Three gotchas

**1. The junction will break.** `saves\survival-rstweaks` in the ATM10 instance is not
a real folder — it is a directory junction pointing at
`F:\Downloads\rstweaks\worlds\survival-rstweaks` on the desktop. Copying the instance
copies a link to a path that will not exist, and the world will appear broken or
empty. Either skip it, or copy the real folder from `rstweaks\worlds\` and drop it
into `saves\` as an ordinary directory.

**2. You probably don't need to move the instance.** If the cabin machine already
has All the Mods 10 installed, just add `rstweaks-0.1.0.jar` to its `mods/` and
`rstweaks-common.toml` to its `config/`. Confirm the pack has **Refined Storage
2.0.9** — every mixin is pinned to `[2.0.9,2.1.0)` and will refuse to load
otherwise, which is deliberate.

**3. Don't install `nodrance/refinedstorage-neoforge-2.0.1(2).jar` into the pack.**
It is a downgrade from 2.0.9 and rstweaks will not load alongside it. It is here as a
reference to port from, not to run.

## Current state

`lpPlanner = false` in the config. My linear planner is written and verified against
synthetic patterns but was never confirmed working on a real network, so it is
switched off. The four measured optimizations — Step Requester backoff, Cable Tiers
lookup, uncraftable cache, pattern-plan copy-on-write — are all active and are what
took the world from 5.29 to 19.98 TPS.

## Where the work resumes

Port Nodrance's `lp` package (in `nodrance/decompiled/`) to RS 2.0.9. The full
analysis is in this thread and the plan file, but the short version:

- Only **3 API gaps**: ojAlgo (replaceable with the `planner/Simplex` +
  `planner/BranchAndBound` already in `src/`), `CancellationToken.timeRemainingMillis()`,
  and `PatternRepository.getPriority(Pattern)`
- Only **6 hook points** outside his new package
- **No structural drift** between RS 2.0.1 and 2.0.9 — his `calculation` package is
  identical to stock
- Port his `RecipeSanitizer`/`RecipeAnalyzer` **first**. His pruning is what keeps
  the LP small enough to solve; without it `lexicographicMinimum()` does one solve
  per recipe and will not finish.
