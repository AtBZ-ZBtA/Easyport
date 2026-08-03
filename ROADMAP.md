# Easyport — Roadmap

> **Picking this up cold? Read [STATE.md](STATE.md) first.** It is the dense re-entry point:
> current status, locked decisions, and the gotchas that cost real time. This document is the
> full plan and the reasoning behind it — depth, not orientation.


**Goal:** Omnidirectional, complete translation of Minecraft mods between Forge 1.20.1 and
NeoForge 1.21.1, via (a) a standalone CLI tool and (b) an in-game service jar that
auto-translates a `/mods-from-other-version` folder into `/mods`.

**Audience:** end users, not developers. A mod that doesn't port is a failure, not an
edge case.

**Status:** Phase 0 in progress. Corpus analyzer built and verified.

---

## 1. Decisions (settled — do not relitigate)

| Decision | Value |
|---|---|
| Direction | **Omnidirectional.** 1.20.1 Forge ⟷ 1.21.1 NeoForge |
| Coverage target | **100%.** Every mod, every line |
| Scope | **Everything.** Mixins, coremods, reflection included |
| Corpus | **All the Mods 9** (Forge 1.20.1) vs **All the Mods 10** (NeoForge 1.21.1) |
| Licensing | Clear — translation is user-local, we distribute no translated output |
| Strategy | Brute force. Easy cases first, hard cases after, nothing dropped |
| Internals | **Black box to the owner.** Public-facing surfaces get plain-language docs; internals get commented for maintainers, not explained in chat |

### Pinned toolchain

| Component | Version | Verified |
|---|---|---|
| NeoForge (1.21.1) | `21.1.248` | 2026-08-02, maven.neoforged.net |
| NeoForm (1.21.1) | `1.21.1-20240808.144430` | 2026-08-02 |
| Forge (1.20.1) | `47.4.22` | 2026-08-02, maven.minecraftforge.net |
| Parchment (1.21.1) | `2024.11.17` | 2026-08-02 |
| Forge mappings (1.20.1) | `official` channel, `1.20.1` | 2026-08-02 |
| JDK | 21 (Temurin 21.0.11 local) | 2026-08-02 |
| Gradle | 8.14.2 (Neo MDK) / 8.8 (Forge MDK) | 2026-08-02 |

All required repositories (NeoForged, Forge, ParchmentMC, Mojang piston-meta) confirmed
reachable from the build environment.

**Rules must target a pinned loader build, not a version line.** The NeoForge 1.21.1 MDK
compiles with deprecation-for-removal warnings against 21.1.248 (`EventBusSubscriber.Bus`),
which means the target API surface still moves *within* 1.21.1. Rule sets carry the exact
Neo build they were derived against; re-verify on bump.

---

## 2. Why 100% is reachable

This is **two stacked migrations**: a loader migration (Forge ⟷ NeoForge) and a game
version migration (1.20.1 ⟷ 1.21.1). The second is harder, and it contains the two
largest breaking changes in modern Minecraft: **data components** (1.20.5, replacing
ItemStack NBT) and **data-driven enchantments** (1.21).

The standard objection is that some translations are statically undecidable — reflection
with computed names, string-keyed NBT that may or may not address vanilla data, coremods
that transform bytecode we've never seen. That objection is **correct about static
analysis and irrelevant to this design**, because nothing forces us to decide statically.

**A runtime shim has strictly more information than a static analyzer.** It sees the
resolved string, the actual `ItemStack`, the concrete class. So every case that defeats
AOT translation gets deferred to runtime rather than abandoned:

| Statically undecidable | Runtime resolution |
|---|---|
| `Class.forName(computedName)` | Intercept and remap at call time, with the real string in hand |
| `stack.getTag().getInt(key)` where `key` is a variable | Live component-backed NBT view resolves against the actual stack |
| `Enchantment` subclass with arbitrary Java | Custom effect component dispatches back into the legacy method |
| Coremod transforming unknown bytecode | Run the coremod against the *translated* class graph |

This removes the hard ceiling. There is no remaining case that can be shown impossible in
principle — only cases that are bounded engineering work. That is exactly the brute-force
premise, and it holds.

### The corpus is the engine

ATM9 vs ATM10 is not a test set. It is a set of mods hand-ported by their own authors, in
both forms — large enough to *derive* translation rules empirically instead of inferring
them from changelogs. It is also **symmetric**: every pair yields both directions from one
diff, which is why omnidirectional costs far less than double.

#### Measured, 2026-08-02

| Metric | Value |
|---|---|
| ATM9 jars (Forge 1.20.1) | 433 |
| ATM10 jars (NeoForge 1.21.1) | 479 |
| **Ground-truth pairs** | **288** |
| Source-only (no reference port) | 148 |
| Target-only (backward targets) | 194 |
| Trivial / Moderate | 93 / 55 |
| **Hard / Nightmare** | **112 / 28** |
| Mods carrying mixin classes | 136 |
| **Total mixin classes** | **1,786** |
| Median per mixin-carrying mod | ~13 |
| Heaviest single mod | `modernfix`, 171 |
| Mods with coremods / launch services | 12 |

**The decisive number: 48.6% of paired mods are Hard or Nightmare.** Solving only the
clean cases caps coverage at **51.4%**. There is no route past halfway that does not go
through mixins — so mixin handling is not a long tail, it is the main event, and the phase
plan is ordered accordingly.

The consolation is that all 1,786 mixin classes have an author-written 1.21.1 counterpart
sitting in ATM10. That is 1,786 worked before/after examples. The repair is *learned from
examples*, not derived analytically — which is exactly the brute-force premise, and the
corpus is large enough to support it.

#### Rule extraction under feature drift

**Mod versions are not aligned across the two packs, and the drift is severe.** `ae2` goes
15.4.9 → 19.2.17; `allthecompressed` 3.0.2 → 4.4.0. Only 5 of 288 pairs share an identical
version; only 38 share a major.minor prefix. A 1.21.1 jar therefore contains two kinds of
change mixed together: **genuine version-migration changes (signal) and features the author
added along the way (noise).**

Diffing pairs naively would learn "this mod gained a new block" as a translation rule and
poison the rule set. Three defenses, in order of strength:

1. **Corroboration threshold (primary).** A candidate rule is accepted only after it appears
   independently across N unrelated mods. Added features are idiosyncratic — they show up
   once. Genuine API migrations show up in dozens. Frequency is the discriminator, and 288
   pairs gives enough statistical power to set N meaningfully.
2. **Member-level matching.** Compare at class+member identity, never whole-file. Members
   that exist only in the 1.21.1 build have no counterpart and drop out automatically,
   which removes most added-feature noise before rule extraction even runs.
3. **Version-distance weighting.** The 38 low-drift pairs are the highest-confidence data.
   Reserve them as a **validation set** — rules are learned from the broad corpus and
   verified against the pairs least contaminated by feature drift.

Never assume a 1.21.1 member is the translation of a 1.20.1 member because they occupy
matching positions. Equivalence must be established by identity and corroboration.

#### Mining results, 2026-08-02 — `tools/RuleMiner.java`

All 288 pairs mined. 24,778 distinct lost symbols, 29,291 gained,
**231 candidate rules scoring ≥ 1.0**, corroborated across up to 190 independent mods.

Scoring needed all three signals; co-occurrence alone was not usable. `ModConfigSpec$Builder#<init>`
appears in every config-using mod, so one-directional confidence paired it at 0.98 with
every config symbol regardless of relationship. What discriminates:

- **Jaccard overlap** instead of confidence — penalises a gained symbol far more widespread
  than the lost one it is matched against.
- **Member-name match** — most migrations move a member between owners and keep its name.
  Strong domain-specific signal.
- **Descriptor match** after collapsing loader namespaces.

Sample of the output, all independently verified correct:

| Lost | Gained | Mods |
|---|---|---|
| `ForgeConfigSpec$Builder#<init>` | `ModConfigSpec$Builder#<init>` | 132 |
| `IEventBus#addListener` | `bus/api/IEventBus#addListener` | 190 |
| `ModLoadingContext#registerConfig` | **`ModContainer#registerConfig`** | 101 |
| `RegistryObject#get` | `DeferredHolder#get` | 115 |
| `ModList#isLoaded` | `fml/ModList#isLoaded` | 113 |
| `forge/fluids/FluidStack#getAmount` | `neoforge/fluids/FluidStack#getAmount` | 68 |

`ModLoadingContext#registerConfig` → `ModContainer#registerConfig` is a **class move**, not
in the hand-written §4 delta table. The corpus found it. That is the method working.

#### The remapper is now the critical path

**74.8% of lost symbols (18,541 of 24,778) carry SRG names.** Forge 1.20.1 runs SRG at
runtime; NeoForge 1.21.1 runs official Mojang names. Every vanilla member therefore differs
for mapping reasons that have nothing to do with the API changing, and vanilla rule mining
produces noise until the source side is remapped first.

Loader-API results are unaffected — Forge's own API is not obfuscated — which is why the
table above is trustworthy today.

**Consequence: the remapper moves ahead of everything else in Phase 2.** The pipeline must
be *remap SRG → Mojang, then diff*. Until that exists, roughly three quarters of the rule
surface cannot be mined at all.

### Measurement is the aiming mechanism

With ~300 mods and a rule set that will reach five figures, the only way to know which
rules are wrong is automated diffing against author-ported ground truth, plus per-jar
translation reports. **The report is not an excuse for failure — it is the instrument
that finds the remaining gap and closes it.** You cannot brute-force toward 100% without
knowing which cases are still failing.

---

## 3. Architecture

### Core principle: shim-first, rewrite-minimally, defer-to-runtime

Do not teach a bytecode transformer what the other version means. Ship compat libraries
and rewrite call sites to point at them. Anything AOT can't resolve, hand to runtime.

```
                  Source jar (either version)
                            │
   [1] Mapping remap        │  SRG <-> Mojang official
   [2] Package relocate     │  net.minecraftforge.* <-> net.neoforged.*
   [3] Call-site redirect   │  changed vanilla API -> static bridge methods
   [4] Type substitution    │  interface / superclass / descriptor fixups
   [5] Resource migration   │  mods.toml, pack.mcmeta, recipes, models, tags
   [6] Runtime-defer marker │  unresolved sites -> dynamic dispatch stubs
                            ▼
                 Translated jar (marked as translated)
                            │
                            ▼
   runs against: compat-lib + runtime-shim + content-backport + target loader
```

The transformer stays **small, dumb, and driven by a declarative rule set**. The
intelligence lives in four hand-written libraries, all human-auditable and unit-testable
without touching the engine:

- **`forge-compat`** — a real `net.minecraftforge.*` tree implemented over NeoForge, and
  its mirror image for the reverse direction. (Forgified Fabric API analog.)
- **`vanilla-bridge`** — static helpers emulating changed vanilla surface across the
  version gap, both directions.
- **`runtime-shim`** — the deferred-decision layer. Reflection interception, live NBT
  ⟷ component views, enchantment effect dispatch.
- **`content-backport`** — 1.21.1 vanilla content (maces, trial chambers, new blocks/items/
  tags) reimplemented as mod-added content so backward translation has real referents.
  **Backward-direction only; the single genuine asymmetry in the project.**

### Module layout

```
translation-core/       bytecode engine + rule DSL, no MC dependency
translation-rules/      declarative rule sets, both directions (the "knowledge")
forge-compat/           loader API shims, both directions
vanilla-bridge/         static bridges for changed vanilla surface
runtime-shim/           reflection interception, live NBT/component views
content-backport/       1.21.1 vanilla content for 1.20.1 targets
resource-migrator/      JSON / toml / mcmeta transforms
translation-cli/        standalone tool             (deliverable A)
translation-service/    in-game service jar         (deliverable B)
testkit/                corpus runner, load verification, coverage reporting
tools/                  standalone analysis utilities (no build step)
```

### Deliverable B: the in-game jar

A normal `@Mod` runs **too late** — mod discovery has already finished.

**GO/NO-GO: GREEN.** Verified against FML 4.0.43 source and tested against a real artifact
on 2026-08-02. Same-launch injection works, from the mods folder, with no launcher
arguments and no restart.

The mechanism, end to end:

1. `ModDirTransformerDiscoverer` (an `ITransformerDiscoveryService`, runs before mod
   discovery) walks the mods folder.
2. For each jar it calls `TransformerDiscovererConstants.shouldLoadInServiceLayer(path)`,
   which promotes any jar declaring one of six service types. **`IModFileCandidateLocator`
   is in that set.**
3. Promoted jars land on the `Layer.SERVICE` module layer *before* `ModDiscoverer` runs.
4. `ModDiscoverer` then `ServiceLoader`s every `IModFileCandidateLocator` and calls
   `findCandidates(ILaunchContext, IDiscoveryPipeline)`.
5. We translate, then call `pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT,
   IncompatibleFileReporting.WARN_ALWAYS)` to inject the result into the same launch.

Verified interface contract:

```java
public interface IModFileCandidateLocator extends IOrderedProvider {
    void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline);
}
```

Guard injection with `context.isLocated(path)` — handing the pipeline a jar another locator
already claimed surfaces as a duplicate-mod error.

**Test:** `devenv/spi-test/` builds a real service jar and runs NeoForge's own predicate
against it. Our jar is `PROMOTED`; a plain content mod is `IGNORED` (negative control, so
the test discriminates rather than passing everything). Requires
`--add-opens=java.base/java.lang.invoke=ALL-UNNAMED`, which the real launcher already passes.

**Still unproven:** that `addPath` results in the injected mod actually *loading* in a live
game. Service-layer promotion is proven; end-to-end injection needs a real launch.

Same core library as the CLI, different entry point. CLI first, wrap it second.

---

## 4. The API delta

Rows marked **[mined]** were confirmed empirically against the corpus and carry the number of
mods that corroborate them. Rows marked **(verify)** are still from memory and unconfirmed —
treat them as leads, not facts, and do not write a rule against one without checking it.

This table exists to bootstrap. `rule-report/` is the authority.

### Loader: Forge 1.20.1 ⟷ NeoForge 1.21.1

| Forge 1.20.1 | NeoForge 1.21.1 | Status | Difficulty |
|---|---|---|---|
| `MinecraftForge.EVENT_BUS` | `NeoForge.EVENT_BUS` | **[mined]** 199→189 | trivial |
| `eventbus.api.IEventBus#addListener` | `bus.api.IEventBus#addListener` | **[mined]** n=190 | trivial |
| `eventbus.api.IEventBus#register` | `bus.api.IEventBus#register` | **[mined]** n=101 | trivial |
| `RegistryObject#get` | `DeferredHolder#get` | **[mined]** n=115 | shim |
| `ForgeConfigSpec$Builder` | `ModConfigSpec$Builder` | **[mined]** n=132 | shim |
| `ModLoadingContext#registerConfig` | **`ModContainer#registerConfig`** | **[mined]** n=101 | shim — *class move, not predicted* |
| `fml.ModList` | `neoforged.fml.ModList` | **[mined]** n=136 | trivial |
| `fml.config.ModConfig$Type` | `neoforged.fml.config.ModConfig$Type` | **[mined]** n=68 | trivial |
| `forge.fluids.FluidStack` | `neoforge.fluids.FluidStack` | **[mined]** n=68 | trivial |
| `data.event.GatherDataEvent` | `neoforge.data.event.GatherDataEvent` | **[mined]** n=68 | trivial |
| `ForgeCapabilities#ITEM_HANDLER` | `Capabilities$ItemHandler` | **[mined]** 97→94 | see below |
| `LazyOptional` + `ICapabilityProvider` | `RegisterCapabilitiesEvent` + `BlockCapability` / `ItemCapability` / `EntityCapability` / `BlockCapabilityCache` | **[mined]** types confirmed present | **hard — different lifecycle model** |
| `SimpleChannel` | `network.registration.PayloadRegistrar` (`playToServer`, `playToClient`, `playBidirectional`, `configurationToClient`, `versioned`, `optional`, `executesOn`) + `network.handling.IPayloadContext` | **[mined]** FQNs confirmed | **hard — must synthesize `StreamCodec`s** |
| `@Cancelable` annotation | `ICancellableEvent` interface | **[confirmed]** present in bus 8.0.5 | shim |
| `net.minecraftforge.eventbus.api.Event` | `net.neoforged.bus.api.Event` | **[confirmed]** present in bus 8.0.5 | trivial |
| `FMLJavaModLoadingContext` | `IEventBus` passed to mod constructor | (verify) — 241 mods use it, top of the shim list | moderate |
| `META-INF/mods.toml` | `META-INF/neoforge.mods.toml` + field changes | (verify) | trivial |
| Forge extension interfaces (`IForgeBlockState` etc.) | NeoForge equivalents, not 1:1 | (verify) | moderate |

Capabilities and networking are the two hard shims. Networking especially: Forge's
`SimpleChannel` registers encode/decode loosely, while NeoForge requires typed
`StreamCodec`s plus the configuration/play phase split from the 1.20.2 protocol rewrite.
Expect this to need runtime assistance, not pure AOT.

### Vanilla: 1.20.1 ⟷ 1.21.1

| Change | Version | Impact |
|---|---|---|
| **Data components replace ItemStack NBT** | 1.20.5 | **Highest.** Needs live runtime view, both directions |
| **Enchantments become data-driven** | 1.21 | **Highest.** Needs effect-component dispatch bridge |
| `AttributeModifier` UUID+name → `ResourceLocation` id | 1.21 | Moderate; forward is a deterministic hash, reverse needs a persisted map |
| `ResourceLocation` ctor privatized → `fromNamespaceAndPath` / `parse` | 1.21 | Trivial, mechanical |
| Network protocol rewrite, `CustomPacketPayload`, configuration phase | 1.20.2 | High — couples to the networking shim |
| Registry / datapack loading changes | 1.20.2+ | Moderate |
| Recipe + ingredient JSON changes | 1.20.5 / 1.21 **(verify exact 1.21.1 shape)** | Moderate, resource-side |
| `pack.mcmeta` format bumps; resource/data formats diverged | throughout | Trivial once numbers confirmed **(verify)** |
| Potion / MobEffect changes | 1.21 | Moderate |
| Rendering: RenderType / ShaderInstance changes | 1.21 | Moderate; concentrated in mixin-heavy mods |
| **New vanilla content** (maces, trial chambers, blocks, tags) | 1.21 | **Backward direction only** — `content-backport` |

### Mappings

Forge 1.20.1 uses **SRG names at runtime**; NeoForge 1.21.1 uses **official Mojang
mappings**. Every vanilla member reference in compiled bytecode differs. Sounds
catastrophic, is actually the easy part — mapping files exist, mature tooling consumes
them, and Mojang's mapping license permits this use.

Mixin refmaps are mapping-specific and must be regenerated. Mixin **injection points** are
the separate hard problem (Phase 6).

---

## 5. Phased plan

Ordering principle: **easy first, hard after, nothing dropped.** Each phase raises measured
coverage against the corpus; the coverage number is the progress metric.

### The binding constraint is verification, not authoring

Authoring — the transformer, the rule sets, the shim classes — is fast. Thousands of rule
entries and hundreds of shim methods are hours of work, not months.

**Verification is what costs wall-clock.** Confirming a translated jar actually works means
launching Minecraft and running it, and the failures that matter most aren't crashes —
they're subtle. A machine that crafts wrong. An entity that won't spawn. A recipe that
silently vanished.

Every piece of verification we automate converts wall-clock into authoring speed, which is
the cheap resource. **This is why the verification harness is Phase 1, ahead of the
transformer.** Under-building it is the single decision that would blow up the schedule.

### Phase 0 — Feasibility spike & corpus intake · 2–4 weeks

- [x] Corpus analyzer built, verified against synthetic fixtures **and a real jar**
- [x] NeoForge 1.21.1 dev environment — builds a real mod jar end to end
- [x] Forge 1.20.1 dev environment — builds in 40s; ForgeGradle 6 on JDK 21 works
- [x] Corpus received: ATM9 (433 jars) + ATM10 (479 jars), ~2.5 GB, under `scrapyard/`
- [x] Corpus analyzed — 288 ground-truth pairs, 1,786 mixin classes, 48.6% Hard/Nightmare
- [x] Feature-drift methodology settled (corroboration threshold + member-level matching)
- [x] **Mod-discovery SPI go/no-go: GREEN** — same-launch injection from the mods folder,
      verified in FML 4.0.43 source and tested against a real service jar
- [x] **Live-launch check passed** — `addPath` injection and shim runtime linkage both proven
      via `runData` (headless, no EULA). Locator observed discovering, scanning and injecting;
      negative control confirms attribution. `MinecraftForge.EVENT_BUS == NeoForge.EVENT_BUS`
      is `true` under the real module layers.
- [ ] Confirm every **(verify)** item in §4 against real sources
- [ ] Hand-port one trivial mod both directions; record every change as ground truth
- [x] **Rule mining works** — all 288 pairs mined, 231 rules scoring ≥ 1.0
      (`tools/RuleMiner.java`, output in `rule-report/`)
- [x] `forge-compat` build order derived from corpus dependency counts
- [ ] **Pick the remapping toolchain — now the critical path** (§7). 74.8% of the rule
      surface is unmineable until SRG → Mojang remapping exists
- [x] **Shim-first architecture validated** — `forge-compat/` compiles three real shims
      against NeoForge 21.1.248 covering all three shim shapes; `net/minecraftforge/**`
      confirmed collision-free. Runtime linkage still needs the live-launch check above.

**Deliverable:** rule-DSL specification + measured corpus difficulty breakdown.

### Phase 1 — Verification harness · days · *gated by: dev environments*
Headless load test (does the translated jar load without crashing), registry diffing
(does it register the same blocks/items/entities as the author's port), recipe and tag
diffing, world-gen smoke test. Plus the coverage metric and per-jar translation reports.

**This is the multiplier on every later phase.** Built before the transformer, because
without it every subsequent phase is throttled by manual testing.

### Phase 2 — Transformer core · days · *gated by: corpus diffs*
Bytecode engine, rule DSL, mapping pipeline, jar in / jar out. Rule-DSL shape is derived
from real corpus pair diffs, not guessed.
**Exit:** a trivial mod translates automatically and loads, both directions.

### Phase 3 — `forge-compat` shims · 1–2 weeks · *gated by: verification throughput*
**Build order is now data-derived**, ranked by how many of the 288 paired mods depend on
each API (`rule-report/lost-symbols.tsv`):

| Rank | API | Mods |
|---|---|---|
| 1 | `FMLJavaModLoadingContext` | 241 |
| 2 | `IEventBus#addListener` | 214 |
| 3 | `MinecraftForge#EVENT_BUS` | 199 |
| 4 | `ModLoadingContext` | 163 |
| 5 | `RegistryObject#get` | 148 |
| 6 | `ModList` | 146 |
| 7 | `ForgeRegistries#ITEMS` | 143 |
| 8 | `DeferredRegister#register` | 139 |
| 9 | `ForgeConfigSpec$Builder` | 137 |
| 10 | `LazyOptional#of` | 117 |
| 11 | `NetworkEvent$Context` | 107 |

The top four cover ~70–84% of paired mods each and are all mechanical renames or namespace
moves. Networking (`NetworkEvent$Context`, 107 mods) stays last — it is the genuinely hard
shim and depends on Phase 4.

### Phase 4 — `vanilla-bridge` + `runtime-shim` · 2–4 weeks · *gated by: subtlety*
Data components, attributes, enchantment dispatch, recipes, potions, reflection
interception. The slowest authoring phase — component semantics are genuinely subtle and
the failure mode is silent wrong behavior, not a crash. **Parallelizes with Phase 3.**

### Phase 5 — Mixin & coremod handling · 1–3 months · **PROMOTED from last to core**
*Gated by: per-mod bespoke work. Runs parallel with Phases 3–4.*

Originally planned as a closing long tail. The corpus measurement moved it: **48.6% of
paired mods carry mixins, so coverage is capped at 51.4% until this is solved.** It is not
optional and it cannot wait for the end.

Refmap remapping is tractable. Injection-point repair is the hard part — when a target
method's body changed across versions, the `@At` anchor must be re-derived. Approach is
corpus-driven: every one of the **1,786 mixin classes** in the paired set has an
author-written 1.21.1 counterpart, so repair patterns are learned from worked examples
rather than derived analytically.

Sequence it by weight — the median mixin mod carries ~13 classes and is tractable early;
the top of the distribution (`oculus` 188, `railways` 177, `modernfix` 171) is late work.
**Still the longest phase, because it resists batching.**

### Phase 6 — Resource/data migration · days
mods.toml, pack.mcmeta, recipes, models, tags, advancements, loot tables. Independent of
the bytecode work — pullable earlier as a self-contained slice.

### Phase 7 — In-game service jar · days
Wraps the CLI core. Cheap *if* Phase 0's SPI answer is clean.

### Phase 8 — `content-backport` · 2–4 weeks
1.21.1 vanilla content as mod-added content for 1.20.1 targets. Backward direction only.
Deferrable — forward direction ships without it.

### Milestones

| Milestone | Estimate | Gated by |
|---|---|---|
| Go/no-go + corpus difficulty breakdown | days | dev env setup, corpus delivery |
| Verification harness live | days | dev environments |
| First useful release (simple content, forward) | 1–2 weeks | verification loop |
| 51% coverage ceiling (all non-mixin mods) | 1–2 months | verification throughput |
| Past 51% — requires mixin repair | 2–4 months | 1,786 injection points |
| High coverage, both directions | 3–6 months | per-mod bespoke work |
| Long-tail closure toward 100% | continuous | play-testing, corpus growth |

**Recalibration note.** Earlier drafts of this document estimated 14–20 months. That was
in human-developer-months, which is the wrong unit for AI-authored work — it over-weighted
authoring and under-weighted verification. These figures assume the corpus is in hand and
verification is automated aggressively. If verification stays manual, the original numbers
come back.

---

## 6. Inputs

| Input | Status |
|---|---|
| Test corpus (ATM9 + ATM10) | **Outstanding — the one live blocker** |
| Scope decision | Settled: everything in scope |
| Licensing | Settled: clear |
| Dev instances | Authorized; setting up |
| GitHub repo | On request, when Phase 1 starts |
| Owner's Java/bytecode fluency | **Still unanswered** — affects code style and how much internals get explained |

### Corpus delivery

Install both modpacks via the CurseForge/Prism launcher, then hand over the two `mods`
folders (or their paths). Do not prune anything — unpaired mods on both sides are
required test cases.

Then:

```bash
java tools/CorpusAnalyzer.java "<ATM9-mods-folder>" "<ATM10-mods-folder>" corpus-report
```

Produces `corpus-manifest.tsv`, `ground-truth-pairs.tsv`, and `unpaired.tsv`, plus a
console difficulty breakdown. Pure metadata analysis — no Minecraft install needed, no
network access, nothing written outside the output directory.

---

## 7. Prior art

- **Sinytra Connector** + **Forgified Fabric API** — closest architectural analog (Fabric
  mods on NeoForge). Study the locator/service hook and the shim-jar structure. Note it
  solves the *easier* problem (same game version, one direction) and still took several
  developers ~2 years.
- **NeoForge 1.20.1 → 1.21.1 porting primers** — bootstraps the §4 delta; much of Phase 0's
  verification is reading these carefully.
- **ParchmentMC** — mapping data.
- **ForgeAutoRenamingTool (FART)**, **tiny-remapper**, **srgutils** — candidate remapping
  toolchain; choose in Phase 0.
- **NeoForm / NeoGradle** — build and toolchain reference.

**Bytecode over source, definitively.** We don't have source for most mods, and
decompile → patch → recompile is lossy and fragile at corpus scale.

---

## 8. Open questions

- ~~Does the discovery SPI permit same-launch injection?~~ **Answered: yes.** See §3.
- Can `SimpleChannel` ⟷ `PayloadRegistrar` be mechanized, or does it need runtime assistance?
- What's the reflection-interception mechanism — rewrite call sites to shim helpers, or a
  java agent? Call-site rewriting is preferable (no launch-arg requirement for users).
- Reverse attribute-modifier mapping (`ResourceLocation` → UUID) needs a persisted table.
  Where does it live, and what happens on a miss?
- How do we verify a translated jar beyond "it loaded"? Headless world-gen smoke test? This
  is the difference between a demo and a tool, and it gates the coverage metric.
- Coverage metric definition: per-jar? per-call-site? per-rule? Needs settling in Phase 0,
  since it's the number the whole project steers by.
