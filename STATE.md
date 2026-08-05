# STATE — read this first

Dense re-entry point. If you are picking this up cold (fresh context, new contributor),
read this file and nothing else until you need depth. [ROADMAP.md](ROADMAP.md) has the full
plan; this has where things actually stand.

**Last updated:** 2026-08-04. See the phase sign-off blocks below for status.

---

## Resume here

**Work offline first. Launch only for what offline cannot answer.** This is the single biggest
change to how this project is worked on, so it comes first.

```bash
# Will these classes load? Whole list, translated and type-checked, in about a minute each.
bash tools/verify-sweep.sh < batch-report/phase4.tsv

# What does the corpus still use that nothing resolves? Two reports, two questions.
java -cp "devenv/spi/asm.jar" tools/RenameGaps.java \
    api-report/forge-api-usage.txt rules/forward.rules.tsv mappings/srg2official.tsv \
    forge-compat/forge-compat.jar \
    devenv/neoforge-1.21.1/build/moddev/artifacts/neoforge-21.1.248.jar \
    devenv/spi/loader-4.0.43.jar devenv/spi/bus-8.0.5.jar devenv/spi/distmarker.jar \
    > api-report/unresolved-types.txt

java -cp "devenv/spi/asm.jar" tools/VanillaGaps.java \
    api-report/vanilla-api-usage.txt rules/forward.rules.tsv mappings/srg2official.tsv \
    devenv/neoforge-1.21.1/build/moddev/artifacts/neoforge-21.1.248.jar \
    > api-report/vanilla-gaps.txt
```

`RenameGaps` lists every **Forge** type the corpus references that neither a shim nor a rule
resolves. `VanillaGaps` asks a deliberately different question of **vanilla**, because "does this
type resolve" is the wrong one there — 1.21 mostly kept type names and changed what is inside
them, so the body of that report is members that no longer exist on types that do. Read its
`BY OWNING TYPE` rollup first: it says which *subsystem* to fix.

`verify-sweep` answers something neither report can, and it is where Phase 4's failures actually
showed up. It runs the JVM's own type checks over each translated jar, so it catches a
`VerifyError` — the most expensive failure this project produces, because a launch reports one
method and then stops. It is also the only thing that sees an **illegal class hierarchy**: a mod
extending something 1.21 made final, or implementing something that stopped being an interface.

**A clean sweep means the classes will load. It does not mean the mod works.** The two are not
substitutes; `batch-verify.sh` is what answers the second.

**Read `RenameGaps`' warning sections before its gap list.** A rule that resolves *incorrectly* is
worse than a missing one, because the gap report stops mentioning it and the failure moves to
runtime. The checks caught `ICapabilityProvider` (same name, incompatible shape, 106 jars),
`LivingDamageEvent` (renamed onto an abstract parent nothing posts, 24 jars), a prefix rule
quietly overriding the `ICondition` shim and putting back the `AbstractMethodError` it exists to
prevent, and withdrew a rule written the same hour — `LivingTickEvent` → `EntityTickEvent$Pre`,
right event, but `getEntity()` narrowed its return type and all 41 callers would have broken. See
[api-report/README.md](api-report/README.md).

**Both reports know what the automatic passes handle, and that has to stay true.** The Holder,
`ARG_FILL` and `ARG_DROP` passes discover their targets from the platform's own descriptors
rather than from rules, so nothing in `forward.rules.tsv` marks that work done. Before the reports
were taught about them they showed roughly 1,600 jar-references of *completed* work at the head of
the queue. If you add a pass that needs no rules, teach the reports about it in the same change.

**Adding a corpus, or chasing one mod that will not translate.** The corpus is an *input*, not a
component — every Java tool takes the mods folder as an argument, and the two shell wrappers read
`EASYPORT_SOURCE_MODS` / `EASYPORT_TARGET_MODS`. Re-run `MemberScan` against the new folder and
both gap reports re-rank themselves, so whatever the old corpus never exercised surfaces in
priority order instead of at a launch. A single problem mod needs no corpus at all: translate it,
run `verify-bytecode.sh`, read the report. Full workflow in [tools/README.md](tools/README.md).

What that does *not* carry across is a different Minecraft version. The seven mechanisms are
version-agnostic — they compare a mod against whatever platform jar they are handed — but
`rules/forward.rules.tsv` and `forge-compat/` are specific to 1.20.1 → 1.21.1. Roughly: one more
pack is hours, one more mod is minutes, one more Minecraft version is a phase.

**Two rules of thumb this produced, for events:**

- An event NeoForge *removed* gets a **link-only shim**, never a rename. The mod links and the
  event never fires, which is accurate — there is nothing to fire it. `FillBucketEvent`,
  `AttachCapabilitiesEvent`, `LootingLevelEvent`.
- An event NeoForge *restructured* needs a **shim plus a bridge** — the `TickEvent` shape — not
  a rename. NeoForge renamed the accessors along with the events (`getAmount` →
  `getNewDamage`, `getEntity` → `getPlayer`), and a type rename cannot follow that. The renames
  currently in `forward.rules.tsv` for `LivingHurtEvent`, `LivingDamageEvent`,
  `EntityItemPickupEvent` and friends are **load-enabling stopgaps**: the mod loads and
  registers its content, then throws `NoSuchMethodError` if that event actually fires. Every
  instance is listed by the member check. Converting this family to shim-and-bridge is the
  largest single piece of unfinished event work.

**One shim trick worth knowing.** `Event$Result` (72 jars) had to be supplied as a top-level
class *literally named* `Event$Result` — `$` is a legal Java identifier character, so it
compiles to exactly the internal name mods reference. It could not be nested, because its outer
class `Event` is one of the types that must be renamed to NeoForge's rather than shimmed.

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

**Eight hard rules, each learned by breaking something:**

- **Never chain a build into a backgrounded batch and read only the tail.** A compile error
  scrolled past once and ten minutes of verification ran against a one-class forge-compat,
  reporting every mod broken including one that was at 100%.
- Editing `tools/*.java`, `rules/forward.rules.tsv` or rebuilding `forge-compat.jar` mid-run is
  now safe — `batch-verify.sh` snapshots all three at start and runs from the copies. Tool edits
  corrupted three runs before that existed, and a mid-run rebuild split one results table across
  two shim layers.
- **A batch that reports the same failures as last time may not have run.** It skips mods
  already in `batch-results.tsv` so a long run can resume. It discards that file automatically
  when the rules, forge-compat or `Translate.java` are newer — but the check is mtime-based, so
  a rebuild that happens *during* a run leaves the results looking fresh. `rm
  batch-report/batch-results.tsv` to force. One full batch was read as "both fixes changed
  nothing" when in fact nothing was re-tested.
- **A mod's current failure is the top of a stack, not the whole of it.** The JVM stops at the
  first problem, so fixing it reveals the next. blockui looked like a vanilla failure and had two
  more Phase 3 gaps underneath — that nearly produced a wrong phase sign-off. Never conclude
  "everything left is Phase N+1" from a single run. cyclopscore then proved it again, taking five
  more blockers after the one that had been blocking it since Phase 3.
- **A new way of matching can take away a match that worked.** Teaching the transformer to match
  overloads that add or drop a parameter made `AttributeSupplier$Builder.add(Attribute, double)`
  ambiguous — it now also matched `add(Holder)` by dropping the `double`, so the call was
  correctly refused and geckolib went from loading to `NoSuchMethodError`. The ambiguity check
  worked; the *fix* silently subtracted. **Re-run the whole batch after any matching change, not
  just the mod you were fixing.** Same-arity overloads now win outright.
- **`verify-sweep.sh` and `batch-verify.sh` both write `translated/`.** Running them at the same
  time has one overwriting jars the other is about to launch. Nothing detects it.
- **Check a results file is newer than the run before believing it.** A stale
  `sample-results.tsv` from two hours earlier was read as the current run, and three fixes that
  had in fact applied looked like they had done nothing. `ls -la --time-style=+%H:%M` before
  drawing a conclusion from any batch output.

**The Phase 4 architecture, and the one idea behind all of it.** Phase 4 built the vanilla bridge
and is signed off below. The whole phase turned on a single decision that is worth understanding
before touching any of it, because the obvious alternative was tried first and does not work.

1.21 changed the *shape* of vanilla types that mod signatures are written in terms of. The
tempting fix is to let the new shape spread: retype the field, rename the type, follow it
outward. That fails at the mod's own boundary. `ArmorMaterials.IRON` becoming a
`Holder<ArmorMaterial>` was handled that way for a while, and geckolib still failed one layer in,
because its own `WolfArmorItem` constructor declares `ArmorMaterial`. Chasing that means
propagating a retype through mod signatures by data flow — a whole-program problem.

**Easyport does the opposite: adapt at every vanilla boundary and leave mod code's worldview
exactly as its author compiled it.** Every adaptation is then local to one instruction, and
nothing has to be inferred about the mod. Seven mechanisms fall out of it, and they compose —
`ParticleType` needed a `COERCE`, an argument drop and two stubs in one constructor:

| Mechanism | For | Needs rules? |
|---|---|---|
| Holder unwrap / wrap | 1.21 wrapping a registry constant | No — read off the platform |
| `ARG_FILL` / `ARG_DROP` | A parameter added or removed | Filler needs one |
| `ARG_COLLAPSE` | Several parameters folded into one | Yes |
| `COERCE` | A type that stopped being assignable to what replaced it | Yes |
| `INTERFACE_SUBSTITUTE` | A vanilla interface that became a record | Yes |
| Abstract stub | Abstract methods 1.21 *added* to a class the mod extends | No |
| Final-override rename | An override of a method 1.21 made final | No |

Two of those need explaining, because they look wrong until you hit the case:

- **`COERCE` cannot be triggered by a descriptor.** `ModelResourceLocation` stopped being a
  `ResourceLocation` while every call site kept saying `ResourceLocation` — nothing about the
  instruction is wrong, only the value arriving at it. That pass runs a real type analysis and
  inserts the conversion at the argument, return, or *branch* that disagrees. It has to be a
  branch sometimes: `cond ? new Custom(..) : vanilla()` has nothing at the return to convert,
  because one side is already right.
- **Substitution is never a `TYPE_RENAME`.** Rewriting `implements ArmorMaterial` is right;
  rewriting every mention of `ArmorMaterial` is not, because the mod also *reads vanilla's own
  constants*, which are real records rather than implementations of the substitute.

Where a conversion cannot be reconstructed — a codec that would have to be derived from a mod's
hand-written serializer — the bridge returns a placeholder and the report names it. That trade is
deliberate and always the same way round: the type registers, everything registered beside it
survives, and the specific thing 1.21 added does not work. The alternative is a mod that fails to
load at all, taking its blocks and items with it.

**What remains, ranked by `api-report/vanilla-gaps.txt`:** `ItemStack` NBT→components (1,207
jar-weight, though its four commonest methods -- getTag, getOrCreateTag, setTag, hasTag -- are now
bridged onto the CUSTOM_DATA component), `FriendlyByteBuf` and the `StreamCodec` migration (684),
data-driven `Enchantments` which became `ResourceKey`s (492), `Ingredient` (349), and
`BlockEntity` (312) — that last one is now *only* the override-declaration half, since `ARG_FILL`
handles its call sites. Overrides are the one shape none of the seven mechanisms reaches: a mod
declaring `saveAdditional(CompoundTag)` no longer overrides anything, so it links, loads, and is
never called.

Also outstanding, smaller: `ForgeHooks` (91 jars) and `ForgeEventFactory` (80) were split across
NeoForge's `EventHooks` and `CommonHooks` and need per-method rules rather than a type rename.

The mixin `@Inject` and `@Accessor` failures (yungsapi, supermartijn642corelib) are Phase 5 and
should be left until last.

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

### Phase 4 — COMPLETE

**Exit criterion:** no library or sampled mod is blocked by vanilla API drift that a transformer
can fix. Met for the library layer outright. Two sampled mods still fail, and the honest reading
of each is below.

**The libraries were the point, and they moved.** Three that were blocked on vanilla drift now
load — geckolib had been stuck since Phase 3 on `ArmorMaterials` becoming `Holder`-wrapped, and
cyclopscore took five more blockers after the one it was stuck on. The two that still fail,
yungsapi and supermartijn642corelib, fail on mixin *apply*, which is Phase 5.

The two sampled mods fail differently, and the difference is the useful part:

- **`blockui`** is blocked by the one shape no bytecode rewrite reaches:
  `OutOfJarResourceLocation extends ResourceLocation`, and 1.21 made that a final record. There is
  no version of that class the mod can be. It is **reported at translate time** now rather than
  discovered at launch, which is the deliverable — the transformer names what it cannot do instead
  of producing something that half-works. See the note below on why the obvious fix is a trap.
- **`aquaculture`** is not blocked by anything structural. It is a long chain of individually
  small 1.21 changes, and working it produced eleven general fixes before this sign-off — tool
  constructors, attribute-modifier ids, inherited static fields, the food builder — each of which
  applies to any mod doing the same ordinary thing. It is still peeling. That is what the
  remaining tail looks like, and it is enumerated rather than mysterious.

**The distinction matters more than the count.** One is a wall; the other is a queue.

Evidence, all reproducible:

| Measure | Start of phase | Now |
|---|---|---|
| Libraries loading | 4 / 8 | **6 / 8** |
| Libraries + sampled mods that type-check clean | 5 / 22 | **22 / 22** |
| Vanilla member references that resolve | 89.3% | **92.2%** |
| Vanilla types deleted with no stand-in | 126 | 114 |

**Read the percentage last, not first.** It moved 2.9 points and that understates the phase badly,
because it is dominated by the enormous majority of references that never broke. The rows above
it are the ones that mean anything: what actually changed is that mods which could not load now
load. Of the 535 references the phase fixed, 416 came from the passes that need no rules at all
(`Holder` 234, arity 182), 90 from the type conversions, and only 29 from plain per-member rules.

That is worth knowing before writing more rules: on the vanilla side, the leverage has been in
mechanisms that read the answer off the platform, not in enumerating cases.

#### The one decision the phase turned on

1.21 changed the *shape* of vanilla types that mod signatures are written in terms of. The
tempting fix is to let the new shape spread outward from the field read or the call site. That
was tried, as `FIELD_RETYPE` on `ArmorMaterials`, and it does not stop at the vanilla boundary:
geckolib still failed one layer in, because its own `WolfArmorItem` constructor declares
`ArmorMaterial`. Following that means propagating a retype through mod signatures by data flow.

**Easyport does the opposite. It adapts at every vanilla boundary and leaves mod code's worldview
exactly as its author compiled it.** Every adaptation is then local to one instruction, and
nothing has to be inferred about the mod's own types.

Seven mechanisms fall out of that, and three need no rules at all because they read the answer off
the platform. See the Resume-here section above for the table and for the two that look wrong
until you hit the case.

#### What Phase 4 built beyond the mechanisms

Three offline tools, and they mattered more than any single mechanism:

- **`VanillaGaps`** — the vanilla counterpart to `RenameGaps`, asking a deliberately different
  question. 25,272 member references ranked, and its `BY OWNING TYPE` rollup is what turned "port
  data components, attributes, enchantments, potions" into "these are one problem".
- **`VerifyBytecode` / `verify-sweep.sh`** — the JVM's type checks, offline. A `VerifyError` costs
  a full launch to find, reports one method, and stops; this surveys a whole jar in seconds. It is
  also the only thing that sees an illegal class hierarchy, which nothing in the plan anticipated:
  six classes across four of the twenty-two mods, every one fatal at load. Pointed at a mod that
  is not even in the tested set — ars_nouveau, which several sampled mods depend on — it found 70
  more, of which 38 were one bug in the hierarchy check itself.
- The **differential against the mod author's own port**, without which the verifier is not usable:
  `SimpleVerifier` is not the JVM and reports mismatches the real verifier would not. Two such
  shapes survived a full round of investigation before turning out to be present in the reference
  ports too.

#### Two bugs worth remembering, because everything looked correct

**Constructors are not inherited.** Both offline tools fold supertype members into their view of a
class, which is right for every kind of member except this one.
`DropExperienceBlock(BlockBehaviour$Properties)` was removed in 1.21 and looked present the whole
time, because `Block` declares a constructor of the same shape. The rule written for it matched
nothing, the report said nothing, and the mod failed at load. It survived several rounds precisely
because nothing looked wrong.

**A new way of matching can subtract.** Teaching the transformer to match overloads that add or
drop a parameter made `AttributeSupplier$Builder.add(Attribute, double)` ambiguous — it now also
matched `add(Holder)` by dropping the `double` — so the call was correctly refused and geckolib
went from loading to `NoSuchMethodError`. The ambiguity check worked; the *widening* took
something away. Same-arity overloads now win outright, and the lesson is in the hard rules above.

#### Known-incomplete inside Phase 4, deliberately

**Overrides.** All seven mechanisms adapt *call sites*. A mod that declares
`saveAdditional(CompoundTag)` is not calling anything — it is failing to override something,
because 1.21 added a parameter. The method links, the class loads, and vanilla never calls it.
This is the largest remaining shape and it needs a different kind of pass: rewriting the mod's own
method signature and adapting its body.

**Placeholder conversions.** Where a codec would have to be derived from a mod's hand-written
serializer, the bridge returns something inert and the report names it. The trade is always the
same way round — the type registers and everything registered beside it survives, while the
specific thing 1.21 added does not work. The alternative is a mod that fails to load at all.

**Extending a now-final vanilla class — deliberately not built, and here is the design.** Three
classes hit it: `OutOfJarResourceLocation extends ResourceLocation`, `GradientColor extends
TextColor`, `DataIngredient extends Ingredient`. The mechanism would mirror
`INTERFACE_SUBSTITUTE` exactly — a `SUPERCLASS_SUBSTITUTE` rule pointing the mod's class at a
shim base, plus a `COERCE` converting one back at the vanilla boundary — and all the machinery
already exists.

It was not built because the trade runs the wrong way here. Every other placeholder in this phase
gives up something 1.21 *added*, while the mod keeps doing what it was written to do. These three
classes exist *because of* what they override — reading a resource from outside the jar, blending
a colour — and converting one to a plain vanilla value throws away the only reason the class is
there. That turns "this mod does not load, and the report says why" into "this mod loads and
quietly does not work", which is the failure this project has spent most of its checks avoiding.

Worth revisiting if a mod turns up where the subclass adds state rather than behaviour. Do not
revisit it to make a number go up.

**The ranked remainder** is in [api-report/README.md](api-report/README.md): `ItemStack`
(1,207 jar-weight -- its four commonest NBT methods are bridged onto the CUSTOM_DATA component,
and the weight is the rest of the type), `FriendlyByteBuf` and `StreamCodec` (684), data-driven
`Enchantments` (492), `Ingredient` (349), `BlockEntity` overrides (312).

### Phase 3 — COMPLETE

**Exit criterion:** no library or sampled mod is blocked by a missing or wrong `net.minecraftforge`
shim. Everything still failing fails on vanilla API drift (Phase 4) or mixin application
(Phase 5). Met.

Evidence, all reproducible from `api-report/unresolved-types.txt`:

| Measure | Start of phase | Now |
|---|---|---|
| Referenced Forge types resolved | 336 / 792 | **652 / 792** |
| Unresolved, weighted by jars using them | 2,408 | **~1,400** of 17,771 (**92%** resolved) |
| forge-compat classes | 44 | 92 |
| Libraries loading | 0 / 8 | 4 / 8 |
| Shim members missing that the corpus calls | 179 | 134 (weight 796 → 407) |

**The four libraries still failing, and why none is Phase 3:**

| Library | Blocker | Phase |
|---|---|---|
| `geckolib` | `VerifyError` — `ArmorMaterials` wrapped in `Holder`, propagating into the mod's own signatures | 4 |
| `cyclopscore` | `GenericDirtMessageScreen` removed from vanilla | 4 |
| `yungsapi` | mixin `@Accessor` on `CriteriaTriggers.CRITERIA`, a vanilla field that no longer exists | 5 |
| `supermartijn642corelib` | mixin `@Inject` whose target method moved | 5 |

Each was reached by clearing a chain of real shim gaps first — cyclopscore alone went through
`NewRegistryEvent`, `ModContainer`, inherited `@SubscribeEvent` handlers, and
`IEnvironment.Keys.NAMING` before arriving at a vanilla class.

**What Phase 3 built beyond the shims:** five rule kinds that did not exist
(`METHOD_TO_STATIC`, `FIELD_RETYPE`, `FIELD_TO_STATIC`, plus the mixin-drop cases for
kind-mismatch and unresolvable `@Shadow`), three offline analysis tools, and four
silent-failure checks. The checks matter more than the shim count: they caught
`ICapabilityProvider`, `LivingDamageEvent`, `LivingTickEvent` and `IContainerFactory` — four
rules that resolved cleanly and would have failed at runtime, three of them written the same day.

**Known-incomplete inside Phase 3, deliberately:** 134 shim members and 427 rename-target
members the corpus calls that are still missing. These are latent `NoSuchMethodError`s at call
sites, not load blockers, and the largest cluster of them is the 1.20.5 NBT-to-components
migration — Phase 4 work wearing a Phase 3 hat. They are enumerated and ranked rather than
unknown.

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

### Phase 3 — forge-compat, working notes (superseded by the sign-off above)

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

**`architectury` is the first library to load.** It is also the highest-fan-in one — 12
dependents — and it took nine consecutive blockers to get there: `TickEvent`,
`TextureStitchEvent`, `EntityItemPickupEvent`, `FillBucketEvent`, `Event$Result`,
`NetworkRegistry`, `EventNetworkChannel`, `NetworkEvent$ClientCustomPayloadEvent`, and finally
`ForgeRegistries$Keys`.

**Read its 100% honestly.** Architectury registers exactly one entry — a biome modifier
serializer — and the translated jar reproduced that one entry. The reference port registers one
too, so the comparison is fair, but the denominator is 1. What is genuinely proven is that a
mod can now translate, load, and register content end to end. Its resource coverage is 0/1,
which is a separate unexplained gap worth a look.

| Library | Dependents | Status | Current blocker | Kind |
|---|---|---|---|---|
| `architectury` | 12 | **OK, 100%** | — | loads and registers |
| `balm` | 6 | **OK, 100%** | — | loads and registers |
| `placebo` | 8 | **OK, 0%** | loads, registers nothing | see below |
| `geckolib` | 8 | fails | `VerifyError` on `ArmorMaterials` | vanilla `Holder` wrapping; needs a field-descriptor rule |
| `cyclopscore` | 10 | fails | `IEnvironment$Keys.NAMING` | modlauncher API change |
| `yungsapi` | 10 | fails | mixin `InvalidAccessorException` | Phase 5 |
| `curios` | 8 | fails | mixin apply | Phase 5 |
| `supermartijn642corelib` | 8 | fails | mixin apply | Phase 5 |

**Three of eight now load.** Both 100% figures have a denominator of 1 — architectury and balm
each register a single biome modifier serializer, and their reference ports register one too.
Fair comparisons, tiny samples. What is proven is the end-to-end path, not completeness.

### Dropping a dead mixin can silently delete registrations

`placebo` loads cleanly and registers *nothing*, where the reference registers one loot pool
entry type. Traced, and the cause is our own mitigation:

```
LootTablesMixin  (dropped: target LootDataManager removed in 1.21)
  └─> LootSystem            referenced by nothing else in the jar
        └─> StackLootEntry  static initialiser
              └─> Registry.register(BuiltInRegistries.LOOT_POOL_ENTRY_TYPE, ...)
```

The registration lives in a static initialiser, which only runs when something touches the
class. The dropped mixin was the only path in. Stripping it kept the mod loading — that is what
mixin-stripping is for — and converted a crash into a mod that loads and quietly does less.

**This is the failure mode the project most wants to avoid, produced by the fix for a different
one.** Generalise the suspicion: every stripped mixin may be the sole entry point to a code
path, and the translate report's `MIXIN_DROP` line is the only warning.

Worth building: after computing `deadMixins`, report which classes become unreachable — a class
referenced only from dropped mixins is a strong signal that content will silently disappear.
That is a static reachability walk over the jar, cheap, and it turns this from a thing found by
tracing one mod into a thing the report says up front.

The proper fix for placebo specifically is retargeting the mixin: 1.21 restructured loot table
loading rather than deleting it. Phase 5.

Resource coverage is measured without launching: `placebo` 92.3%, `curios` 73.1%,
`supermartijn642corelib` 71.4%, `cyclopscore` 43.2%, `balm` 16.7%.

#### Sample layer — 14 mixed mods

Run with everything above in place. `batch-report/libs-results.tsv` holds the library run.

| Status | Count | Meaning |
|---|---|---|
| `OK` | 1 | `additional_lights` — 100% registry, 100% resource |
| `NO_CONTENT` | 3 | behaviour-only mods; the reference registers nothing either, so coverage is undefined rather than zero |
| `DEPS_UNTRANSLATABLE` | 5 | a required library loads but fails |
| `DEPS_MISSING` | 2 | dependency absent from the harness, not a translation failure |
| `LAUNCH_FAILED` | 3 | real blockers |

**Half the sample is gated on the library layer**, which is the argument for continuing to work
libraries before tail mods. Those five will move as geckolib, curios and cyclopscore do.

The three real failures are all *vanilla*-side, not loader-side — the shim work has moved the
frontier:

- `alltheores` — `DropExperienceBlock` constructor changed
- `aquaculture` — `IncompatibleClassChangeError` on its armor material class, the same
  `ArmorMaterials`-became-`Holder` change that blocks geckolib
- `blockui` — `OutOfJarResourceLocation` cannot extend `ResourceLocation`, which became a final
  record in 1.21

The `ArmorMaterials` pattern now blocks two of eight sampled mods and one library.

`blockui` is the one worth remembering, because it was *not* vanilla when the sample first ran.
It failed on `EventBusSubscriber$Bus.bus()`, then on `Bus.FORGE` — both genuine Phase 3 gaps.
The enum must be renamed (the loader scans the annotation, so a shimmed one would never be
found), and NeoForge dropped the accessor and renamed the constant to `GAME`; both are
redirected to `BusBridge` now. Only then did the real blocker appear.

**Do not read a mod's current failure as its only failure.** Each is a stack, and the top of it
is whatever the JVM happened to reach first. Phase 3 could not be signed off on the *first*
sample run for exactly this reason.

#### The `Holder` wrapping goes deeper than a field descriptor

`FIELD_RETYPE` is implemented and works — `ArmorMaterials.IRON` now reads as `Holder`, and
`ArmorItem`'s constructor is retyped to match. geckolib still fails, one layer further in:

```
Type 'net/minecraft/core/Holder' is not assignable to 'net/minecraft/world/item/ArmorMaterial'
```

The remaining mismatch is in geckolib's **own** class. `WolfArmorItem` declares a constructor
taking `ArmorMaterial`, and the call site now passes a `Holder`. The vanilla type change
propagates through mod signatures, so fixing the vanilla side is necessary and not sufficient.

Three options, none free:

1. `TYPE_RENAME ArmorMaterial -> Holder`. One line, rewrites mod signatures too. Wrong for any
   mod that calls methods *on* an `ArmorMaterial`, which would then be called on a `Holder`.
2. Propagate the retype through mod signatures by data flow — retype a parameter when every
   caller now passes the new type. Correct, and a real analysis pass.
3. Leave it. Two mods in the sample plus geckolib.

Worth knowing before picking: this is the same shape as the 1.20.5 `Codec`/`MapCodec` split and
the data-component rewrite. A vanilla type changing under mod signatures is Phase 4's central
problem, not a special case — so option 2 is probably the real answer and should be designed
once for all three.

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
Phase 5 problem: the target exists and the injection point inside it does not match. Refmap
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

14. **Constructors are not inherited — never resolve one through the class hierarchy.** Both
    offline tools fold supertype members in, which is right for every member except this one.
    `DropExperienceBlock(Properties)` was removed in 1.21 and looked present because `Block`
    declares a constructor of the same shape, so the call was judged fine, reported as fine, and
    failed at load with `NoSuchMethodError`. It survived several rounds because everything about
    it looked correct: the rule existed, the tool ran, the report said nothing. Constructors are
    now checked against what the owning type itself declares, in `Translate#applyWrapAdapters`
    and `VanillaGaps`.

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
