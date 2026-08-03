# Easyport

Translates Minecraft mods between **Forge 1.20.1** and **NeoForge 1.21.1**, in both
directions.

Two things get built:

1. **A translator tool** — point it at a mod, get back a version that works on the other
   loader.
2. **A mod that does it for you** — drop it in `/mods`, and anything you put in
   `/mods-from-other-version` gets translated into `/mods` automatically, renamed so you
   can tell what was translated.

**Status:** early. Nothing user-facing works yet. See [ROADMAP.md](ROADMAP.md) for the
plan and current progress.

---

## What works right now

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
