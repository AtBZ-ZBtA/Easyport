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
| `RENAME_METHOD` | owner, name, desc, newOwner, newName, newDesc | Same shape, different owner or name |
| `CTOR_TO_STATIC` | owner, ctorDesc, factoryName, factoryDesc | Constructor became a static factory |
| `CTOR_SWAP2` | owner, oldDesc, newDesc, [narrowTopTo] | Two constructor arguments reordered |
| `REMOVED` | symbol | No replacement — reported, never rewritten |

`CTOR_TO_STATIC` removes the `NEW`/`DUP` pair and switches `INVOKESPECIAL` to `INVOKESTATIC`.
`CTOR_SWAP2` inserts a `SWAP` (and a `CHECKCAST` first, if the new signature also narrows a
type — after the swap that value is buried). It refuses wide arguments, since `long` and
`double` occupy two stack slots and `SWAP` would corrupt them.

`REMOVED` exists so the transformer can be honest. Inventing a plausible target for an API
that no longer exists is the measured failure mode from `handport/` — 5 false positives out
of 26 symbols — and a jar that loads while quietly doing the wrong thing is worse than one
that refuses.

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

### Not yet covered

A world-gen smoke test would catch runtime crashes during actual play, which neither metric
sees. It needs a server launch (EULA) or a client (display), so it is deferred rather than
dropped.

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
