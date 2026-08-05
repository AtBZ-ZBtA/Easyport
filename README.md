# Easyport

Translates Minecraft mods between **Forge 1.20.1** and **NeoForge 1.21.1**, in both
directions.

Two things get built:

1. **A translator tool** — point it at a mod, get back a version that works on the other
   loader.
2. **A mod that does it for you** — drop it in `/mods`, and anything you put in
   `/mods-from-other-version` gets translated into `/mods` automatically, renamed so you
   can tell what was translated.

**Status:** both deliverables exist and work, in one direction. Forge 1.20.1 → NeoForge 1.21.1 is
built end to end — bytecode, mixins, resources — and the in-game mod translates and loads mods
during the same launch you drop them in. **The reverse direction is not started.** That is the
large remaining piece, along with a tail of individually small gaps. See [STATE.md](STATE.md) for
exactly where things stand and [ROADMAP.md](ROADMAP.md) for the plan.

---

## What works right now

### The mod

`easyport.jar` goes in your NeoForge 1.21.1 `mods` folder. Drop a Forge 1.20.1 mod into
`mods-from-other-version/` next to it, start the game, and the mod is translated into `mods/`
under a `-easyport.jar` name and loaded **in that same launch** — no restart, no second pass, no
launcher arguments.

```bash
bash tools/build-service.sh          # produces easyport.jar
```

It also keeps the folder honest. A translated mod is rebuilt when its source changes or when
Easyport itself is upgraded, and removing the source jar from `mods-from-other-version/` removes
the translated one — so the file you dropped in is the file you manage. Nothing without the
`-easyport.jar` suffix is ever touched.

The translation it performs is **byte-for-byte the same** as the command-line tool's: it runs the
same class, not a reimplementation, and that equality is checked rather than assumed. Everything
Easyport could not translate goes to the log and to a report in `.easyport/`.

What it needs to work is a Forge mod — a jar carrying `META-INF/mods.toml`. A NeoForge mod dropped
in by mistake is left alone rather than mangled.

### The translator

Rewrites a Forge 1.20.1 mod jar to run on NeoForge 1.21.1 — bytecode, resources, mixin
metadata and access transformers. Translated mods load into a real NeoForge instance and
register their content.

```bash
java -cp "<asm>;<asm-tree>;<asm-commons>" tools/Translate.java \
    <input.jar> <output.jar> mappings/srg2official.tsv rules/forward.rules.tsv <platform-jars...>
```

**How well it works, measured rather than claimed.** Every translated mod is launched next to
the author's own 1.21.1 port and compared on what each registers into the game:

| | |
|---|---|
| `additional_lights` | 100% of registry entries, 100% of resources |
| 7 of the 8 most-depended-on libraries | load and register content |
| All 433 mods in the test corpus | translate without error |
| 22 of 22 libraries and sampled mods | pass a full bytecode type-check |
| Vanilla API the corpus calls | 92% still resolves after translation |
| Mixin coordinates the corpus declares | 88% still point at what their author meant |
| Mixin problems that stop a mod loading | none left |
| Recipes, tags and advancements rewritten | 138,095 across 265 mods; 0 of 148,051 data files broken |

**The last row is the layer that fails without telling you.** A missing class throws and something
catches it; a recipe naming a tag that no longer exists just never matches, so the mod loads,
registers everything, and cannot craft any of it. Forge and NeoForge disagree about the name of
almost every shared tag — 30,000 references across 181 of the 433 test mods — and nothing anywhere
reports a tag that is not there. That is why those numbers are counted rather than assumed, and why
every file is re-read after rewriting.

**The mixin row is worth reading carefully too, because it is not the same as "mixins work."** A mixin
that patches a Minecraft method whose body has since changed cannot be repaired automatically, and
about 12% of them are in that position. What changed is that such a mixin now switches itself off
and is named in the report, instead of aborting the launch and taking every other mod down with it.
The mod loads and does slightly less; it used to be the whole game refusing to start.

Anything it cannot translate is **reported, never guessed**. Each run writes a report naming
exactly what was left unresolved. A jar that loads while quietly doing the wrong thing is worse
than one that refuses, and that is not a hypothetical — inventing plausible targets for removed
APIs was measured at 5 false positives out of 26 symbols during early testing.

The same principle governs the places where a faithful translation is impossible. 1.21 moved
several things onto serialization codecs that would have to be reconstructed from a mod's own
hand-written reader, and there is no honest way to do that automatically. Rather than refuse the
whole mod — which would lose every block and item it also registers — those get an inert
placeholder and a named line in the report. **A clean translation report is not the same as a
clean port, and the report is where the difference shows up.**

### Corpus analyzer

Compares two folders of mods and reports what we're up against. This is how the project
learns: mods that exist in *both* folders were ported by their own authors, and those
matched pairs are what the translation rules get derived from.

```bash
java tools/CorpusAnalyzer.java "<folder-A>" "<folder-B>" corpus-report
```

For example, All the Mods 9's mods folder as A and All the Mods 10's as B.

**What it tells you:** how many mods appear in both versions, how hard each one looks to
translate (trivial / moderate / hard / nightmare), and which mods exist in only one
version.

**What it needs:** Java 21. Nothing else — no Minecraft install, no internet. It only
reads the mod files; it never modifies them.

**What it writes**, into the output folder you name:

| File | Contents |
|---|---|
| `ground-truth-pairs.tsv` | Mods present in both versions — the learning set |
| `corpus-manifest.tsv` | Every mod scanned, with its difficulty rating |
| `unpaired.tsv` | Mods that exist in only one version |

Don't delete anything from either folder before running it. Mods that exist in only one
version are still needed — they're the cases with no reference answer available, which is
exactly what the finished tool has to handle on its own.

---

## Requirements

- **Java 21** (Temurin or equivalent)

That's it for now. Later phases will need a Minecraft install for testing.

---

## Getting the corpus

The corpus is not in this repository — it is ~2.8 GB of other people's mods under their own
licenses, and redistributing it would violate most of them. Set it up locally:

1. Install **All the Mods 9** (Forge 1.20.1) and **All the Mods 10** (NeoForge 1.21.1).
2. Point the tools at their `mods` folders.

Mods present in both packs were ported by their own authors, which is what makes them usable
as ground truth. Don't prune either folder — mods unique to one version are needed test
cases too.

**Any pack works, and you can use more than one.** The corpus is an input to the tools, not a
part of them: every analysis tool takes the mods folder as an argument, and the scripts read
`EASYPORT_SOURCE_MODS` / `EASYPORT_TARGET_MODS`. Adding a second pack re-ranks the work queue
automatically, so whatever the first one never happened to use shows up in priority order.

If a single mod won't translate, you don't need a pack at all — translate it and read the report
it writes, which names every symbol it could not resolve. See [tools/README.md](tools/README.md).

---

## License

**CC0 1.0 Universal** — see [LICENSE](LICENSE). This code is dedicated to the public domain.
Take it, fork it, ship it, sell it, no attribution required.

Two things that dedication does **not** cover, because they were never ours to give away:

- **Third-party dependencies** fetched at build time (NeoForge, Forge, ASM, and friends)
  carry their own licenses. NeoForge and Forge are LGPL-2.1.
- **Mods you translate.** Output is a derivative work of its input and stays under the
  original mod's license. Easyport translates locally, on your machine, for your own use —
  it does not redistribute anything, and neither should you without the author's terms
  allowing it.
