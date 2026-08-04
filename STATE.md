# STATE — read this first

Dense re-entry point. If you are picking this up cold (fresh context, new contributor),
read this file and nothing else until you need depth. [ROADMAP.md](ROADMAP.md) has the full
plan; this has where things actually stand.

**Last updated:** 2026-08-04, mid Phase 3 (forge-compat expansion).

---

## Resume here

**Work from the gap report, not from the launch logs.** This is the single biggest change to
how this project is worked on, so it comes first.

```bash
# The whole remaining work queue, ranked, offline, in about a minute.
java tools/RenameGaps.java api-report/forge-api-usage.txt rules/forward.rules.tsv \
    forge-compat/forge-compat.jar \
    devenv/neoforge-1.21.1/build/moddev/artifacts/neoforge-21.1.248.jar \
    devenv/spi/loader-4.0.43.jar devenv/spi/bus-8.0.5.jar devenv/spi/distmarker.jar \
    > api-report/unresolved-types.txt
```

It lists every Forge type the 433-jar corpus references that neither a shim nor a rule
resolves, ranked by how many jars each one blocks, with a suggested rename target where the
platform has a class of the same name. See [api-report/README.md](api-report/README.md).

Why this matters: the old loop found missing classes by launching the game and reading the
first `ClassNotFoundException` — about ten minutes per class, strictly one at a time, because
the JVM stops at the first one. Architectury alone walked through `TickEvent`,
`TextureStitchEvent` and `EntityItemPickupEvent` that way. Every input to that answer is
static. The first run of the report resolved **196 types in one edit**.

**The loop, for what the report cannot answer** — signature mismatches, verification errors
and behaviour, which need a real launch:

```bash
bash tools/build-forge-compat.sh          # after ANY forge-compat change; clears the baseline
bash tools/batch-verify.sh < batch-report/libs.tsv     # 8 highest-fan-in libraries
bash tools/batch-verify.sh < batch-report/sample.tsv   # 14 mixed mods

# what blocked each mod (these logs self-clear on success, so they are always live)
grep -oE "(ClassNotFoundException|NoSuchMethodError)[:.] ?'?[a-zA-Z0-9_./$]{0,50}" \
    devenv/neoforge-1.21.1/run/failed-<modid>.log | head -2
```

**Three hard rules, each learned by breaking something:**

- **Never chain a build into a backgrounded batch and read only the tail.** A compile error
  scrolled past once and ten minutes of verification ran against a one-class forge-compat,
  reporting every mod broken including one that was at 100%.
- Editing `tools/*.java` or `rules/forward.rules.tsv` mid-run is now safe — `batch-verify.sh`
  snapshots both at start. Tool edits corrupted three runs before that existed.
- **A batch that reports the same failures as last time may not have run.** It skips mods
  already in `batch-results.tsv` so a long run can resume. It now discards that file
  automatically when the rules, forge-compat or `Translate.java` are newer — but one full
  batch was read as "both fixes changed nothing" when in fact nothing was re-tested.

**Immediate next work:** capabilities. `ForgeCapabilities` (164 jars), `CapabilityManager`
(92), `Capability` (75), `AttachCapabilitiesEvent` (86) and `ICapabilitySerializable` (40) are
the top of the gap report and are one design problem, not five. NeoForge replaced the model
outright — capabilities are resolved by lookup (`BlockCapability`/`ItemCapability`) rather
than attached to objects — so this is the first item that a delegating shim cannot cover.

After that: `ForgeHooks` (91), `IForgeMenuType` (89), `ForgeEventFactory` (80),
`Event$Result` (72), and the restructured `LivingEvent` family, which needs the same
bridging treatment as `TickEvent`.

The two mixin-apply failures are the hard Phase 7 problem and should be left until last.

**Not started:** backward direction (1.21.1 → 1.20.1). Only `rules/forward.rules.tsv` exists.
The locked decision is omnidirectional, and everything so far reads as forward progress — do
not mistake that for being halfway.

---

## What this is

**Easyport** translates Minecraft mods between **Forge 1.20.1** and **NeoForge 1.21.1**,
both directions, aiming at complete coverage. Two deliverables: a CLI tool, and a jar that
sits in `/mods` and auto-translates anything dropped in `/mods-from-other-version`.

This is *two* stacked migrations — loader (Forge↔NeoForge) and game version (1.20.1↔1.21.1).
The second is harder and contains the data-component rewrite (1.20.5) and data-driven
enchantments (1.21).

---

## Locked decisions — do not relitigate

| | |
|---|---|
| Direction | **Omnidirectional** |
| Coverage target | **100%.** Every mod, every line |
| Scope | **Everything** — mixins, coremods, reflection included |
| Strategy | Brute force. Easy first, hard after, nothing dropped |
| Corpus | ATM9 (Forge 1.20.1) vs ATM10 (NeoForge 1.21.1) |
| License | CC0 1.0 — see the carve-outs in [README](README.md) |
| Owner's role | Treats internals as a **black box**; document accordingly |

The owner has pushed back on scope reduction twice. Both times the objection was wrong on
the merits and the corpus proved it. **Do not re-propose narrowing scope.**

---

## Where things stand

Everything below is measured, not estimated.

> **Phase status lives only in the `### Phase N — COMPLETE` sign-off blocks below, and the
> newest one is listed first.** Do not name a current or next phase here. This header has gone
> stale three times — it is the one line nobody updates and everybody trusts.

- **Both dev environments build.** NeoForge 21.1.248 (2m10s) and Forge 1.20.1-47.4.22 (40s),
  both producing real mod jars. ForgeGradle 6 works on JDK 21.
- **SPI go/no-go: GREEN.** A jar in `/mods` declaring `IModFileCandidateLocator` loads on the
  SERVICE layer *before* mod discovery, so translated mods inject in the same launch — no
  restart, no launcher args. Verified in FML 4.0.43 source and tested against a real artifact.
- **Corpus measured.** 433 + 479 jars → **288 ground-truth pairs**. 48.6% Hard/Nightmare,
  **1,786 mixin classes**.
- **Rule mining works.** **231 candidate rules scoring ≥ 1.0**, corroborated across up to
  190 mods. Found a class move (`ModLoadingContext#registerConfig` → `ModContainer#registerConfig`)
  that hand-written analysis had missed.

- **Shim-first architecture validated.** `forge-compat/` compiles three real shims against
  NeoForge 21.1.248, covering the three shapes every later shim will take: static field alias
  (`MinecraftForge.EVENT_BUS`, 199 mods), static-method delegation (`ModList`, 146 mods), and
  instance delegation with self-return (`ForgeConfigSpec$Builder`, 137 mods). Bytecode
  verified: the alias is the same instance, and builder methods return the *shim* type so
  chaining survives.

- **End-to-end injection proven in a live launch.** `runData` with the service jar in
  `run/mods/` and a real ATM10 mod in `run/mods-from-other-version/`:

  ```
  Found additional transformation services from discovery services: [translation-layer-spike.jar]
         0 - translationlayer.spi.TranslationLocator from translation-layer-spike.jar
  [translation-layer] found 1 jar(s) in mods-from-other-version
  [translation-layer] injecting accelerated-decay-neoforge-21.0.0.jar
  ```

  The injected mod then reached dependency resolution and failed only on an unrelated missing
  dependency (`architectury`) — i.e. it loaded. Negative control: with the service jar
  removed, the mod is not found at all.

- **Shim runtime linkage proven.** A real `@Mod` bundling `net/minecraftforge/**` exercised
  all three shapes under the live module layers. `MinecraftForge.EVENT_BUS == NeoForge.EVENT_BUS`
  returned **true** — the shim *is* NeoForge's bus, not a wrapper, so translated listeners
  land on the bus NeoForge actually dispatches from. No `NoClassDefFoundError`, no
  split-package rejection.

- **Remapper built; vanilla mining unblocked.** `tools/SrgToOfficial.java` produces the
  SRG→official table (64,225 members, 0 unmatched). SRG residue in mined symbols: 74.8% → **0.0%**.
  The vanilla work list is now data-driven, and it confirms both predicted hard problems with
  real numbers:

  | Vanilla API | Mods | Why it matters |
  |---|---|---|
  | `ResourceLocation#<init>` | 237 + 188 | Constructor privatised in 1.21 → `fromNamespaceAndPath` / `parse`. **Largest single dependency in the corpus.** |
  | `ItemStack#getTag` / `hasTag` / `getOrCreateTag` / `setTag` / `save` | 135 / 111 / 105 / 97 / 84 | The data-component rewrite — the hardest change, now quantified |
  | `FriendlyByteBuf#writeInt` / `readItem` / `writeItem` / `writeNbt` | 114 / 88 / 84 / 69 | Network rewrite, `StreamCodec` migration |
  | `BlockEntity#load` | 70 | 1.21 signature change |
  | `MenuScreens#register` | 76 | GUI registration change |

- **Resource layer mined too.** `tools/ResourceMiner.java` retired most remaining
  **(verify)** rows empirically. 1.21 **singularised the datapack tree** — `recipes` →
  `recipe` (160 mods), `loot_tables` → `loot_table` (148), `advancements` → `advancement`
  (55), `tags/fluids` → `tags/fluid`, `tags/entity_types` → `tags/entity_type`. `mods.toml` →
  `neoforge.mods.toml` is essentially a **file rename** — the key set is unchanged apart from
  `enumExtensions`. `pack_format` 15 → 34. The data-driven enchantment schema is visible in
  22 mods with zero counterparts in 1.20.1, confirming that migration from the corpus rather
  than from memory.

- **Zero-drift ground truth built and the rule set scored against it.** `handport/` holds a
  probe mod written for both loaders, functionally identical, so every difference between the
  built jars is migration and nothing else. `handport/expected-rules.tsv` is the hand-labelled
  correct answer. Scored: **61% precision on mappable symbols, 13 correct / 8 wrong / 5 false
  positives.**

  The failure pattern is structural, and it set the Phase 2 rule-DSL design (ROADMAP §5):
  every 1:1 rename was correct, every non-1:1 migration was wrong, and symbols with **no**
  replacement got a confident invented answer. **`FMLJavaModLoadingContext` — number one on
  the work list at 241 mods — cannot be expressed as a symbol mapping at all**; the bus is
  injected into the mod constructor, so the constructor signature must change rather than any
  call site. Four rule kinds are needed: `RENAME`, `REMOVED`, `CONTEXTUAL`, `STRUCTURAL`.

### Phase 2 — COMPLETE, and first real mod translated end to end

`tools/Translate.java` + `rules/forward.rules.tsv`. **A real corpus mod now translates to
100% registry and 100% resource coverage against its author's own port.**

```
additional_lights (Forge 1.20.1 -> NeoForge 1.21.1)
  registry  326/326 = 100%   (173 blocks, 147 items, 1 tab, 5 sounds; 0 missing, 0 extra)
  resource  900/900 = 100%
  day zero:   0% / 83.6%
```

Five rule kinds implemented: `TYPE_RENAME`, `RENAME_METHOD`, `CTOR_TO_STATIC`,
`CTOR_SWAP2`, `REMOVED`. SRG→official remapping runs first, before any rule matches.

**Correction to a Phase 0 conclusion.** Phase 0 recorded `FMLJavaModLoadingContext` (241 mods,
top of the work list) as `STRUCTURAL` — requiring a constructor-signature rewrite because
NeoForge injects the bus. Half right: the injection is real, but NeoForge *kept*
`ModLoadingContext.get()` and exposes the bus via `getActiveContainer().getEventBus()`, so it
is a plain delegating shim. The largest item on the work list is cheap, not expensive.

**The shim-first boundary, learned by hitting it.** Anything the *loader* looks up or
dispatches by name must be rewritten; anything a mod merely *calls* can be shimmed:

| Category | Why | Handling |
|---|---|---|
| `@Mod`, `@SubscribeEvent`, `@EventBusSubscriber` | FML scans for the exact descriptor | `TYPE_RENAME` |
| Lifecycle + bus event classes | Dispatched by exact class identity | `TYPE_RENAME` |
| `IEventBus` | Appears in mod descriptors | Shim **extending** NeoForge's, so it is valid on both sides |
| Everything else Forge | Only called by mods | Plain shim |

Getting this wrong is silent. A shimmed `@Mod` means FML never finds the mod: it loads,
registers nothing, and reports no error at all.

**A false positive worth remembering.** The Phase 0 shim spike compiled *against* the shims
and linked cleanly, which looked like proof and was not — it never tested descriptor
compatibility, because it had been compiled against the very types under test. Real Forge mods
are compiled against real Forge, and only they exercise that. Test shims with a real mod.

**Forge and NeoForge differ in strictness, and the shim must preserve Forge's.** NeoForge
throws when `EVENT_BUS.register()` gets an object with no `@SubscribeEvent` methods; Forge
accepted it silently. That is a hard load failure on real corpus mods, so `ForgeEventBus`
makes it a no-op.

### Phase 3 — forge-compat, IN PROGRESS

21 classes covering the head of the work list: `MinecraftForge`, `IEventBus`/`ForgeEventBus`,
`FMLJavaModLoadingContext`, `ModLoadingContext`, `ModConfig`, `IConfigSpec`, `ModList`,
`ForgeConfigSpec`, `DistExecutor`, `DeferredRegister`, `RegistryObject`, `IForgeRegistry`,
`ForgeRegistries`, `ForgeSoundType`.

Expansion is a tight loop, and `tools/batch-verify.sh` drives it: translate a sample, run each,
collect the missing class names from the logs, add them, repeat. Missing classes fail loudly
with the exact name needed, so the logs *are* the work queue.

**First batch over 14 paired mods gave a much better queue than the failure count suggested:
13 failures but only 5 distinct missing symbols.** The distribution is heavily headed — the
same few classes block most mods, so each expansion round unblocks many at once.

#### Current standing on the 14-mod sample, after seven rounds

| Status | n | Meaning |
|---|---|---|
| `OK` | 1 | Loads and content verified — `additional_lights`, 100% registry / 100% resource |
| `NO_CONTENT` | 3 | Loads; registers nothing on either side, so coverage is undefined |
| `LAUNCH_FAILED` | 3 | Real remaining blockers |
| `DEPS_MISSING` | 7 | Harness cannot load their dependencies — not translation failures |

**Read it as 4 of 7 measurable mods loading, up from 1.** Seven of the fourteen are excluded
by the harness ceiling, so the denominator is 7, not 14. Quoting "1 of 14 at 100%" would be
wrong in both directions at once — it counts harness limitations as translation failures, and
it treats behaviour-only mods as zero when they have nothing to score.

The three real blockers are three *different* problems, not one recurring:
`DropExperienceBlock`'s single-argument constructor (removed outright, needs a rule kind that
inserts a defaulted argument), an `ArmorMaterial` interface change surfacing as
`IncompatibleClassChangeError`, and `EventBusSubscriber$Bus.bus()` which no longer exists.
The cheap head of the distribution is done; what is left is the tail.

**Count `DEPS_MISSING` separately or the translator looks far worse than it is.** 5 of those 13
failures were mods needing *other* mods (`ars_nouveau`, `mekanism`, `create`, `curios`,
`bookshelf`) that the harness does not load. That is a harness limitation, not a translation
failure, and lumping it in with real failures would misdirect all the work that follows.
`batch-verify.sh` now classifies it.

#### Techniques established — these are the reusable part

Five, in rough order of how much they unlock. Each came from a failure that no amount of
shim-writing would have reached:

1. **Relocate, then rename** — for vanilla types 1.21 deleted. A mod jar cannot supply a class
   under `net.minecraft.*` (module resolution refuses it, proven in
   `testkit/vanilla-package-probe`), so the stand-in lives in `easyport.vanilla` and references
   are rewritten to it. No structural surgery needed.
2. **Remap SRG inside text** — refmaps, `@At(target=...)`, `@Accessor(...)`. The bytecode
   remapper only reaches real member references; mixins address targets as *strings*. Curios
   carried 22 stale SRG names, yungsapi 38, and every injection point pointed at a member that
   no longer existed. This is why mixin-heavy mods translated cleanly and then died at apply
   time.
3. **Event bridging** — for events NeoForge restructured. The bus dispatches by the posted
   object's exact class, so a mod listening for a Forge event type is unreachable unless
   something posts that type. forge-compat subscribes to the NeoForge event and re-posts the
   Forge shape. Generalises to the whole event layer.
4. **Mixin stripping** — drop mixins whose target class no longer exists. They can never apply,
   and leaving them fails the entire mod. Reported, never silent.
5. **Dependency resolution** — transitive, with bundled jars translated recursively and
   platform-provided libraries dropped.

#### Rename or shim? The rule, learned the expensive way

**A class existing at the same path in NeoForge is not sufficient reason to rename to it.**
`ModLoader` does exist there — the rename resolved cleanly and then failed on
`isDataGenRunning()`, which NeoForge moved elsewhere. Worse, it was a *regression*: before the
rename geckolib loaded, because the unresolved class sat on a path that never ran. Renaming
made the class resolve, so the call was reached and the mod died on the method.

- **Rename** when the loader looks the type up or dispatches by it (annotations, events), or
  when the whole surface is verified identical.
- **Shim** otherwise. A rename fixes a class and cannot fix a signature; a shim adapts.

#### Validation must be conservative about its own knowledge

Rename targets are checked against a platform class index. That check regressed the library set
twice by being confidently wrong — first the FML loader jar was unindexed so `@Mod` was
rejected, then distmarker was unindexed so `OnlyIn` was rejected 105 times.

A target is now only declared absent when the index **demonstrably covers its package**. An
unindexed package degrades to "assume present", i.e. to the behaviour before validation
existed, rather than silently disabling correct renames.


#### Library layer — current standing

The eight highest-fan-in libraries (~70 dependents between them) all still fail, but each is
now blocked on a *different* subsystem rather than a shared shim gap. Six rounds moved every
one of them forward through the load sequence:

| Library | Dependents | Blocker was | Now |
|---|---|---|---|
| `architectury` | 12 | `TextureStitchEvent` | renamed to `TextureAtlasStitchedEvent`; advanced to `EntityItemPickupEvent` |
| `yungsapi` | 10 | mixin `InvalidAccessorException` | unchanged — Phase 7 |
| `cyclopscore` | 10 | `NewRegistryEvent` | renamed; then `ModContainer`; now inherited-handler registration (fixed, unverified) |
| `placebo` | 8 | `eventbus.api.GenericEvent` | shimmed; then networking; now `IModInfo` (fixed by the `forgespi` rule, unverified) |
| `geckolib` | 8 | `network.NetworkRegistry` | shimmed; now a `VerifyError` on `ArmorMaterials` — see below |
| `curios` | 8 | mixin apply | unchanged — Phase 7 |
| `balm` | 6 | `common.world.BiomeModifier` | renamed; now `ICapabilityProvider` (fixed by the capabilities rule, unverified) |
| `supermartijn642corelib` | 8 | mixin apply | unchanged — Phase 7 |

**Every library that was blocked on a missing class has moved at least twice.** None load yet,
but the failures are now deeper in the sequence — construction and verification rather than
linking — which is what progress looks like here.

**`geckolib`'s `VerifyError` is a new failure class worth naming.** 1.21 wrapped static vanilla
registry constants in `Holder`: `ArmorMaterials.IRON` went from an enum constant implementing
`ArmorMaterial` to a `Holder<ArmorMaterial>`, and `ArmorItem`'s constructor changed to match.
Fixing it needs a rule kind that rewrites a *field reference's descriptor*, which does not
exist — the current kinds are `TYPE_RENAME`, `TYPE_PREFIX_RENAME`, `RENAME_METHOD`,
`CTOR_TO_STATIC`, `CTOR_SWAP2` and `REMOVED`, all of which address types or methods. This is
Phase 4 (vanilla bridge) work, and it will not be the only instance of the pattern.

Two of the eight are blocked on mixin *apply* rather than a missing class, which is the hard
Phase 7 problem: the target exists and the injection point inside it does not match. Refmap
remapping fixed the easy half of that; what remains is injection points into methods whose
bodies changed.
#### Dependency resolution — built, and it changed the priorities

`tools/Deps.java` resolves a mod's **transitive** required dependencies; `batch-verify.sh`
translates and loads them alongside the candidate. Direct dependencies were not enough:
`ars_creo` needs `create`, which needs `flywheel`, `ponder` and more.

Three problems surfaced only once dependencies were actually loaded, and none of them are
things a single-jar harness would ever have shown:

1. **Bundled libraries collide.** Mods ship dependencies under `META-INF/jarjar/`. Two mods
   bundling the same library — or NeoForge already carrying it — become separate modules
   exporting one package, and the layer refuses to resolve: *"Modules MixinExtras and
   mixinextras.neoforge export package com.llamalad7.mixinextras"*. That kills the entire
   launch, so it reads as the candidate mod failing rather than as a conflict between its
   dependencies. Platform-provided libraries are now dropped.
2. **Dropping a bundled jar corrupts the jarjar index.** `META-INF/jarjar/metadata.json` lists
   every bundled jar by path; a stale entry surfaces as `Invalid paths argument` and an
   IOException naming the *outer* mod, so `create.jar` reads as corrupt. The index is pruned
   to match. (Regex is the wrong tool here — entries contain nested objects, and a
   character-class pattern matched nothing and emitted an empty index, which is worse than the
   stale one. Brace-depth scan instead.)
3. **Bundled mods were shipped untranslated.** They are ordinary Forge jars carrying
   `mods.toml`, so NeoForge rejects them and the outer mod fails on a dependency that is
   physically inside it — `create` bundles `flywheel` and `ponder` and still reported both
   "not installed". Nested jars are now translated recursively, to a depth of 3.

**The priority this changes: high-fan-in dependencies are worth far more than tail mods.**
`create`, `curios`, `geckolib` and `ars_nouveau` each block many dependents. Fixing one mod in
the tail buys one mod; fixing `create` buys everything that depends on it. `create` currently
fails on genuine vanilla API changes (`ChunkRenderDispatcher`,
`AbstractProjectileDispenseBehaviour`) — Nightmare tier, and squarely Phase 4 work.

#### The harness caps out at 44% of the corpus — now being lifted

Measured across all 288 paired mods: **161 of them (56%) declare inter-mod dependencies.** The
harness loads only the candidate plus support jars, so those can never load regardless of how
good the translation is.

**This is the binding constraint on measuring coverage, and it is a Phase 1 gap rather than a
Phase 3 one.** No amount of forge-compat work moves it. Lifting it means the harness resolving
a mod's dependency graph, translating each dependency too, and loading them together — which
is also the first point at which the tool gets exercised the way a real user would use it, on
a whole modpack rather than one jar.

Until then any corpus-wide coverage number is drawn from a 44% sample, and skewed: mods with
no dependencies are systematically simpler than mods with many.

**Two dependencies live in surprising places**, both found by searching jars rather than
guessing: `com.electronwill.nightconfig.core` (needed by the `IConfigSpec` shim) and
`net.neoforged.api.distmarker.Dist`, which ships inside `mergetool-2.0.0-api.jar`.

#### Removed vanilla types — constraint proven, and the way around it

A mod jar **cannot** supply a class in a vanilla package. Tested directly
(`testkit/vanilla-package-probe`):

```
java.lang.module.ResolutionException: Modules vanillapkgprobe and minecraft
export package net.minecraft.world.level.storage.loot to module easyport_inspector
```

That matters because 1.21 deleted vanilla types some mods *implement* —
`net.minecraft.world.level.storage.loot.Serializer` is a real case (aquaculture), removed when
loot serialization moved to codecs, with no replacement to rename to. Supplying the missing
class is the obvious fix and it is impossible.

**The workaround is to rename rather than supply.** We control the mod's bytecode, so a
removed vanilla type can be `TYPE_RENAME`d to a shim living *outside* `net.minecraft` —
forge-compat owns its own package space and triggers no split. So the category needs no
structural surgery after all: it is ordinary shim work plus a rename, with the shim simply not
allowed to sit where the original did.

Worth having tested rather than assumed. The inference chain (no `module-info`, so an automatic
module, so it owns the package) was right, but it decided a large amount of downstream work and
would have been expensive to get wrong in either direction.

### Phase 1 — COMPLETE

`tools/VerifyHarness.java` + `testkit/inspector/`. Measures whether a translated mod actually
works, by running it and comparing what it registered against the author's own port.

- **Engine:** `runData` — full FML boot, headless, ~10s, no EULA (gotcha #11).
- **Differential:** a baseline launch with only the inspector is subtracted from each run, so
  what remains is exactly what the jar under test contributed. No ignore lists to go stale.
- **Two metrics:** registry coverage (needs a launch) and resource coverage (static, covers
  recipes/tags/loot tables). Both validated at 100% on a self-comparison.
- **Loaded vs. rejected** are distinguished. An empty delta means very different things
  depending on whether the jar ran at all, and both need different fixes.

**Day-zero baseline:** an untranslated Forge jar scores **0% registry / 83.6% resource**. The
launch succeeded and nothing crashed — a crash-only check would have called that a pass while
the mod contributed nothing. That is the entire reason this phase came before the transformer.

The 83.6% is *entirely* the 1.21 singularisation (143 `recipes/`→`recipe/`, 5
`tags/blocks/`→`tags/block/`), so that mod's resource layer is a pure mechanical rename.

Deferred: world-gen smoke test — needs a server (EULA) or client (display). Would catch
runtime crashes during play, which neither coverage metric sees.

**Next: Phase 2, the transformer core.** Rule DSL needs four rule kinds (`RENAME`, `REMOVED`,
`CONTEXTUAL`, `STRUCTURAL`) — see ROADMAP §5 Phase 2 for why a rename table alone fails on the
most common migration in the corpus.

### Phase 0 — COMPLETE

Deliverable was "rule-DSL specification + measured corpus difficulty breakdown". Both exist:
the difficulty breakdown above, and the DSL requirements in ROADMAP §5 Phase 2, derived from
measurement rather than taste.

One loose end, deliberately left: two **(verify)** rows remain in ROADMAP §4 (Forge extension
interfaces, `@Cancelable` semantics beyond the type's existence). Neither blocks Phase 1.

Nothing requires the owner. The EULA question is moot — `runData` needs no EULA (gotcha #11).

**Next: the remapper.** Everything it needs (Mojang official mappings, SRG, Parchment) is a
public download.

---

## Hard-won gotchas — each of these cost real time

1. **SRG contamination — SOLVED, but never mine without the mapping.** Forge 1.20.1 runs SRG
   member names (`m_61124_`); NeoForge 1.21.1 runs official Mojang names. Unmapped, 74.8% of
   lost symbols were pure mapping noise and vanilla mining was meaningless.
   `tools/SrgToOfficial.java` composes Mojang ProGuard mappings (official→obf) with MCPConfig
   `joined.tsrg` (obf→SRG) into 64,225 SRG→official members. Residual contamination: **0.0%**.
   **Always pass `mappings/srg2official.tsv` to RuleMiner** — without it the tool warns, and
   the vanilla numbers measure the mapping rather than the API.

   Two traps inside that composition, both of which produced silently wrong output:
   - Forge bytecode uses **official class names with SRG member names**, so only members need
     remapping; descriptors are already comparable.
   - ProGuard writes class names dotted, TSRG writes them slashed. Fully obfuscated classes
     (`dcv`) have no package so the forms coincide and it looks fine — until a class Mojang
     leaves unobfuscated (`MinecraftServer`) fails to join and silently takes its entire
     member set with it.

2. **Feature drift poisons naive diffing.** Mod versions differ sharply between packs
   (`ae2` 15.4.9 → 19.2.17). Only 5 of 288 pairs share a version. A pair diff mixes genuine
   migration with the author's own new features. **Never accept a rule from one pair.**
   Defenses: cross-mod corroboration (primary), member-level matching, version-distance
   weighting. The 38 low-drift pairs are a *validation* set, not training data.

3. **Co-occurrence alone does not find rules.** `ModConfigSpec$Builder#<init>` appears in every
   config-using mod, so one-directional confidence paired it at 0.98 with everything
   config-related. Needs Jaccard + member-name match + descriptor match together.

4. **`modId=` appears in `[[dependencies.*]]` blocks too.** Parsing TOML without section
   awareness makes every mod claim to provide `minecraft` and `neoforge`, silently corrupting
   all pairings. `CorpusAnalyzer.parseToml` is section-aware; keep it that way.

5. **Most mods leave `version="${file.jarVersion}"`** for Gradle to resolve into the jar
   manifest. Fall back to `Implementation-Version` in `META-INF/MANIFEST.MF`.

6. **`securejarhandler` needs `--add-opens=java.base/java.lang.invoke=ALL-UNNAMED`.** The real
   launcher passes it; standalone harnesses must too.

7. **NeoForge MDK has no `1.21.1` branch.** Use `archive/1.21-mdg` — it is already configured
   for `minecraft_version=1.21.1`, just pin `neo_version` yourself.

8. **NeoForge rejects any jar containing `META-INF/mods.toml`.** `IncompatibleModReason`
   flags it as `MINECRAFT_FORGE`. The resource migrator **must** rename it to
   `META-INF/neoforge.mods.toml` and leave no `mods.toml` behind, or the translated jar is
   refused outright. Note the detection is on the *descriptor file*, not on
   `net/minecraftforge/**` classes — shipping those is fine, and the check only fires for
   jars no reader could handle.

9. **`net/minecraftforge/**` is free real estate.** NeoForge 21.1.248 and FML 4.0.43 ship
   *zero* classes in that package, so `forge-compat` owns it outright with no split-package
   conflict. This is what makes the shim-first architecture viable.

10. **Log from the SERVICE layer with `LoggerFactory.getLogger(...)`, not
    `ILaunchContext.LOGGER`.** The latter produced no visible output, which makes a working
    locator look identical to one that never ran — an expensive thing to debug. A locally
    obtained slf4j logger reaches the console fine.

12. **Build forge-compat with `tools/build-forge-compat.sh`, never inline.** A compile error
    scrolled past inside a backgrounded build-and-batch command; `javac` left an almost-empty
    output directory, `jar` packaged it without complaint, and ten minutes of verification ran
    against a forge-compat containing **one class** — reporting every mod broken including one
    that had been at 100%. The script fails loudly, refuses to package an obviously incomplete
    build, and clears the cached baseline. **Never chain a build and a long test run into one
    backgrounded command and then read only the tail.**

13. **A sealed interface cannot be implemented, not even by a named class.**
    `IConfigSpec.ILoadedConfig` permits only `net.neoforged.fml.config.LoadedConfig`, so the
    obvious wrapper for `ForgeConfigSpec.setConfig` is impossible. Check `PermittedSubclasses`
    with `javap -v` before designing a shim around implementing a NeoForge interface.

11. **`runData` is the cheap live-launch harness.** Datagen exercises the full FML boot,
    including `ModDirTransformerDiscoverer` scanning `run/mods/`, runs headless, and exits on
    its own in ~10s. **No EULA required** — only the dedicated server needs one. Use this for
    Phase 1 verification rather than a client or server launch.

---

## Pinned toolchain

| Component | Version |
|---|---|
| NeoForge | `21.1.248` |
| FML / fancymodloader | `4.0.43` |
| Forge | `1.20.1-47.4.22` |
| NeoForm | `1.21.1-20240808.144430` |
| Parchment | `2024.11.17` (1.21.1) |
| JDK | 21 |

**Rules must target a pinned loader build, not a version line.** The 1.21.1 API still moves
within itself — 21.1.248 already deprecates `EventBusSubscriber.Bus` for removal.

---

## Data and how to regenerate it

Nothing generated is committed except the high-confidence rule subset. The corpus itself is
**never** committed (~2.8 GB of third-party mods under their own licenses).

```bash
# 1. Triage the corpus -> corpus-report/
java tools/CorpusAnalyzer.java "<ATM9>/mods" "<ATM10>/mods" corpus-report

# 2. Mine rules from the pairs -> rule-report/
java -cp devenv/spi/asm.jar tools/RuleMiner.java \
    corpus-report/ground-truth-pairs.tsv "<ATM9>/mods" "<ATM10>/mods" rule-report
```

Full tool docs: [tools/README.md](tools/README.md).

| Path | Contents | Committed |
|---|---|---|
| `corpus-report/` | Pair matching + difficulty triage | yes (metadata only) |
| `rule-report/candidate-rules-strong.tsv` | The 231 rules ≥ 1.0 | yes |
| `rule-report/*-symbols.tsv` | Full ranked symbol lists (~3.5 MB) | no |
| `scrapyard/` | The mod corpus | **never** |
| `devenv/` | MDKs, downloaded jars | no (except `spi-test/src`) |
