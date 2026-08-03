# STATE — read this first

Dense re-entry point. If you are picking this up cold (fresh context, new contributor),
read this file and nothing else until you need depth. [ROADMAP.md](ROADMAP.md) has the full
plan; this has where things actually stand.

**Last updated:** 2026-08-02, end of Phase 0.

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

**Phase 0: ~70% done.** Everything below is measured, not estimated.

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

### Phase 0 remaining — 3 actionable, 1 blocked

| Item | Blocked on |
|---|---|
| **Pick the remapping toolchain** — the critical path, see gotcha #1 | nothing |
| Hand-port one trivial mod both directions as ground truth | nothing |
| Confirm remaining **(verify)** rows in ROADMAP §4 | nothing |
| Prove `addPath` injection **and** shim runtime linkage in a live launch | **owner** — needs EULA acceptance or a display |

The live-launch check is not urgent: both questions it would settle are already answered
statically (SPI from FML source, shim linkage from compilation plus the empty-package
finding), and Phase 1 has to launch the game anyway. **What compilation cannot prove is
runtime linkage under the real module layers** — that stays open until then.

**Next: the remapper.** Everything it needs (Mojang official mappings, SRG, Parchment) is a
public download.

---

## Hard-won gotchas — each of these cost real time

1. **SRG contamination is the critical path.** 74.8% of lost symbols carry SRG names
   (`m_61124_`). Forge 1.20.1 runs SRG at runtime; NeoForge 1.21.1 runs Mojang official. Every
   vanilla member differs for mapping reasons alone. **Vanilla rule mining is meaningless until
   the source side is remapped.** Loader-API results are unaffected — Forge's own API is not
   obfuscated — which is why the 231 rules are loader-side and trustworthy.

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
