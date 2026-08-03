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

### Known limitation: vanilla results are mapping-contaminated

**74.8% of lost symbols carry SRG names** (`m_61124_`). Forge 1.20.1 runs SRG at runtime;
NeoForge 1.21.1 runs official Mojang names. Every vanilla member therefore differs for
reasons that have nothing to do with the API changing.

**Loader-API results are unaffected** — Forge's own API is not obfuscated — which is why the
tool reports the two separately and why the loader rules are usable today.

Vanilla mining stays blocked until the source side is remapped SRG → Mojang first. That
remapper is the project's critical path.
