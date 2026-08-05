# Easyport — Roadmap

> **Picking this up cold? Read [STATE.md](STATE.md) first.** It is the dense re-entry point:
> current status, locked decisions, and the gotchas that cost real time. This document is the
> full plan and the reasoning behind it — depth, not orientation.


**Goal:** Omnidirectional, complete translation of Minecraft mods between Forge 1.20.1 and
NeoForge 1.21.1, via (a) a standalone CLI tool and (b) an in-game service jar that
auto-translates a `/mods-from-other-version` folder into `/mods`.

**Audience:** end users, not developers. A mod that doesn't port is a failure, not an
edge case.

**Status:** Phases 0–7 done. Both deliverables exist for the forward direction: the CLI translates
real mods that load, register content and apply their mixins, and the service jar does the same
thing from inside the game during the launch that needs it. **The backward direction is not
started**, and it is the large remaining piece — everything to date reads as forward progress, and
that is not the same as being halfway.

> **This line has gone stale four times and is the one nobody updates and everybody trusts.**
> Phase status lives in the `### Phase N — DONE` blocks below; check those before believing this.

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
| `META-INF/mods.toml` | `META-INF/neoforge.mods.toml` | **[mined]** essentially a *file rename* — the key set is unchanged apart from `enumExtensions` (13 mods). **Must not leave `mods.toml` behind** (STATE gotcha #8) | trivial |
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
| Recipe JSON: `components` added (41 mods), `show_notification` removed (62→6) | 1.20.5 / 1.21 | **[mined]** Moderate, resource-side |
| `pack.mcmeta` `pack_format` **15 → 34** (dominant values) | throughout | **[mined]** Trivial |
| **Datapack directories singularised** — see Phase 6 | 1.21 | **[mined]** Trivial but pervasive |
| Enchantment JSON schema (`anvil_cost`, `min_cost`, `max_cost`, `weight`, `slots`, `supported_items`, `effects`, …) | 1.21 | **[mined]** 22 mods ship these; 0 in 1.20.1 |
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

### Phase 1 — Verification harness — **DONE**
**This is the multiplier on every later phase.** Built before the transformer, because
without it every subsequent phase is throttled by manual testing.

Delivered: `tools/VerifyHarness.java` + `testkit/inspector/`.

- [x] Headless load test — `runData`, ~10s, no EULA
- [x] **Loaded vs. rejected** distinguished — an empty delta means very different things
      depending on whether the jar ran at all
- [x] Registry coverage — differential against a baseline launch, so no ignore lists
- [x] Resource coverage — recipes/tags/loot tables compared statically, no launch needed
- [x] Coverage metric, validated at 100% on a self-comparison
- [ ] World-gen smoke test — **deferred**, needs a server (EULA) or client (display). Would
      catch runtime crashes during play, which neither coverage metric sees.

**Day-zero baseline measured.** An untranslated Forge 1.20.1 jar
(`additional_lights`) against its NeoForge port:

| Metric | Result |
|---|---|
| Registry coverage | **0%** — 0 of 326 entries; jar rejected during discovery |
| Resource coverage | **83.6%** — 148 missing, 148 extra |

The resource delta is *entirely* the 1.21 singularisation: 143 `recipes/`→`recipe/` and 5
`tags/blocks/`→`tags/block/`. Nothing else differs, so for this mod the resource layer is a
pure mechanical rename.

**The registry result is why this phase came first.** The launch succeeded and nothing
crashed — a crash-only check would have called it a pass, while the mod contributed nothing
at all.

### Phase 2 — Transformer core — **DONE**
Bytecode engine, rule DSL, mapping pipeline, jar in / jar out.
**Exit criterion was "a trivial mod translates automatically and loads".** Exceeded: a real
corpus mod (`additional_lights`) translates to **100% registry and 100% resource coverage**
against its author's own port. Day zero was 0% / 83.6%.

`tools/Translate.java` + `rules/forward.rules.tsv`. Rule kinds implemented: `TYPE_RENAME`,
`TYPE_PREFIX_RENAME`, `RENAME_METHOD`, `CTOR_TO_STATIC`, `CTOR_SWAP2`, `REMOVED` — plus two
`@EventBusSubscriber` repairs that no rule kind can express (see Phase 3).

Backward direction (1.21.1 → 1.20.1) is **not** started; only `rules/forward.rules.tsv`
exists.

#### Rule DSL requirements — derived from measurement, not design taste

The zero-drift probe pair (`handport/`) was scored against the corpus-mined rules with a
hand-labelled ground truth. Result: **61% precision on genuinely mappable symbols, and the
failures are structural rather than statistical.**

| Outcome | n | Character |
|---|---|---|
| Correct | 13 | **Every** pure 1:1 rename |
| Wrong | 8 | **Every** migration that is not 1:1 |
| False positive | 5 | Symbols with no replacement — a target was invented |

So a symbol→symbol table is necessary and nowhere near sufficient. The DSL needs four rule
kinds:

1. **`RENAME`** — symbol → symbol. Mining already produces these at high confidence and they
   cover the bulk of the loader API.
2. **`REMOVED`** — the symbol has no replacement and the capability moved elsewhere. The
   miner currently cannot represent this and therefore always predicts *something*; it needs a
   null hypothesis, or it will emit five confident wrong answers per mod.
3. **`CONTEXTUAL`** — one source symbol, several valid targets chosen by surrounding context.
   `RegistryObject#get` becomes `DeferredBlock#get` or `DeferredItem#get` depending on which
   registry the holder came from. Requires type inference at the call site, not a lookup.
4. **`STRUCTURAL`** — not a call-site rewrite at all. **`FMLJavaModLoadingContext` is rule
   kind 4 and it is number one on the work list at 241 mods.** The mod event bus and
   `ModContainer` are injected into the mod constructor in NeoForge, so there is no call to
   rewrite — the *constructor signature* has to change. The `ItemStack` NBT surface is the
   same shape of problem: `getOrCreateTag`/`setTag`/`hasTag` become a `CustomData` idiom, not
   a renamed method.

**The single most-depended-on migration in the corpus cannot be expressed as a symbol
mapping.** Designing the DSL around rename tables and bolting on the rest would have meant
rewriting the engine once the top of the work list was reached.

### Phase 3 — `forge-compat` shims — **DONE**

Exit criterion met: no library or sampled mod is blocked by a missing or wrong net.minecraftforge
shim. See STATE.md for the evidence table and the four remaining library blockers, all of which
are Phase 4 (vanilla drift) or Phase 5 (mixin application).

The build order below was the plan. It held for the top of the list and was wrong about
networking, which this phase moved forward: it was scheduled last on the assumption it depended
on Phase 4, and measurement put SimpleChannel in 162 of 433 corpus jars, ahead of everything
except the event bus and mod-loading context. Bridged onto NeoForge payloads instead.

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

**Status: ranks 1–10 done.** 29 classes. Rank 11 (networking) is the remaining head item.

**Networking looks shimmable after all** — a correction to the §4 assessment, which called it
"hard, must synthesize `StreamCodec`s". That framing assumed each Forge message had to become
its own typed NeoForge payload, which would indeed require generating a codec per message
class. It does not. One generic payload per *channel* is enough:

- `SimpleChannel.registerMessage(...)` buffers the encoder, decoder and handler rather than
  registering anything immediately. Forge mods call it from static init or the mod
  constructor, long before NeoForge's `RegisterPayloadHandlersEvent` fires.
- forge-compat listens for that event and replays the buffered registrations.
- Each channel registers **one** payload type carrying `(discriminator, byte[])`. Its
  `StreamCodec` only reads and writes the byte array — no per-message codec generation.
- On receipt, the discriminator selects the buffered decoder, which reads the bytes exactly as
  Forge's did, and the handler runs unchanged.

The Forge encode/decode functions already produce a `FriendlyByteBuf`, so the wire format is
carried across intact instead of being reinterpreted. Untested — the design follows from the
API surface, and the surrounding phase has repeatedly shown that reasoning about someone
else's loader is worth less than one run.

Expansion runs as a measured loop rather than by working down the list blind:
`tools/batch-verify.sh` translates a corpus sample, runs each mod, and the per-mod failure
logs name exactly the missing class. Each round unblocks several mods at once because the
failure distribution is heavily headed — the second batch showed 7 failures behind only 4
distinct missing symbols.

**Three things this phase established that the plan had wrong or missing:**

1. **Rank 1 was mis-classified as `STRUCTURAL`.** `FMLJavaModLoadingContext` is a delegating
   shim — NeoForge kept `ModLoadingContext.get()`. The largest item on the work list is cheap.
2. **Shims must match descriptors real mods were compiled against**, not just link. A spike
   compiled against the shims proved nothing; `IEventBus` had to become a sub-interface of
   NeoForge's so a Forge-typed descriptor resolves while staying valid on the NeoForge side.
   The same trick then solved `IConfigSpec`.
3. **Forge and NeoForge differ in strictness, and the shim must preserve Forge's.** NeoForge
   throws where Forge accepted silently. This bit twice by different routes — once through
   explicit `EVENT_BUS.register()`, which the shim absorbs, and once through
   `@EventBusSubscriber`, which it cannot, because FML does that registration itself and the
   shim is never in the path. That one is fixed in the transformer instead.

### Phase 4 — `vanilla-bridge` + `runtime-shim` — **DONE**

Planned as "the slowest authoring phase" on the grounds that component semantics are subtle and
the failure mode is silent wrong behaviour rather than a crash. Half right, and wrong about the
shape of the work.

**What the plan got wrong.** It read as a list of subsystems to port by hand — data components,
attributes, enchantment dispatch, recipes, potions. Measured, they are not separate problems.
`VanillaGaps` put 25,272 vanilla member references in front of the question and the same cause
was under most of them: *a vanilla type changed shape underneath code compiled against the old
one*. Attributes and potions turned out to be one mechanism (`Holder` wrapping), and that
mechanism needs no rules at all — it reads the family off the platform's own descriptors.

**What it got right.** The failure mode really is silence. Every conversion that cannot be
reconstructed — a codec that would have to be derived from a mod's hand-written serializer —
returns a placeholder and is named in the report, because a mod that loads and quietly does less
is the thing this project most wants to avoid.

**What the plan missed entirely:** a mod's *class hierarchy* becoming illegal. 1.21 made
`ResourceLocation` and `Ingredient` final, turned `ArmorMaterial` into a record, and made several
methods final. No call-site rewrite reaches any of that, and nothing in the plan anticipated it.
It was found by building an offline verifier, not by launching.

**Also learned:** the biggest single lever in the phase was not a mechanism but a tool. Offline
type-checking replaced a loop that cost one launch per broken method.

The remaining vanilla work is enumerated in [api-report/README.md](api-report/README.md), ranked
by owning type, and one shape stands outside all seven mechanisms: an *override*. They adapt call
sites, and a mod declaring `saveAdditional(CompoundTag)` is not calling anything — it is failing
to override something.

### Phase 5 — Mixin & coremod handling — **DONE**

Planned as the longest phase, on the grounds that it "resists batching" and would need per-mod
bespoke work against 1,786 mixin classes. Wrong about the shape, and wrong in a useful direction.

**What the plan got right.** Injection-point repair really is the hard part, and it is not a
signature problem: the anchor names a member that still exists and the method being patched stopped
calling it. What the plan missed is that this is answerable *offline* — the platform jar has the
bodies, and Mixin scans exactly the resolved target method, so reading that one method's instruction
list asks the same question Mixin will. 53 of them, invisible to every other check.

**What the plan got wrong.** It assumed the work was repair, learned from worked examples. Measured,
the distribution has no head at all — nothing in the queue exceeds 8 jars — so per-case repair is
all cost and no leverage. The leverage was in **changing the granularity of failure**. A broken
coordinate used to delete the whole mixin class; `require = 0` disables the one injector and leaves
the rest applying. That took every mixin failure in the corpus off the launch path without
addressing a single one of them individually.

**What the plan missed entirely:** that removing a `@Accessor` annotation reclassifies the *whole
mixin* (`MixinInfo.getVariant`), turning one dead accessor into a mixin that may no longer target a
class. It regressed an unrelated library from 100% to not loading.

Measured outcome: libraries loading 6/8 → **7/8**, mixin coordinates intact **88.4%**, and
coordinates that abort a launch **595 → 0**. The 595 still lose their behaviour and are ranked in
[api-report/README.md](api-report/README.md); that queue is dominated by client rendering, which
`runData` cannot exercise.

The corpus-driven learning from the 1,786 pairs was **not** built. It was not needed for the exit
criterion and the flat distribution argues against doing it blind. The pairs are still there.

### Phase 6 — Resource/data migration · DONE

**Exit criterion:** a translated mod's datapack means the same thing to 1.21.1 that it meant to
1.20.1. Met, for everything the two platform jars and the corpus can be asked about.

#### What this plan got wrong, and it was not a detail

The section this replaces said Phase 6 was "mostly already built" and listed three small items.
That assessment was made by reading `resource-report/json-key-deltas.tsv`, which was produced by a
scan of **bare key names** — and the note in `tools/README.md` documenting that blind spot gave as
its hypothetical example a recipe's `result.item` becoming `result.id`. That is precisely what
1.20.5 did, to 34,375 files in the corpus. The limitation was written down, directly above the
output, and the output was still read as a complete answer.

Making the scan record key *paths* instead took about thirty lines and turned three small items
into nine, several of them corpus-wide:

| Change | Scale | Failure if skipped |
|---|---|---|
| Tag namespace `forge:` → `c:` | 33,674 references, **206 of 433 jars** | silent — recipes never match |
| Common-tag renames past the swap | 39 tags | silent |
| Recipe `result.item` → `result.id` | 34,375 files | recipe dropped |
| Advancement `display.icon.item` → `id` | 55 mods | advancement dropped |
| `conditions` → `neoforge:conditions` | 47 mods | silent — every conditional recipe fires |
| Condition/modifier `type` `forge:` → `neoforge:` | 32,937 type values | file dropped |
| `forge:conditional` unwrapped | 2,457 files | file dropped |
| `data/<ns>/forge/` → `neoforge/` | 59 jars | biome modifiers never load |
| `dimension_type` int provider flattened | 5 mods | the dimension does not exist |

**Four of those nine fail silently**, which is the whole argument for the phase. A mod with the
tag namespace unmigrated loads, registers every block and item it has, and cannot craft any of
them, with nothing in any log to say why.

#### `pack_format` was the one item on the old list that was real, and it is not

It was the only item with direct evidence — translated jars do ship 15 where 1.21.1 wants 34 —
and measuring the corpus retired it. ATM10 is a shipping modpack, and 19 of its jars declare
`pack_format: 6` (Minecraft 1.16.2), 30 declare 15, and 179 of 479 ship no `pack.mcmeta` at all.
Mod resource packs are not validated against it. Bumping the number would have been a change with
a plausible rationale, no effect, and a new opportunity to be wrong about data packs, which want
48 rather than 34.

#### The one place the corpus is the wrong source

Everything else in this project is mined from ATM9 against ATM10. The `forge:` → `c:` tag mapping
is mined from `forge-1.20.1-47.4.22-universal.jar` against `neoforge-21.1.248.jar` instead,
because a `data/c/tags/item/tools/axes.json` sitting in some ATM10 mod proves only that a mod
author invented that name. The platform jars are what the game defines: 321 tags against 463, 187
identical under the directory singularisation, 59 renamed by 42 rules, 75 with no counterpart
flagged by 39 more. Nothing is unaccounted for.

The 75 with no counterpart are reported rather than approximated. Most are the colour tags: 1.21
replaced `forge:glass/black` with the intersection of `c:glass_blocks` and `c:dyed/black`, which
no rename can express, and mapping it to `c:dyed/black` alone would silently widen a
stained-glass recipe to accept black wool. That is the trade this project always refuses.

**Do not use resource-coverage percentages as this phase's metric.** They are contaminated by the
same feature drift that poisons rule mining (§2): `allthecompressed` shows 6,612 "missing" resources
because its reference port is version 4.4.0 against a 3.0.2 source, and almost all of that is
content the author added rather than anything migration should produce. A low resource percentage is
a prompt to look, not a defect.

That trap is easy to fall into from the other side too. `blockui` looked like a systematic 1.21 GUI
migration — `textures/gui/*.png` moving into a `<modid>_sprites/` subdirectory with a new atlas JSON
— and measuring it killed the theory: `assets/<ns>/atlases/` appears in 77 of 433 ATM9 jars and 78 of
479 ATM10 jars, so it is not a version change at all. That was one mod restructuring itself.

**The bulk of what remains below is backward-direction work**, because it is a list of trees that
exist only in 1.21.1. Forward, they need nothing; backward, they must be synthesised or dropped.

**Work list mined from the corpus** (`tools/ResourceMiner.java`, output in `resource-report/`):

**Directory renames (1.21 singularised the datapack tree) — implemented, `DIR_RENAMES` / `TAG_RENAMES`:**

| 1.20.1 | 1.21.1 | Mods |
|---|---|---|
| `data/<ns>/recipes/` | `data/<ns>/recipe/` | 160 |
| `data/<ns>/loot_tables/` | `data/<ns>/loot_table/` | 148 |
| `data/<ns>/advancements/` | `data/<ns>/advancement/` | 55 |
| `data/<ns>/tags/entity_types/` | `data/<ns>/tags/entity_type/` | 40 |
| `data/<ns>/tags/fluids/` | `data/<ns>/tags/fluid/` | 23 → 27 |
| `data/<ns>/structures/` | `data/<ns>/structure/` | 44 |

**New 1.21.1-only trees** the backward direction must synthesise or drop:
`data/<ns>/enchantment/` (22), `data/<ns>/data_maps/` (36), `data/<ns>/neoforge/` (42),
`data/<ns>/jukebox_song/` (8), `data/<ns>/tags/data_component_type/` (5).

**Other confirmed deltas:** `META-INF/mods.toml` → `META-INF/neoforge.mods.toml` (file rename, key
set otherwise unchanged) — **done**, along with the dependency version ranges, which the file rename
alone does not cover: a renamed descriptor still declaring `minecraft [1.20.1,1.21)` gets past
discovery and is refused during resolution, which is a more confusing failure than being rejected
outright. Recipe `show_notification` is now removed as well. `pack_format` was measured and
retired, see above. Recipe `components` and loot table `include` / `predicates` are 1.21.1-only
additions: forward they need nothing, backward they must be dropped.

### Phase 7 — In-game service jar · DONE

**Exit criterion:** a mod dropped into `mods-from-other-version/` is translated and loaded by the
same launch. Met, and the estimate held — it was days of work because Phase 0 had already answered
the only question that could have made it months.

It wraps the CLI core exactly as planned. `easyport.jar` declares
`IModFileCandidateLocator` in `META-INF/services`, which FML's `ModDirTransformerDiscoverer` finds
while walking the mods folder *before* mod discovery, promoting the jar onto the SERVICE layer. It
carries `forward.rules.tsv`, `srg2official.tsv` and `forge-compat.jar` inside itself, translates
each inbox jar into `mods/` under a `-easyport.jar` name, and hands the results straight to the
discovery pipeline.

**Verified end to end, not inferred from the spike:**

| Check | Result |
|---|---|
| Translated mod loads in the same launch | `Creating FMLModContainer instance for [com.mgen256.al.AdditionalLights]` |
| Four mixin-heavy libraries at once | balm, curios, supermartijn642corelib, yungsapi all construct |
| In-game output vs CLI output | **byte-identical**, unpacked and diffed |
| Unchanged source relaunched | cache hit, no retranslation |
| Easyport rebuilt | cache invalidated, mod retranslated |
| Source removed from the inbox | translated jar removed from `mods/` |

The byte-identical row is the one that matters. It runs `easyport.tools.Translate`, the same class
the command line runs — a second implementation would drift from the one every measurement in this
project was taken against, and the drift would surface as mods behaving differently depending on
how they were translated.

**Two things the plan did not anticipate**, both found by running it rather than reasoning about
it. The jar cannot find itself — FML loads it through the secure jar handler, so its code source
is a `union:` URI that `Paths.get` refuses, which made its own modification time a cache key of
zero and a rebuilt Easyport retranslate nothing. And the module path arrives with its separators
doubled, so the same platform jar was indexed twice under two spellings. Both are recorded in
[tools/README.md](tools/README.md).

**What it does not do** is detect direction. It translates Forge 1.20.1 → NeoForge 1.21.1 and
recognises a jar it should leave alone; running on a Forge 1.20.1 instance and translating
backward needs the backward rule set, which does not exist.

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
