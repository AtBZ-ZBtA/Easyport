# Tools

Standalone analysis utilities. Each is a single Java file that runs directly on JDK 21 with
no build step — `java tools/Foo.java`, no Gradle, no Maven, no project setup.

They are deliberately dependency-light so they keep working when the rest of the project is
mid-refactor.

---

## CorpusAnalyzer

Compares two folders of mod jars and works out which mods appear in both.

```bash
java tools/CorpusAnalyzer.java "<sourceModsDir>" "<targetModsDir>" [outputDir]
```

Mods present in **both** folders were ported by their own authors. Those matched pairs are
the ground truth every translation rule is derived from — the reason the corpus is the
engine of this project rather than just its test set.

**Dependencies:** none. **Needs:** JDK 21. No Minecraft install, no network. Reads jars,
never writes to them.

### What it does

- Matches mods by **declared `modId`, not filename.** Filenames vary wildly across versions
  and packagers; the declared id is the only stable identity a mod has.
- Reads `META-INF/mods.toml` (Forge), `META-INF/neoforge.mods.toml` (NeoForge), and
  `fabric.mod.json`.
- Triages each jar: TRIVIAL / MODERATE / HARD / NIGHTMARE, driven by **mixin class count**
  and coremod presence — not by mixin *config* count, since one config can list eighty mixins
  and it is the individual injection points that break across a version gap.

### Output

| File | Contents |
|---|---|
| `ground-truth-pairs.tsv` | Mods in both versions — the rule-derivation set |
| `corpus-manifest.tsv` | Every jar scanned, with difficulty and mixin/coremod counts |
| `unpaired.tsv` | Mods in only one version |

Unpaired mods are **not** junk. Source-only mods are the case the finished tool must handle
with no reference port available; target-only mods are backward-direction targets. Don't
prune either folder before running.

### Traps this tool already handles

- **`modId=` also appears in `[[dependencies.*]]` blocks.** Parsing TOML without tracking
  sections makes every jar claim to provide `minecraft` and `neoforge`, which silently
  corrupts every pairing. `parseToml` only reads `[[mods]]` blocks. Keep it that way.
- **`version="${file.jarVersion}"`** is a Gradle placeholder most real mods ship. Falls back
  to `Implementation-Version` in the jar manifest.

---

## RuleMiner

Mines translation rule candidates from author-ported mod pairs.

```bash
java -cp <asm.jar> tools/RuleMiner.java \
    <pairs.tsv> <sourceModsDir> <targetModsDir> [outputDir]
```

**Dependencies:** ASM (any 9.x). `devenv/spi/asm.jar` if you have run the SPI spike;
otherwise fetch `org.ow2.asm:asm:9.5`.

### How it works

For each pair it reads every bytecode reference the mod makes into `net/minecraft`,
`net/minecraftforge`, and `net/neoforged`, then diffs the two sides. A symbol on the source
side but not the target is a candidate **lost** API; target-only is a candidate **gained**
API.

**A single pair's diff means nothing.** Mod versions drift heavily between the two packs
(`ae2` goes 15.4.9 → 19.2.17), so most of any one diff is the author's own feature work, not
migration. Signal comes from **corroboration** — a real API migration appears in dozens of
unrelated mods; an added feature appears once.

### Scoring

Co-occurrence alone does not work. `ModConfigSpec$Builder#<init>` appears in every
config-using mod, so one-directional confidence pairs it at 0.98 with every config symbol
regardless of any real relationship. Three signals together discriminate:

- **Jaccard overlap** — unlike confidence, penalises a gained symbol far more widespread than
  the lost symbol it is matched against.
- **Member-name match** — most of these migrations move a member between owners and keep its
  name (`ForgeConfigSpec$Builder#comment` → `ModConfigSpec$Builder#comment`). Strong signal
  in this domain.
- **Descriptor match**, after collapsing loader namespaces.

`score = jaccard × (1 + nameMatch + 0.6 × descMatch)`. Rules scoring **≥ 1.0** are worth
reading. Tunables at the top of the file: `MIN_CORROBORATION` (default 5 mods),
`CORRELATION_WIDTH` (default top 250 per side — the pass is quadratic).

### Output

| File | Contents |
|---|---|
| `candidate-rules-strong.tsv` | Rules scoring ≥ 1.0 — **start here** |
| `candidate-rules.tsv` | All scored candidates (~13 MB, mostly noise) |
| `lost-symbols.tsv` | Source APIs ranked by how many mods use them — **the shim work list, in build order** |
| `gained-symbols.tsv` | Target APIs ranked the same way |

### Required: the SRG mapping

Pass `mappings/srg2official.tsv` as the fifth argument. Without it the tool warns loudly and
the vanilla numbers measure the mapping rather than the API — see below.

---

## SrgToOfficial

Builds the SRG → official member table that RuleMiner needs.

```bash
java tools/SrgToOfficial.java <mojang-client.txt> <joined.tsrg> mappings/srg2official.tsv
```

**Inputs** (both public downloads, ~14 MB total, not committed):

- Mojang official mappings for 1.20.1 — ProGuard format, `official -> obfuscated`. Follow
  `piston-meta` version manifest → `1.20.1` → `downloads.client_mappings`.
- MCPConfig `config/joined.tsrg` — TSRG2, `obfuscated -> SRG`. From
  `de.oceanlabs.mcp:mcp_config:1.20.1@zip`.

No published mapping goes SRG → official directly, so the two are composed on the
obfuscated middle and inverted. Output is committed (`mappings/srg2official.tsv`, 64,225
members) so mining is reproducible without re-downloading.

**Two traps, both of which produce silently wrong output rather than errors:**

- Forge bytecode carries **official class names with SRG member names**, so only members need
  remapping. Descriptors are already comparable.
- ProGuard writes class names dotted, TSRG slashed. Fully obfuscated classes (`dcv`) have no
  package so the forms coincide and it appears to work — until a class Mojang leaves
  unobfuscated (`MinecraftServer`) fails to join and takes its whole member set with it.

---

## ResourceMiner

The resource-layer counterpart to RuleMiner: mines migration rules from everything in a jar
that isn't bytecode.

```bash
java tools/ResourceMiner.java <pairs.tsv> <sourceModsDir> <targetModsDir> [outputDir]
```

**Dependencies:** none.

Mines three things, all ranked by how many independent mods agree:

| Output | Contents |
|---|---|
| `directory-deltas.tsv` | Resource directory renames — 1.21 singularised the datapack tree |
| `descriptor-deltas.tsv` | `mods.toml` → `neoforge.mods.toml` key changes |
| `json-key-deltas.tsv` | JSON schema key changes per resource category |

### Two things it gets right that a naive version wouldn't

**Category canonicalisation.** 1.21 renamed `recipes` → `recipe`, `advancements` →
`advancement`, and so on. Comparing raw directory names makes *every* 1.21.1 key look added
and every 1.20.1 key removed — the tool silently measures the rename instead of the schema.
Trailing `s` is stripped per segment, identically on both sides.

**Share-based key filtering.** Most keys in a datapack file are author data, not schema —
criterion names like `has_iron_ingot`. A raw count cannot separate them. Schema keys appear
in a large share of mods on one side and collapse on the other, so a key is only reported
when it crosses 25% on one side and falls below 5% on the other.

### Known limitation: no nested context

Key analysis is flat, so a change in *where* a key appears is invisible. If a recipe's
`result.item` became `result.id` while `item` remained valid inside ingredients, this tool
cannot see it — `item` is still common on both sides. Nested-path analysis would be needed to
catch that class of change.

---

## Translate

The transformer. Rewrites a Forge 1.20.1 jar toward NeoForge 1.21.1.

```bash
java -cp "devenv/spi/asm.jar;devenv/spi/asm-tree.jar;devenv/spi/asm-commons.jar" \
    tools/Translate.java <inputJar> <outputJar> mappings/srg2official.tsv rules/forward.rules.tsv
```

**Dependencies:** ASM 9.7 core + tree + commons. **All three must be the same version** —
mixing a cached `asm-tree` with a different `asm` core produces `NoSuchMethodError` deep
inside `ClassReader`, far from the actual cause.

### What it does, and deliberately doesn't

Three passes: SRG→official member names, then rules, then resources. The SRG pass must run
first because every rule is written against official names.

It rewrites as little as possible. Under shim-first, most `net.minecraftforge.*` references
need no rewriting — they resolve against `forge-compat` unchanged. Rewriting is reserved for
cases where a shim is impossible.

**The boundary:** anything the *loader* looks up or dispatches by name must be rewritten;
anything a mod merely *calls* can be shimmed. Annotations (`@Mod`, `@SubscribeEvent`) and
event classes fall on the rewrite side — FML scans for exact descriptors and the bus
dispatches by exact class, so a shimmed copy is simply a different type that never matches.

Type renames are therefore an explicit allowlist in the rule file, **not** a blanket
`net.minecraftforge` → `net.neoforged` rename, which would defeat the shim layer entirely.

### Rule kinds

| Kind | Fields | Use |
|---|---|---|
| `TYPE_RENAME` | from, to | Loader-scanned annotations and dispatched event classes |
| `TYPE_PREFIX_RENAME` | fromPrefix, toPrefix | A package moved wholesale |
| `RENAME_METHOD` | owner, name, desc, newOwner, newName, newDesc | Same shape, different owner or name |
| `METHOD_TO_STATIC` | owner, name, desc, newOwner, newName, newDesc | Instance call becomes static, receiver as first arg |
| `FIELD_RETYPE` | owner, newFieldDesc | Every field on a holder class changed type — **currently unused**, see below |
| `FIELD_TO_STATIC` | owner, name, desc, newOwner, newName, newDesc | Deleted constant, value still computable |
| `CTOR_TO_STATIC` | owner, ctorDesc, factoryName, factoryDesc, [factoryOwner] | Constructor became a static factory |
| `CTOR_SWAP2` | owner, oldDesc, newDesc, [narrowTopTo] | Two constructor arguments reordered |
| `COERCE` | from, to, bridgeOwner, bridgeName | A type stopped being assignable to what replaced it |
| `INTERFACE_SUBSTITUTE` | platformType, substitute | A vanilla interface became a record |
| `ARG_FILL` | paramType, bridgeOwner, bridgeName | 1.21 added a parameter the call site cannot supply |
| `REMOVED` | symbol | No replacement — reported, never rewritten |

`METHOD_TO_STATIC` and `FIELD_TO_STATIC` exist for the case neither a rename nor a shim can
reach: a type that *must* be renamed, because the loader dispatches on it and a shimmed copy
would never fire, whose replacement is missing something the corpus uses. Both are cheap because
the stack already suits them — INVOKEVIRTUAL leaves the receiver exactly where INVOKESTATIC
reads its first argument, and GETSTATIC and a no-arg INVOKESTATIC each push one value.

**Rule owners for these three are written POST-rename.** They run after the type remapper, so
the owner is already `net/neoforged/...` even though the mod was compiled against
`net/minecraftforge/...`.

`CTOR_TO_STATIC` removes the `NEW`/`DUP` pair and switches `INVOKESPECIAL` to `INVOKESTATIC`. Its
optional fifth field names the class the factory lives on — usually the constructor's own class,
because vanilla supplied the replacement itself (`ResourceLocation.fromNamespaceAndPath`), but
sometimes a bridge, because there is no replacement. `AttributeModifier` collapsed a UUID and a
display name into a single `ResourceLocation`: two parameters into one, which no argument-level
rule can express.
`CTOR_SWAP2` inserts a `SWAP` (and a `CHECKCAST` first, if the new signature also narrows a
type — after the swap that value is buried). It refuses wide arguments, since `long` and
`double` occupy two stack slots and `SWAP` would corrupt them.

`REMOVED` exists so the transformer can be honest. Inventing a plausible target for an API
that no longer exists is the measured failure mode from `handport/` — 5 false positives out
of 26 symbols — and a jar that loads while quietly doing the wrong thing is worse than one
that refuses.

### The Phase 4 kinds, and the passes that need no rules at all

`COERCE`, `INTERFACE_SUBSTITUTE` and `ARG_FILL` all address the same migration seen from
different angles: a vanilla type changed shape underneath code that was compiled against the old
one. They compose deliberately — `ParticleType` needed a `COERCE` *and* an argument removal in
one call.

- **`COERCE`** converts a value at the vanilla boundary. Unlike every other kind, its trigger is
  not a descriptor: `ModelResourceLocation` stopped being a `ResourceLocation` while every call
  site kept saying `ResourceLocation`, so nothing static about the instruction is wrong and only
  following the value finds it. That pass runs a type analysis over each method and inserts the
  conversion where an argument, a return, or a branch feeding one disagrees.
- **`INTERFACE_SUBSTITUTE`** rewrites what a class implements, and *only* that. A `TYPE_RENAME`
  would also rewrite reads of vanilla's own constants, which are real records rather than
  implementations of the substitute — the mod would then fail verification somewhere else.
- **`ARG_FILL`** supplies a parameter 1.21 added, at whichever position explains the new
  signature. An ambiguous position is refused and reported: inserting in the wrong one produces a
  call that links and hands every argument to the wrong parameter.

`FIELD_RETYPE` is still implemented and deliberately has no uses left. It was how `ArmorMaterials`
was handled before the Holder passes existed, and it is the mechanism this project decided against:
it retypes the field read and lets the new type spread outward, which does not stop at the vanilla
boundary. Adding one back for a type the Holder pass covers silently disables that pass, because a
field already retyped no longer looks like a gap — `forward.rules.tsv` says so at the point where
the old rules used to be.

Three passes need no rules, because they read the answer off the platform:

| Pass | Does |
|---|---|
| Holder unwrap / wrap | Converts at every boundary where 1.21 wrapped a registry constant. The family is discovered from the platform's own descriptors, so it never needs updating |
| Illegal hierarchy | Renames an override of a method 1.21 made final; reports an extends/implements that became impossible |
| Abstract stub | Implements abstract methods 1.21 *added* to a class the mod extends |

The abstract-stub pass rests on one observation: a mod compiled against 1.20.1 implemented
everything abstract then, so anything unimplemented now was added since. There is no case where
the author meant to leave one out. It still has to track which ancestor implements what — an
early version stubbed `Block.asBlock()` to null on every block, because `BlockBehaviour` declares
it abstract and `Block` implements it.

Stubs and placeholder codecs are a deliberate trade, and always reported: the type registers and
everything registered beside it survives, while the specific thing 1.21 added does not work.
The alternative is a mod that fails to load at all.

### The report

Every run writes `<output>.report.tsv` listing what was applied and what was left
unresolved. Unresolved entries are the point: they name exactly what to add next.

---

## batch-verify.sh

Translates and verifies a list of ground-truth pairs, appending results as they land.

```bash
awk -F'\t' 'NR>1 && $2=="TRIVIAL" {print $1"\t"$9"\t"$10}' \
    corpus-report/ground-truth-pairs.tsv | head -20 > batch-report/sample.tsv
bash tools/batch-verify.sh < batch-report/sample.tsv
```

Input is `modId<TAB>sourceJar<TAB>targetJar`. Output accumulates in
`batch-report/batch-results.tsv`, and already-completed mods are skipped, so a long run can
be interrupted and resumed. Per-mod translate and verify logs land beside it — those logs are
the work queue for expanding `forge-compat`, since each failure names the missing class.

The baseline is computed once and cached in the report directory, so each mod costs two
launches rather than three. **Delete `batch-report/baseline.json` after changing
forge-compat**, or the cached baseline will no longer match what the runs actually load.

---

## VerifyHarness

Measures whether a translated mod actually works, by running it.

```bash
java tools/VerifyHarness.java <runtimeDir> <inspectorJar> <candidateJar> [referenceJar] [outDir]

# example
java tools/VerifyHarness.java devenv/neoforge-1.21.1 testkit/inspector/inspector.jar \
    translated/foo.jar "<ATM10>/mods/foo.jar" verify-report
```

**Dependencies:** a built NeoForge MDK at `<runtimeDir>` and the inspector jar (below).

### Why it measures registry content, not load success

"The jar loaded without crashing" is the weak test, and it passes for mods that are badly
broken — half their blocks missing, an entity type silently dropped. So the harness compares
what the candidate *registered* against what the author's own port registered, entry by
entry. That number is the coverage metric the project steers by.

Verified against a real Forge 1.20.1 jar: the launch succeeded, nothing crashed, and the mod
contributed **zero of 326** expected entries. A crash-only check would have called that a pass.

### How it works

1. **Baseline launch** with only the inspector, to capture what NeoForge and the harness
   register on their own.
2. **Candidate launch**, subtract the baseline. What remains is exactly what the jar under
   test contributed — no hardcoded ignore lists to drift out of date.
3. **Reference launch** with the author's port, same subtraction.
4. Compare.

Launches use `runData`: full FML boot including mods-folder discovery, headless, ~10s, and
**no EULA** — only a dedicated server needs one.

### Loaded vs. ran

The harness distinguishes "the candidate loaded and registered nothing" from "the candidate
was rejected and never ran at all". Both look like an empty delta but need completely
different fixes. It compares the modId the jar declares against the loaded mod list the
inspector dumps.

### Two coverage numbers

| Metric | Needs a launch | Covers |
|---|---|---|
| **Registry coverage** | yes | Blocks, items, entities, creative tabs, sounds — anything registered at runtime |
| **Resource coverage** | no | Recipes, tags, loot tables, models — plain files in the jar |

Resource coverage compares paths verbatim, deliberately: a translated jar that kept a
`recipes/` directory instead of renaming to `recipe/` shows up as missing content, exactly
as the game would treat it.

Extra entries are reported but not counted as failures — they are usually the author adding
features between versions, which is drift rather than a translation bug.

### What the coverage metric cannot measure

**Mods that register nothing.** Behaviour-only mods — AI tweaks, performance patches, UI
changes — have an empty registry delta on *both* sides, so registry coverage is undefined
rather than zero. `AI Improvements` is one: its author's own NeoForge port also contributes 0
entries. For these, "loaded successfully" is the only available signal, and treating their 0%
as a translation failure would understate coverage badly.

Since roughly half the corpus is Hard/Nightmare tier and much of that is behaviour mods, this
is not a rare edge case. A separate signal — behavioural smoke tests, or at minimum a
loaded-vs-crashed count — is needed before a corpus-wide coverage number means anything.

**Mods needing other mods.** A mod whose dependencies are absent fails to load for reasons
unrelated to translation. `batch-verify.sh` classifies these `DEPS_MISSING` and they must be
excluded from the denominator; 5 of the first 14 sampled mods fell here.

**Runtime behaviour.** A world-gen smoke test would catch crashes during actual play, which
neither metric sees. It needs a server launch (EULA) or a client (display), so it is deferred
rather than dropped.

---

## Inspector (`testkit/inspector`)

The probe VerifyHarness relies on. A small NeoForge mod that dumps every registry's contents
plus the loaded mod list to `easyport-inspection.json` in the game directory.

Walks the registry-of-registries rather than a hardcoded list, so registries added or renamed
between versions are picked up without editing it. Vanilla content is excluded by default;
`-Deasyport.inspect.includeVanilla=true` keeps it.

Rebuild after changes:

```bash
cd testkit/inspector && javac -cp "<neoforge>;<bus>;<loader>;<slf4j>;<modlauncher>;<asm>;<guava>;<dfu>" \
    -d out src/easyport/inspector/*.java && cp -r src/META-INF out/ && jar cf inspector.jar -C out .
```

`dfu` is DataFixerUpper — needed because `BuiltInRegistries.REGISTRY` exposes
`com.mojang.serialization.Keyable`.

---

## RuleMiner: vanilla results without the mapping

**74.8% of lost symbols carry SRG names** (`m_61124_`). Forge 1.20.1 runs SRG at runtime;
NeoForge 1.21.1 runs official Mojang names. Every vanilla member therefore differs for
reasons that have nothing to do with the API changing.

**Loader-API results are unaffected** — Forge's own API is not obfuscated — which is why the
tool reports the two separately and why the loader rules are usable today.

Vanilla mining stays blocked until the source side is remapped SRG → Mojang first. That
remapper is the project's critical path.

---

## The offline analysis tools — **start here**

`UsageScan`, `MemberScan` and `RenameGaps` answer, without launching the game, what the
transformer still cannot translate. They replaced the loop that found missing classes by
launching, reading the first `ClassNotFoundException`, fixing it and launching again — about ten
minutes per class, strictly one at a time because the JVM stops at the first. Architectury alone
walked through nine blockers that way.

Every input to that answer is static. The first `RenameGaps` run resolved 196 types in one edit.

### UsageScan — how many jars reference a symbol

```bash
java tools/UsageScan.java "<corpus-dir>" "net/minecraftforge/network/NetworkRegistry" "addGenericListener"
```

Searches raw class bytes for the symbol as a substring. Crude on purpose: every class name,
method name and descriptor a class references lives in its constant pool as plain bytes, so a
substring search has no false negatives for the thing being asked about, and a false positive
would need a string literal that happens to contain an internal name.

Counts **jars, not call sites** — one mod calling something forty times is one mod's worth of
evidence.

### MemberScan — which members of a package the corpus calls

```bash
java -cp "devenv/spi/asm.jar;devenv/spi/asm-tree.jar" tools/MemberScan.java "<corpus-dir>" "net/minecraftforge/" > api-report/forge-api-usage.txt
```

The input to every shim. Writing a shim from memory of an API covers the methods that come to
mind and misses the ones that do not; the corpus then fails on the difference, one verify cycle
at a time. This turns a shim from a guess into a transcription.

Includes `invokedynamic` bootstrap arguments, so method references — `Foo::decode` passed as a
decoder, the dominant idiom in networking — are not missed. They are constant-pool handles rather
than instructions, and an opcode-only walk would silently under-report exactly the members most
worth knowing about.

### RenameGaps — what is still unresolved, and what is resolved *wrongly*

```bash
java -cp "devenv/spi/asm.jar" tools/RenameGaps.java \
    api-report/forge-api-usage.txt rules/forward.rules.tsv mappings/srg2official.tsv \
    forge-compat/forge-compat.jar \
    devenv/neoforge-1.21.1/build/moddev/artifacts/neoforge-21.1.248.jar \
    devenv/spi/loader-4.0.43.jar devenv/spi/bus-8.0.5.jar devenv/spi/distmarker.jar \
    > api-report/unresolved-types.txt
```

**Read the warning sections before the gap list.** A rule that resolves incorrectly is worse than
a missing one: the gap report stops mentioning it and the failure moves to runtime.

| Section | Catches |
|---|---|
| `RENAME TARGET MISSING A CALLED MEMBER` | The rule resolves; the target lacks what the corpus calls. Caught `ICapabilityProvider` — same name, incompatible shape, 106 jars implement it |
| `SHIM MISSING A CALLED MEMBER` | Same question asked of forge-compat. Caught `LazyOptional` taking `java.util.function` types where Forge declared its own `NonNull*` — different methods to the JVM |
| `RENAMES ONTO AN ABSTRACT TYPE` | NeoForge split a concrete event and kept the name as an abstract parent. Caught `LivingDamageEvent`, where 24 jars would have registered listeners that never fire |
| `SHIMS SHADOWED BY A RULE` | A broad prefix rule quietly disabling a shim |

It mirrors `Translate` exactly, and getting that wrong made it useless twice:

- Rules are tested **before** shims, because that is the order `Translate` uses.
- A rename is only counted when the target **exists** — `Translate` refuses one that does not.
- Member names are mapped **SRG → official** first. Skipping that compared `m_246326_` against a
  class declaring `addPotionTab`: 90 of 495 findings were noise.
- Members already redirected by `RENAME_METHOD` / `METHOD_TO_STATIC` are skipped, or the report
  re-accuses work already done.

Narrow beats complete. Flagging *every* abstract target produced 91 hits, nearly all correct
interfaces like `IItemHandler`; a warning list that size gets skimmed and ignored.

### VanillaGaps — the same question asked of *vanilla*

```bash
java -cp "devenv/spi/asm.jar;devenv/spi/asm-tree.jar" tools/MemberScan.java "<corpus-dir>" "net/minecraft/" > api-report/vanilla-api-usage.txt

java -cp "devenv/spi/asm.jar" tools/VanillaGaps.java \
    api-report/vanilla-api-usage.txt rules/forward.rules.tsv mappings/srg2official.tsv \
    devenv/neoforge-1.21.1/build/moddev/artifacts/neoforge-21.1.248.jar \
    > api-report/vanilla-gaps.txt
```

`RenameGaps` asks whether a type resolves. For `net.minecraft` the answer is almost always yes
and it is the wrong question — 1.21 mostly kept type names and changed what is inside them.
`ItemStack` still exists and no longer has `getTag`. Pointed at vanilla, `RenameGaps` reports a
clean bill of health on a corpus that cannot link.

So this inverts the emphasis: missing types are a footnote, and the body of the report is members
that no longer exist on types that do.

| Section | Means |
|---|---|
| `SIGNATURE CHANGED` | The name survives, the descriptor does not. Prints the platform's real descriptors alongside, because the difference usually names the type that moved |
| `MEMBER GONE` | No member of that name survives. Needs a rule, a bridge, or a `REMOVED` entry |
| `TYPE GONE` | The class was deleted. Relocate-then-rename |
| `BY OWNING TYPE` | Jar-weighted rollup. **Read this first** — it says which *subsystem* to fix, and forty one-jar findings on one class beat one forty-jar finding on a class nothing else uses |

It knows about the Holder passes, which is the difference between a queue and a wish list. The
transformer discovers that family from the platform's own descriptors rather than from rules, so
nothing in `forward.rules.tsv` marks any of it done — and a report that only understood rules put
about 1,400 jar-references of already-finished work at the top of its list.

### VerifyBytecode / verify-bytecode.sh / verify-sweep.sh — the JVM's own checks, offline

```bash
bash tools/verify-sweep.sh < batch-report/phase4.tsv     # translate + type-check a whole list
bash tools/verify-bytecode.sh translated/geckolib.jar    # one jar
```

**Run this before any launch.** Through Phase 4 most failures are `VerifyError`, which is the
most expensive kind this project produces: ten minutes of launch to find, one method reported,
and the JVM stops there — so a jar with forty bad methods took forty launches to survey. This
surveys all of them in seconds.

It also sees a failure class nothing else can. Six mods had a class hierarchy that is simply
illegal against 1.21 — extending something now final, implementing something that stopped being
an interface — and no call-site analysis reaches those.

Three things make the output worth reading rather than skimming:

- **Findings are grouped by shape**, not listed per method. One transformer bug produces the same
  error hundreds of times, and the useful figure is how many distinct shapes remain.
- **A missing class is sorted by package.** `net.minecraft` and `net.minecraftforge` are
  Easyport's responsibility, so a missing one is a real gap; anything else is another mod this
  run did not load. Suppressing the second kind took the first geckolib report from 12 shapes to
  1, and the one that remained was the real bug.
- **The mod author's own port is subtracted.** `SimpleVerifier` is not the JVM — it merges types
  by loading classes and reports mismatches the real verifier would not. Two such shapes survived
  a full round of investigation before turning out to be present in the reference ports too. Same
  differential move Phase 1 made for registry coverage.

A clean run means the classes will load. It does not mean the mod works — that is what
`batch-verify.sh` answers, and the two are not substitutes for each other.
