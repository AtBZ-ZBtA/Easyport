# STATE — read this first

Dense re-entry point. If you are picking this up cold (fresh context, new contributor),
read this file and nothing else until you need depth. [ROADMAP.md](ROADMAP.md) has the full
plan; this has where things actually stand.

**Last updated:** 2026-08-05. See the phase sign-off blocks below for status.

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

# The same question for com.mojang, which is a SEPARATE run because MemberScan takes one prefix
# and everything downstream inherits it. blaze3d sat outside every report for months this way.
# See api-report/README.md for the full platform list this one needs.
java -cp "devenv/spi/asm.jar;devenv/spi/asm-tree.jar" tools/VanillaGaps.java \
    api-report/lib-api-usage.txt rules/forward.rules.tsv mappings/srg2official.tsv \
    devenv/neoforge-1.21.1/build/moddev/artifacts/neoforge-21.1.248.jar \
    forge-compat/forge-compat.jar devenv/spi/*-1211.jar devenv/spi/joml.jar \
    > api-report/lib-gaps.txt

# What do mixins still point at that is not there? Reads the jars, not a usage file.
# Pass a single jar instead of the folder to work one mod; it lists everything then.
java -cp "devenv/spi/asm.jar;devenv/spi/asm-tree.jar" tools/MixinGaps.java \
    "<corpus>/mods" rules/forward.rules.tsv mappings/srg2official.tsv \
    devenv/neoforge-1.21.1/build/moddev/artifacts/neoforge-21.1.248.jar \
    forge-compat/forge-compat.jar \
    > api-report/mixin-gaps.txt
```

`RenameGaps` lists every **Forge** type the corpus references that neither a shim nor a rule
resolves. `VanillaGaps` asks a deliberately different question of **vanilla**, because "does this
type resolve" is the wrong one there — 1.21 mostly kept type names and changed what is inside
them, so the body of that report is members that no longer exist on types that do. Read its
`BY OWNING TYPE` rollup first: it says which *subsystem* to fix.

`MixinGaps` exists because **neither of the other two can see a mixin at all.** They read a mined
usage file, which works because ordinary code addresses its targets through the constant pool;
mixins address theirs as *text*, so a mixin-heavy mod gets a clean bill of health from both right up
to the launch that aborts. Read its header rather than its sections: the number that matters is
`intact`, and everything listed below the header already loads — what it lists is behaviour a
translated mod no longer has. Pass `forge-compat.jar` alongside the platform jar, or mixins into
Forge's own classes go unjudged.

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
bash tools/build-neoforge-compat.sh       # the backward shim layer
bash tools/build-service.sh               # easyport.jar, the in-game half; needs forge-compat first

# Backward. Direction is detected from the jar; a jar already in the target shape is copied, not
# translated. Pass the shared libraries too -- without them an unindexed library reads as a type
# that does not exist, which is how DataFixerUpper became 275 phantom findings.
# EASYPORT_BACKWARD_NAMING=official for a dev-environment jar; omit it for one players will run.
# bash tools/build-neoforge-compat.sh, then drop the jar in devenv/forge-1.20.1/run-data/mods/
# and run: cd devenv/forge-1.20.1 && ./gradlew.bat runData --no-daemon --console=plain
java -cp "$CP" tools/Translate.java <neoforge-mod.jar> out.jar \
    mappings/srg2official.tsv rules/backward.rules.tsv \
    devenv/spi/forge-*.jar neoforge-compat/neoforge-compat.jar \
    devenv/spi/dfu.jar devenv/spi/brigadier.jar devenv/spi/guava.jar
EASYPORT_DIRECTION=backward bash tools/verify-bytecode.sh out.jar
bash tools/backward-verify.sh < batch-report/backward.tsv   # registry content vs the 1.20.1 build

bash tools/batch-verify.sh < batch-report/libs.tsv     # 8 highest-fan-in libraries
bash tools/batch-verify.sh < batch-report/sample.tsv   # 14 mixed mods

# what blocked each mod (these logs self-clear on success, so they are always live)
grep -oE "(ClassNotFoundException|NoSuchMethodError)[:.] ?'?[a-zA-Z0-9_./$]{0,50}" \
    devenv/neoforge-1.21.1/run/failed-<modid>.log | head -2
```

**Ten hard rules, each learned by breaking something:**

- **A documented limitation is not a handled limitation.** `tools/README.md` carried a section
  headed "Known limitation: no nested context" whose worked example was a recipe's `result.item`
  becoming `result.id`. That is what 1.20.5 did, to 34,375 corpus files, and the caveat sat
  directly above the report that was being read as a complete answer — by the same person who had
  written the caveat. If a blind spot is worth a paragraph, it is worth a measurement.
- **Translate the whole corpus before believing any number.** The 22-mod tested set had been the
  denominator for four phases, and the first sweep of all 433 jars found **21 that could not be
  translated at all** — three distinct crashes, two of them older than the phase that found them,
  and not one of them in the tested set. A mod that fails to translate never reaches the harness,
  so it is invisible to every measurement the harness produces. `translated=N failed=M` over the
  full corpus is cheap and is the only thing that sees this class of failure.

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

**Mixins, after Phase 5: repair, then defuse, then delete.** A broken mixin coordinate does not fail
like a broken call — it fails during mixin *apply* and takes the whole launch with it, every mod
included. It used to be handled by deleting the mixin class, which is not a contained loss: a dropped
mixin can be the only path into a static initialiser, and that is exactly how placebo came to load
and register nothing. **Failure granularity is now the individual injector**, via `require = 0`, so
the loss is the size of the actual breakage. Seven passes, none of which needs a rule; the table is
in [tools/README.md](tools/README.md). Three things to know before touching it:

- **Never remove an `@Accessor` from an interface mixin without marking the method
  `ACC_SYNTHETIC`.** Mixin decides the mixin's *variant* from the class — an interface is an
  ACCESSOR mixin only while every method is an accessor or synthetic, else an INTERFACE mixin, which
  may only target an interface. Stubbing one dead accessor regressed balm from 100% to not loading.
- **Strip `@Group` when defusing.** A named group's `min` check is separate from `require`, so the
  soft-fail otherwise looks applied and changes nothing.
- **Selectors have two spellings**, `Lnet/minecraft/Foo;bar()V` and `net/minecraft/Foo.bar()V`. The
  bare one is what `remap = false` anchors use, which is where Forge types need renaming.

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

#### The whole queue above was scoped to `net/minecraft/`, and one subsystem fell outside it

`api-report/vanilla-api-usage.txt` is produced by `MemberScan <corpus> "net/minecraft/"`. Every
report built on it — the ranking above, the gap counts, the decisions about what to build next —
inherited that prefix. **`com.mojang.blaze3d` is not under `net/minecraft`**, though it ships in
the same jar, is obfuscated by the same mappings, and was reworked in 1.21 harder than most of
vanilla. Nothing this project produced had ever looked at it.

What it was hiding, in jars of 433: `VertexConsumer.endVertex` 157, `uv` 127,
`vertex(Matrix4f,…)` 119, `color` 97 and 99, `uv2` 88. Each is a `NoSuchMethodError` the first
time the mod draws. `endVertex` is the highest-jar-count single member anywhere in the project's
measurements, and it sat outside every report because of one argument to one scan.

Now measured in `api-report/lib-gaps.txt` and mostly closed: the vertex protocol was renamed
wholesale to `addVertex`/`set*`, so most of it is rename rules, with `easyport/bridge/VertexBridge`
taking the members whose shape also changed. Corpus member references under `com/mojang/` went
from 89.5% resolving to 93.5%, and the 22-mod verify sweep stayed 22/22 clean.

Every rule in that block was checked against the disassembly of both versions rather than the
method names, and two would have been wrong from the names alone: `uv2(int)` maps to `setLight`
while `uv2(int,int)` maps to `setUv2`, and `overlayCoords(int,int)` maps to `setUv1`. The packed
and unpacked forms of the same idea were renamed onto different targets.

**What is left there is one wall, and it is the `Tesselator`/`BufferBuilder` lifecycle**
(`getBuilder` 104, `begin` 104, `Tesselator.end` 76, `BufferBuilder.end` 60). 1.20.1 hands you a
reusable builder and then tells it to begin; 1.21 constructs the builder *from* the mode and
format. Fusing those means the value in the mod's local has to come from a call that has not
happened yet, and a bridge cannot write into a caller's local. There is a design that works — have
`getBuilder` return null, have `begin` build the real one into a thread-local, and route every
subsequent vertex call through the bridge so a null receiver resolves to it — but it assumes one
builder in flight per thread, and no harness here can test rendering. It is written down rather
than built for that reason.

**The two other non-vanilla prefixes were checked and are clean:** `org/joml/` (434 references)
and `it/unimi/dsi/` (1,404) both resolve at 100%. So the scoping bug cost exactly one subsystem,
which is worth knowing precisely — the instinct after finding a hole like this is to assume more
of them.

#### The backward side of the same rework, designed and deliberately not half-built

Scanning ATM10 the same way says the mirror is just as big: `setUv` 136 jars,
`addVertex(Matrix4f,…)` 124, `Tesselator.begin` 110, `BufferUploader.drawWithShader(MeshData)`
100, `setColor` 99 and 98, `setLight` 92, `buildOrThrow` 86, 48 distinct members in all.

Two of the three sub-problems are *easier* backward, and the asymmetry is worth stating because it
is the opposite of the usual one. The lifecycle fusion that is a wall going forward is trivial
going back — one call becoming two is what a bridge does, so `Tesselator.begin(mode, format)`
becomes `getBuilder()` then `begin(mode, format)` and returns the builder. `MeshData` needs a shim
type wrapping 1.20.1's `RenderedBuffer`, which is ordinary work.

**The third sub-problem is the whole difficulty: `endVertex` has to be put back.** A 1.21.1 mod
never calls it — a vertex is committed implicitly — and 1.20.1's builder requires it. Nothing in
the mod's bytecode marks where a vertex ends.

There is a real answer, and it has now been measured rather than assumed. The 1.21 idiom is a
fluent chain whose value is discarded:
`consumer.addVertex(m,x,y,z).setColor(…).setUv(…).setLight(…)` compiles to a run of invocations
ending in a `POP`. **The `POP` is the end of the vertex**, exactly and syntactically, so the
transformer can replace it with a call to `endVertex()`.

`tools/VertexChains.java` exists to check that before anything is built on it, because "the idiom
is always X" is right often enough to feel safe and wrong often enough to corrupt geometry in the
one subsystem no harness here can test. Over all 479 ATM10 jars:

| Chain ends at | n | Jars |
|---|---|---|
| `POP` | **5,033** | 166 |
| `ASTORE` — consumer outlives the statement | 38 | 2 |
| `ARETURN` — a helper returns the part-built vertex | 36 | 22 |
| passed to a call | 27 | 1 |
| `VOID_FORM` — the 11-argument `addVertex`, which needs no insertion | 24 | 16 |

**97.6%, and the remainder is concentrated rather than smeared** — 172 jars contain a chain at
all, and three of the four non-`POP` shapes live in 2, 1 and 16 jars. `ARETURN` is the one real
hole: a helper that returns a part-built vertex ends it at *its caller's* `POP`, which is not
reachable from inside the helper without an interprocedural pass. Those 22 jars get named in the
report rather than quietly rewritten wrong.

Not built yet on purpose: **this family is all-or-nothing.** Without the renames a mod dies with a
loud `NoSuchMethodError`; with the renames but without `endVertex` it feeds a malformed vertex
stream into vanilla's builder and fails somewhere far away, or draws corrupt geometry. Shipping
the easy half would trade a clear failure for a confusing one, which is the wrong direction and
the opposite of every other trade this project makes.

The mixin layer is done (Phase 5, signed off below). What is left there is not load failures but
595 injectors and accessors that load and no longer do anything, ranked in
`api-report/mixin-gaps.txt`. That queue is dominated by **client rendering**, which `runData` never
exercises — so it is the one area where no harness this project has will catch a regression.

**Resources fail without saying anything, and that is the whole of Phase 6.** Bytecode failures
announce themselves — a missing class throws and the harness catches it. Nothing in the resource
layer throws. A recipe naming a tag that no longer exists never matches; a datapack condition under
a key the loader stopped reading is never evaluated. The mod loads, registers every block and item
it has, and cannot craft any of them, with nothing in any log to say why. Four of the nine
migrations in the phase fail exactly that way, the largest being the `forge:` → `c:` tag namespace
at 33,674 references across 206 of the 433 corpus jars. Table in
[tools/README.md](tools/README.md).

**One rule that is specific to this layer: for tags, the corpus is the wrong source.** Everything
else here is mined from ATM9 against ATM10. A `data/c/tags/item/tools/axes.json` sitting in an
ATM10 mod proves only that a mod author invented that name; Forge's and NeoForge's own jars are
what the game defines. That mapping is mined from the two platform jars — 321 tags against 463,
187 identical, 59 renamed, 75 with no counterpart. The 75 with none are reported rather than
approximated.

**Never use resource-coverage percentages as a measure of resource migration.** They carry the same
feature drift that poisons rule mining: `allthecompressed` reports 6,612 missing resources because
its reference is version 4.4.0 against a 3.0.2 source, and nearly all of that is content the author
added. Treat a low percentage as a prompt to look at the diff, never as a defect count. The inverse
error is just as easy — `blockui`'s GUI textures moving into a `<modid>_sprites/` directory looked
like a systematic 1.21 migration until it was counted: `assets/<ns>/atlases/` appears in 77 of 433
ATM9 jars and 78 of 479 ATM10 jars, so it is not a version change at all.

**How much is left, stated plainly, because the shape of recent progress invites the wrong
answer.** Both directions translate, load and register end to end, and both have a measurement
loop. That is architecture, not coverage. **Registry content has been measured for 22 mods of the
288 ground-truth pairs — 7.6% — and two of them pass.** The corpus analysis has said from the
start that 48.6% of pairs are Hard or Nightmare and nothing has changed that. A run of green
results on a 14-mod sample is not the project nearly finished; it is the sample being small.

The backward sweep puts a number on the rest: **3,425 distinct vanilla members and 339 types with
no 1.20.1 counterpart**, close to twice the forward gap, and weighted toward things 1.20.1 cannot
represent at all rather than things that merely moved.

**The backward direction is started, not finished**, and it is the whole of what remains. See its
own block below for what is built and what is not. Two things to carry into any work on it:
`Translate` is now direction-aware and refuses a rules file whose `#direction:` header disagrees
with the input jar, and **the backward direction has no launch harness at all**, so nothing in it
has been run — only compiled, type-checked and measured.

The forward side also still has a tail: 595 mixin coordinates that load and do nothing, the
`BlockEntity` override shape no pass reaches, and the `ForgeHooks`/`ForgeEventFactory` split.

---

## What this is

**Easyport** translates Minecraft mods between **Forge 1.20.1** and **NeoForge 1.21.1**,
both directions, aiming at complete coverage. Two deliverables: a CLI tool, and a jar that
sits in `/mods` and auto-translates anything dropped in `/mods-from-other-version`. **Both exist
and work, forward only** — `tools/Translate.java` and `easyport.jar` (`tools/build-service.sh`).
The in-game one runs the CLI's own class and produces byte-identical output.

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

### Backward direction — IN PROGRESS

**NeoForge 1.21.1 → Forge 1.20.1.** Started; nothing launch-tested yet, because the only harness
this project has is a NeoForge 1.21.1 `runData` and the backward equivalent has not been stood up.
Everything below is verified offline — compiled, type-checked, or measured against the corpus.

#### One transformer, not two

`Translate` is now direction-aware rather than forked. That is the right shape for a reason worth
keeping: almost nothing in it is directional. The seven Phase 4 mechanisms and all seven mixin
passes ask the *platform jar* what the answer is instead of consulting a table, so handing them
1.20.1 rather than 1.21.1 is the entire change — a `Holder` unwrapped one way is wrapped the
other, by the same code reading a different descriptor.

**Direction is detected from the input jar and cross-checked against the rules file.** A Forge mod
declares `META-INF/mods.toml`, a NeoForge one declares `META-INF/neoforge.mods.toml` — the same
test the loader applies, so it cannot drift. Each rules file carries a `#direction:` header and a
mismatch is refused: running a NeoForge mod through the forward rules does not fail, it produces a
jar of confidently wrong renames.

Forward output is byte-identical after the change, checked by diffing an unpacked jar against the
Phase 6 sweep.

#### Four asymmetries, each of which broke the obvious approach

**The mapping table does not invert.** Forge 1.20.1 bytecode carries SRG member names and
NeoForge 1.21.1 carries official ones, so the naive backward move is to read
`mappings/srg2official.tsv` the other way round. It cannot be: SRG ids are globally unique, so
srg→official is a function, but official names are not — `getTag`, `tick` and `getName` occur on
dozens of classes. Inverting collapsed 64,225 rows onto 37,970 names and picked an arbitrary SRG
id for **26,255** of them, which is a remapper that silently rewrites calls to whichever class was
last. `SrgToOfficial` now also emits **`mappings/official2srg.tsv`**, keyed on owner + name +
descriptor, 80,654 entries, unique by construction. The forward table is untouched.

Lookups walk the supertype chain, because a call on a subclass names the subclass while the member
is declared above it. A miss is reported as `NO_SRG_NAME` — **unless the member is present in the
1.20.1 jar under its official name**, which means it was never obfuscated and there is nothing to
do. Enum constants are the whole of that case; without the check every `RenderShape.MODEL` read as
a missing API.

**The common-tag default is wrong half the time.** Forward, a `forge:` tag with no rule is swapped
to `c:` and is almost always right. Backward, of NeoForge's **348 common tag paths only 139 have a
1.20.1 counterpart**; 36 more are renames and **173 do not exist in Forge 1.20.1 at all**. So
`TAG_GONE` carries most of the weight in `backward.rules.tsv` rather than being a footnote. Tag
rules are stated per direction rather than inverted in code, precisely because this default is not
symmetric.

**Most type renames must not be renames.** All 166 forward `net/minecraftforge/X` →
`net/neoforged/Y` rules invert 1:1, and every inverted target exists in the Forge 1.20.1 platform.
Adding all of them produced **118 rules that resolve to a type without the member the corpus calls
on it** — the "rename resolves, mod dies on the first call" failure `RenameGaps` warns about.
Classifying each candidate out of the NeoForge jar kept only what the loader actually scans:
annotations, the enums annotations carry, and subclasses of `Event`. **38 renames; the other 128
are shim work.** The 41 warnings that remain are the event families whose accessors NeoForge
renamed along with the event — the inherited stopgap debt, stated in the rules file rather than
discovered later.

**The mod constructor is the headline blocker, and it is bigger than its forward counterpart.**
NeoForge injects into the mod constructor; Forge 1.20.1 calls a no-argument one and expects the
mod to fetch what it needs from static context. **408 of the 479 corpus mods declare the injected
form** — they are found by the loader, fail to construct, and contribute nothing.

This is the mirror of the `FMLJavaModLoadingContext` finding that topped the Phase 0 work list at
241 mods, and it is the one place where that finding's shape does *not* invert into something
cheap. Forward it dissolved, because NeoForge kept `ModLoadingContext.get()` and a delegating shim
reached the bus. Backward there is nothing to delegate to: the signature the loader looks for is
the thing that is wrong, and no shim can change a constructor's descriptor.

`Translate` now synthesises the missing `()V`, filling each parameter from the same `ARG_FILL`
table the vanilla passes use and chaining to the original with `this(...)`. All three injected
types — the mod event bus, the mod container, the physical side — are reachable statically in
1.20.1, which is the only reason the pass is possible at all. A parameter with no filler is
reported, not guessed.

#### The backward harness measures content, not just loading

`bash tools/backward-verify.sh < batch-report/backward.tsv`. Three launches per mod — baseline,
reference, candidate — with the baseline's ids subtracted from both sides, and the reference and
candidate registry sets diffed id by id. Same input format as the forward harness with the roles
swapped: the NeoForge jar is translated, the Forge jar is the answer.

Two pieces had to exist first. **`testkit/inspector-forge`** is the probe, deliberately writing
the identical JSON to the NeoForge one so the two directions stay comparable. **`tools/DevifyJar`**
renames SRG members to official ones and does nothing else, because the reference — the author's
own 1.20.1 build — cannot otherwise load in a dev environment at all. It must never grow into a
second transformer; the only reason it is separate from `Translate` is that de-obfuscation and
translation are different jobs sharing one table.

**Classify a failed reference as a harness limit, not a translation failure.** `botanypots`' own
1.20.1 build refuses to load without `bookshelf`, and calling that "registers nothing" blames
entirely the wrong thing. 5 of the 14 sampled mods are in that position. This is the same mistake
the forward harness records from Phase 3, arriving from a new direction.

**The reference side was silently broken for every mixin-carrying mod, and the symptom looked
like a property of the mods.** `DevifyJar` renamed bytecode only; mixins address their targets as
*text* — refmap JSON, `@At(target = ...)`, `@Accessor("f_12345_")`, and an access transformer is a
file of them. Left in SRG, Mixin throws `Critical injection failure` during apply and takes the
whole launch down, so the reference registered nothing and the harness dutifully reported
`NO_CONTENT`. That is a plausible-looking answer — plenty of mods really do register nothing —
which is exactly what made it dangerous. lootr, bookshelf and almostunified went from 0 reference
ids to 12, 4 and 2 once the text was rewritten too, and **136 corpus mods carry mixins**.

The lesson is the one Phase 3 already recorded from the other side: the bytecode remapper never
sees a mixin. It was written down, in this tool's own javadoc, as something that would merely
degrade behaviour. It aborts the launch.

#### A backward mod now loads on Forge 1.20.1

`devenv/forge-1.20.1` runs `runData` headless in about 13 seconds, the same shape as the forward
harness, and it reads mods from `run-data/mods/`. **`additional_lights`, translated from its
NeoForge 1.21.1 build, loads and constructs on Forge 1.20.1 and completes mod loading with no
errors.** That exercises the whole chain: descriptor, class-file downgrade, naming, the
synthesised constructor, the shim layer, the type renames and the resource migrations.

**What is not yet verified is registry *content*.** `runData` never reaches a registry dump, so
"loaded without error" is as far as this harness goes. The forward direction measures content with
`testkit/inspector` plus `VerifyHarness`; the backward equivalent needs a 1.20.1 build of that and
does not have one. Do not read the paragraph above as a coverage number.

Getting there took five rounds, and every one of them was a real defect rather than a
misconfiguration:

| Round | Failure | Cause |
|---|---|---|
| 1 | `Missing required field mandatory` | the descriptor migration never ran — it triggered on the *forward* filename, and renamePath moves the file first |
| 2 | `UnsupportedClassVersionError` | see below |
| 3 | `constructed 0 mods` | Forge refuses a jar declaring a mod with no `@Mod` class; NeoForge accepts one, so forge-compat had got away with it |
| 4 | `NoSuchFieldError: f_256840_` | see below |
| 5 | `NoSuchMethodError`, three times | ordinary shim and vanilla-rule gaps: `Blocks.register`'s covariant return, `TorchBlock`'s swapped constructor, `Block.properties()` |

**Minecraft 1.20.1 runs on Java 17 and 1.21.1 on Java 21**, and a JVM refuses a class file newer
than itself before any code runs. **111,713 classes across 466 of the 479 corpus jars are Java 21
bytecode.** Every class is renumbered to 61 now; constructs whose bootstrap only exists on a newer
JDK — a pattern-matching switch against `SwitchBootstraps` — are reported rather than rewritten,
because desugaring one is a real transform and not to be attempted speculatively. The forward
direction has no equivalent problem: a newer JVM accepts older class files, so this asymmetry is
one-way.

**The dev harness cannot load production jars at all**, and that is not a statement about our
output. Forge 1.20.1 has two naming worlds: a mod shipped to players carries SRG member names,
which is what this transformer emits, while a ForgeGradle dev environment runs vanilla under
official names. An unmodified ATM9 jar, straight off CurseForge, fails in `runData` with exactly
the same `NoSuchFieldError`. So `EASYPORT_BACKWARD_NAMING=official` leaves names alone for harness
runs, and **the naming step is the one thing the harness therefore cannot test.** Nothing available
here can; it would take a production launch.

| | |
|---|---|
| Backward vanilla references that resolve | **88.2%** of 27,060 (forward is 92.2%) |
| NeoForge types the corpus references | 861 |
| Resolved by a rename | **440** |
| Needing `neoforge-compat` | 147 with a differing surface, plus what has no counterpart at all |
| `neoforge-compat` classes | 24 |
| **Sampled mods measured at 100% / 99%** | **2 of 4 measurable** |

#### Two ways to earn a rename, and both are mechanical

The rename set was built twice, and the first attempt was wrong in a way worth keeping written
down. **Inverting `forward.rules.tsv` is the wrong source**: it only covers types the *forward*
direction needed a rule for, which is not the set this direction references. `GatherDataEvent` is
a mod-bus event named by 163 ATM10 jars, had no forward rule, and was therefore missing — so every
mod using it failed on a class that should never have been shimmed.

Re-derived from all 861 referenced types, using the two justifications the rules files allow:

- **The loader dispatches by it** — annotation, enum, or subclass of `Event`, read out of the
  NeoForge jar. 263 qualify, all with targets that exist in Forge 1.20.1.
- **The surface is verified identical** — `tools/SurfaceMatch.java` compares both types' public
  and protected members, descriptors included, with the two loaders' package prefixes collapsed.
  138 of the remaining 285 match exactly.

`IItemHandler` is the case that prompted the second: six methods, byte-identical on both sides,
and it was about to be hand-written as a shim because a launch happened to name it. One type at a
time, that is 138 launches. The rejections carry as much information as the promotions —
`IEventBus` differs 13 members to 14, which is exactly why it has a hand-written shim.

The shim layer therefore covers only what genuinely differs: `DeferredRegister` (250 jars),
`DeferredHolder` (220), `ModConfigSpec` and its builder (212), `ModList` (256), the event bus, the
mod container.

#### Three things left out on purpose, each for the same reason

`DeferredRegister.createDataComponents` (36 jars), `NeoForgeRegistries.ATTACHMENT_TYPES` (38), and
`NeoForgeRegistries`' live `Registry` fields (24). Data components and data attachments do not
exist in 1.20.1 in any form — a shim would accept registrations and drop them. The `Registry`
fields fail differently and are worth the note: Forge only publishes its registries' vanilla view
during `NewRegistryEvent`, *after* mod construction, so a field initialised eagerly is null exactly
when a mod's static initialiser reads it. The first version shipped that, and the NPE surfaced
inside the shim pointing at the wrong file. Absent, the mod gets a `NoSuchFieldError` naming the
field.

#### Where the sample stands, and the one wall in it

| Status | n | Meaning |
|---|---|---|
| `OK` | 2 | `additional_lights` 100.0%, `allthecompressed` 99.1% |
| `LOADED_NOTHING` | 2 | `alltheores`, `aquaculture` |
| `DEPS_MISSING` | 5 | the **reference** will not load either — harness limit, not translation |
| `NO_CONTENT` | 5 | neither side registers anything |

A second set of eight mixin-carrying mods is in `batch-report/backward-mixin-results.tsv`, kept
separate because the harness appends and skips already-recorded ids — two lists sharing one file
merge silently. **The reference side of that set did not work at all until `DevifyJar` learned to
rewrite mixin coordinates**, which is the finding below.

**2 of 4 measurable**, which is the honest denominator. `allthecompressed` reports 822 *extra*
entries alongside its 9 missing, and that is feature drift rather than translation: its 4.4.0
NeoForge build registers far more than the 3.0.2 Forge reference. Missing and extra are separate
columns precisely so a percentage cannot be inflated or deflated by it.

**`aquaculture` is a wall, not a queue item.** It registers a custom armor material, and 1.21 made
armor materials a *registry* — `Registries.ARMOR_MATERIAL` exists in 1.21.1 and does not exist in
1.20.1 at all. There is no registry to register into and no shim that can invent one; 1.20.1's
armor system reads an enum. This is the backward mirror of `OutOfJarResourceLocation extends
ResourceLocation`: forward, the hard cases are things 1.21 added that a 1.20.1 mod cannot know
about; backward, they are things 1.21 added that a 1.20.1 *game* cannot represent.

#### The whole 1.21.1 corpus, translated backward

**479 / 479 jars translate**, and that is the same narrow claim the forward direction makes: the
transformer runs to completion and produces a jar. What it does not say is the point.

| Measure | Backward | Forward, for scale |
|---|---|---|
| Distinct vanilla members with no counterpart | **3,425** | 1,968 of 25,288 references |
| Distinct types absent from the target | **339** | 114 |
| Jars needing an abstract stub | 234 | — |
| Jars with a mixin injector defused | 110 | 96 |
| Jars using a Java 21 construct with no 17 form | 76 | n/a |

**The member figure was 4,142 and the correction is worth more than the number.** Roughly 700 of
those were the platform index reporting on itself: authlib and `com.mojang.logging` were on the
classpath but not member-indexed, so every member of an indexed-but-unread owner came back absent,
and `GameProfile.getId` was on the missing-API list. The three counts that went *up* moved for the
same reason in reverse — indexing everything makes more of the corpus judgeable, so more abstract
stubs and defused injectors are found rather than skipped. See gotcha 0.

**Roughly twice the vanilla gap of the forward direction, and it is not symmetric drift.** 1.21
added the data-component system, `StreamCodec`, `RegistryFriendlyByteBuf`, data-driven
enchantments and armour-material registries; every one of those is a thing a 1.20.1 game has no
representation for, so the backward tail contains walls where the forward tail contains queues.

Three defects surfaced only because the sweep ran over everything rather than a sample, and two
of them were in the *reporting* rather than the transformer — which is worse, because a wrong
number is acted on:

- **19 jars read as failures that were not.** A modpack folder carries jars already in the target
  loader's shape — datapack-only jars, Fabric jars, mods shipping both descriptors. Refusing them
  is right; calling them failures made a sweep read 19 worse than it was.
- **`OWNER_NOT_IN_1201` conflated two things.** A member whose owner the transformer cannot find
  was reported as "this type does not exist in 1.20.1" — but an unindexed *library* looks
  identical. DataFixerUpper accounted for 275 of 445 supposedly-missing types. The platform set
  now carries the shared libraries, and the lesson generalises: an index that is missing something
  does not report a gap in itself, it reports a gap in the thing it is measuring.
- **A source jar can hold two entries under one name.** ars_nouveau ships `META-INF/LICENSE.txt`
  twice; the second `putNextEntry` throws and fails the jar. Nothing in translation causes it and
  nothing downstream survives it, so the first wins and the duplicate is named.

#### Known-incomplete, deliberately

**Mixin coordinates are not translated backward at all**, and the transformer says so per jar
rather than staying quiet. A coordinate names an official 1.21.1 member and needs the
owner-qualified table plus the selector's own owner to become an SRG name. Until that exists,
every mixin in a backward-translated mod points at a member Forge 1.20.1 resolves by a different
name — which fails at apply time and takes the launch with it, exactly as Phase 5 documented in
the other direction.

**Mixin *naming* is still not translated backward**, and that is narrower than it first looked.
Phase 5's machinery — repair, defuse per injector, stub, drop — turns out to work backward
unchanged, because it asks the platform rather than a table: pointed at the 1.20.1 jar it drops a
mixin whose target class 1.21 introduced, defuses injectors that no longer resolve, and stubs
accessors onto members that are gone. So mixins **apply safely** in both directions today.

What is missing is the official → SRG conversion of the coordinates themselves, for a jar players
would run. It needs the owner-qualified table plus each selector's own owner, which all exists;
it has simply not been written. The dev harness runs under official names and so cannot show the
gap either way.

**1.21.1-only datapack trees are not handled.** `data/<ns>/enchantment/`, `data/<ns>/data_maps/`,
`data/<ns>/jukebox_song/` and `data/<ns>/tags/data_component_type/` have no 1.20.1 meaning and are
currently carried across untouched rather than dropped or synthesised.

### Phase 7 — COMPLETE

**Exit criterion:** a mod dropped into `mods-from-other-version/` is translated and loaded by the
same launch. Met.

`easyport.jar` goes in `mods/`. It declares `IModFileCandidateLocator` in `META-INF/services`,
which FML's `ModDirTransformerDiscoverer` finds while walking the mods folder *before* mod
discovery — the only window in which a jar can be translated and still be loaded by the launch
that found it. It carries `forward.rules.tsv`, `srg2official.tsv` and `forge-compat.jar` inside
itself. Build with `bash tools/build-service.sh`.

| Check | Result |
|---|---|
| Translated mod loads in the same launch | `Creating FMLModContainer instance for [com.mgen256.al.AdditionalLights]` |
| Four mixin-heavy libraries at once | balm, curios, supermartijn642corelib, yungsapi all construct |
| In-game output vs CLI output | **byte-identical**, unpacked and diffed |
| Unchanged source relaunched | cache hit, no retranslation |
| Easyport rebuilt | cache invalidated, mod retranslated |
| Source removed from the inbox | translated jar removed from `mods/` |

**The byte-identical row is the one that matters**, and it is why this phase cost days rather than
weeks. It runs `easyport.tools.Translate` — the same class the command line runs, not a port of it.
A second implementation would drift from the one every measurement in this project was taken
against, and the drift would surface as mods behaving differently depending on how they were
translated.

#### Three things that were wrong first, all found by running it

**The jar cannot find itself.** FML loads it through the secure jar handler, so its code source is
a `union:` URI that `Paths.get` refuses. Using its own modification time as a cache key silently
produced a stamp of zero, so a rebuilt Easyport reported every mod up to date and retranslated
nothing — a fix that ships and reaches nothing the user has already ported looks exactly like a fix
that does not work. The build id is now a content hash packaged *into* the jar.

**The module path arrives with its separators doubled.** Windows accepts `C:\\Users\\...`, so the
same platform jar appeared twice under two spellings and was indexed twice. Paths go through
`toRealPath` before deduplication.

**forge-compat has to be in the target index, not just the mods folder.** It was injected as a mod
from the first run and looked complete, because the mod under test had no mixins into Forge's own
classes. Phase 5 already recorded that supermartijn642corelib patches `net.minecraftforge.registries.GameData`
and needs forge-compat indexed to judge it; the in-game path was missing that and would have
silently judged those mixins against nothing.

#### What it deliberately does not do

**It does not detect direction.** It translates Forge 1.20.1 → NeoForge 1.21.1 and recognises a
NeoForge jar it should leave alone rather than mangle. Running on a Forge 1.20.1 instance and
translating the other way needs the backward rule set, which does not exist.

**It claims exactly one thing in the mods folder: the `-easyport.jar` suffix.** Files carrying it
are rebuilt when their source or Easyport changes, and deleted when their source leaves the inbox —
otherwise the user's way of uninstalling a translated mod, deleting the file they dropped in, does
nothing at all. Nothing without that suffix is ever touched.

**Without a target index it still translates, loudly.** If no platform jar is found on the module
or class path it warns and carries on with the Holder, argument-arity, abstract-stub and mixin
passes disabled. That is a large loss, and the warning is the difference between a known limitation
and a mystery.

### Phase 6 — COMPLETE

**Exit criterion:** a translated mod's datapack means the same thing to 1.21.1 that it meant to
1.20.1. Met for everything the two platform jars and the corpus can be asked about.

| Measure | Start of phase | Now |
|---|---|---|
| Corpus jars that translate at all | 412 / 433 | **433 / 433** |
| Datapack rewrites across the corpus | — | **137,968** in 265 of 433 jars |
| Datapack files that stop parsing | — | **0** of 148,051 |
| `architectury` resource coverage | 0.0% | **100.0%** |
| Libraries loading | 7 / 8 | 7 / 8 |
| Type-check clean | 22 / 22 | 22 / 22 |

Corpus-wide actions, largest first: `PLATFORM_TYPE_NAMESPACE` 32,937 · `TAG_NAMESPACE` 31,771 ·
`RECIPE_RESULT_ID` 29,471 · `RECIPE_CONDITIONS_NAMESPACED` 20,075 ·
`RECIPE_SHOW_NOTIFICATION_DROP` 11,240 · `ADVANCEMENT_ICON_ID` 2,817 ·
`RECIPE_CONDITIONAL_UNWRAP` 2,457 · `TAG_RENAME` 1,903 · `TAG_FILE_RENAME` 212 ·
`DIMENSION_INT_PROVIDER_FLATTEN` 30 · `TAG_FILE_MERGE` 11 · `RESOURCE_SUPERSEDED` 5.

Losses, named rather than counted as successes: `RECIPE_RESULT_ID on a mod recipe type` 4,904 in
94 jars, `TAG_NO_COUNTERPART` 135 in 32 jars, `RECIPE_CONDITIONAL_ALTERNATIVES_DROPPED` 118 in 3
jars, `RECIPE_CONDITIONAL_UNREADABLE` 7, `RESOURCE_JSON_UNPARSED` 2.

The rewrite total reconciles as 132,929 clean plus the 5,039 that were applied *and* flagged — the
mod-recipe-type results and the no-counterpart tags, both of which are real edits carrying a
caveat. The remaining 127 findings changed nothing and are not counted as rewrites.

**The 4,904 is the one to understand.** A mod's own recipe type is decoded by the mod's own code,
which this pass did not rewrite. Most such codecs delegate to `ItemStack`'s and therefore want
`id` — in the reference ports, mod recipe types moved with vanilla's almost everywhere — but a
codec that reads the key by hand still wants `item`, and nothing in the JSON says which.
`farmingforblockheads` kept `item` across 810 files while `mysticalagriculture` moved all 501 of
its own. It is applied, because a wrong guess costs the same recipe either way, and it is reported
separately so the guess is visible.

**Neither the libraries row nor the type-check row moved, and both were meant to be read that
way.** This phase changes what a mod *does*, not whether it loads; a resource migration that
altered either number would be a bug. What moved is 21 jars that could not be translated at all,
and a mod whose only datapack file was in a namespace the loader does not read.

#### The phase existed because the plan said it did not

ROADMAP had this as "mostly already built" with three small items left. That assessment came from
`resource-report/json-key-deltas.tsv`, and **the tool that produced it documented the exact blind
spot that was hiding the real work.** `tools/README.md` carried a section headed "Known
limitation: no nested context" whose hypothetical example was a recipe's `result.item` becoming
`result.id`. That is what 1.20.5 did, to 34,375 files in the corpus.

Teaching `ResourceMiner` to record key *paths* rather than bare key names took about thirty lines
and turned three small items into nine:

| Change | Scale | Failure if skipped |
|---|---|---|
| Tag namespace `forge:` → `c:` | 33,674 references, **206 of 433 jars** | **silent** — recipes never match |
| Common-tag renames past the swap | 39 tags | **silent** |
| Recipe `result.item` → `result.id` | 34,375 files | recipe dropped |
| Advancement `display.icon.item` → `id` | 55 mods | advancement dropped |
| `conditions` → `neoforge:conditions` | 47 mods | **silent** — conditional recipes all fire |
| Condition/modifier `type` `forge:` → `neoforge:` | 32,937 type values | file dropped |
| `forge:conditional` unwrapped | 2,457 files | file dropped |
| `data/<ns>/forge/` → `neoforge/` | 59 jars | biome modifiers never load |
| `dimension_type` int provider flattened | 5 mods | the dimension does not exist |

**A limitation that is written down is not a limitation that is handled.** A report whose blind
spot is documented still reads as a complete answer to anyone who trusts its output — and the
person trusting it was the same person who wrote the caveat. If a blind spot is worth a paragraph,
it is worth a measurement.

#### `pack_format` was the one item with evidence, and measuring it retired it

It was the only one of the three old items backed by an observation rather than a changelog:
translated jars do ship 15 where 1.21.1 wants 34. ATM10 is a shipping modpack, and **19 of its
jars declare `pack_format: 6`** — Minecraft 1.16.2 — 30 declare 15, and 179 of 479 ship no
`pack.mcmeta` at all. Mod resource packs are not validated against it. Bumping it would have been
a change with a plausible rationale, no effect, and a fresh chance to be wrong, since data packs
want 48 rather than 34.

#### The one place the corpus is the wrong source

The `forge:` → `c:` tag mapping is mined from `forge-1.20.1-47.4.22-universal.jar` against
`neoforge-21.1.248.jar`, not from ATM9 against ATM10. A `data/c/tags/item/tools/axes.json` in some
ATM10 mod proves only that a mod author invented that name; the platform jars are what the game
defines. 321 Forge tags against 463 NeoForge ones: **187 identical** under the directory
singularisation, **59 renamed** by 42 `COMMON_TAG` rules, **75 with no counterpart** flagged by 39
`TAG_GONE` rules. Nothing is unaccounted for — rules are keyed on the tag path alone, so one rule
covers both the block and the item form, which always move together.

The 75 with no counterpart are reported, not approximated. Most are the colour tags — 1.21 replaced
`forge:glass/black` with the intersection of `c:glass_blocks` and `c:dyed/black`, a set operation
no rename can express. Mapping it to `c:dyed/black` alone would silently widen a stained-glass
recipe to accept black wool, which is the trade this project always refuses. The namespace is
swapped anyway, so a mod that both defines and uses the tag keeps agreeing with itself; what is
lost either way is agreement with every other mod.

#### Two collision shapes, both from mods built for two loaders at once

A mod that supports Forge and Fabric ships its common tag twice, under `forge:` and under `c:`, and
the namespace swap lands them on one path. A mod that supports Forge and NeoForge ships both
`mods.toml` and `neoforge.mods.toml`. Either one was a duplicate-entry `ZipException` that failed
the entire jar — 9 of 433 on the first sweep, which is how they were found.

They resolve differently on purpose. **Tags are merged**, because a tag is a set and the union is
what the game would have computed from the two files anyway. **Everything else defers to the file
the author already put at the destination**, because it was written for the target loader and the
one being renamed was not.

#### It also closed a gap Phase 3 recorded as unexplained

Phase 3 signed off architectury as "OK, 100%" with a note that its resource coverage was 0/1 and
was "a separate unexplained gap worth a look". It is the `data/<ns>/forge/` → `neoforge/` rename.
Architectury ships exactly one datapack file, `data/architectury/forge/biome_modifier/impl.json`,
and the author's own port has it at `data/architectury/neoforge/biome_modifier/impl.json`. Ours now
matches, and architectury reads **100% registry, 100% resource**.

Worth reading as more than one file moving. Architectury's entire datapack contribution is that
one biome modifier, it was in a namespace the loader does not read, and every measurement said the
mod was fine — because the thing that was broken was not the kind of thing anything was measuring.

#### The pass checks its own output, and had to be as lenient as the game

Every rewritten file is re-parsed before it is written, and kept as-is if it does not survive the
round trip. This whole layer is invisible until the game reads the file, and a file the game cannot
read is worse than one that was never migrated — the recipe disappears instead of merely not
matching. It costs one parse of a document already in memory and makes "produced malformed JSON" a
bug that cannot reach a jar. Across the corpus it has never fired.

**Being stricter than the consumer was the expensive kind of correct.** Minecraft reads datapack
JSON through `GsonHelper`, whose reader is lenient, so the corpus is full of files that are not
strict JSON and work perfectly: `// Mod integrations` comments, unquoted keys like
`{id: "#c:x", required: false}`, trailing commas. A strict parser rejected 55 of them, and a
rejected file silently skips **every** migration above — its tags stay in the `forge:` namespace and
nothing says so. The parser matches the game now, and the one file still rejected is genuinely
truncated, is named in the report, and is left alone.

That rejection is per file and named per file for the same reason. "Some file somewhere in this jar
did not parse" is not something anyone can act on.

#### Three crashes found by sweeping the corpus, two of them older than this phase

The Phase 6 sweep was the first time all 433 jars had been translated in one pass, and it found
three ways `Translate` could abort. Only the first is Phase 6's own.

**Renaming onto an occupied path**, above. Phase 6's.

**Argument stack depth counted in slots rather than values.** The coercion pass located a call's
arguments with `Type.getSize()`, but an ASM analysis frame holds a long or a double as *one* entry
whose size is 2. With one wide argument it silently read the wrong one; with two it indexed past
the bottom of the stack and threw. Present since Phase 4 and invisible because every method with
only single-width parameters computes the same answer.

**`@At("NEW")` naming a bare type.** `Type.getReturnType` was handed a target that is not a
descriptor — `target = "net/minecraft/world/Container"` is perfectly ordinary — and threw out of
ASM. Present since Phase 5.

### Phase 5 — COMPLETE

**Exit criterion:** no library or sampled mod is blocked by mixin application. Met. Both mods that
were stuck on it moved: yungsapi loads, and supermartijn642corelib is now past the mixin layer
entirely and fails on `BuiltInRegistries.POTION`, whose *field descriptor* changed in 1.21 —
`DefaultedRegistry` to `Registry`. That is a Phase 4 shape found by clearing Phase 5.

| Measure | Start of phase | Now |
|---|---|---|
| Libraries loading | 6 / 8 | **7 / 8** |
| Mixin coordinates intact | — | **88.4%** (4,485 unchanged + 49 repaired of 5,129) |
| Coordinates that do not resolve | not measurable | 595, **none of them fatal** |
| Libraries + sampled mods that type-check clean | 22 / 22 | 22 / 22 |

**Read the second and third rows together, because the second one alone flatters the phase.** 595
coordinates still do not do what their author intended, and the phase repaired only 49 of them. The
third row has no "before" figure on purpose: nothing could count these until `MixinGaps` existed, and
the pre-phase number is not 595 in any comparable sense — the same breakage was there, distributed
between mixin classes deleted wholesale and launches that aborted. What changed is that every one
this tooling can detect now switches itself off instead of taking the launch down — and that distinction is the whole phase, because a broken mixin is not like a broken
call. An unresolved member reference throws `NoSuchMethodError` on the path that reaches it; an
unresolved mixin coordinate throws during mixin *apply*, which aborts **the entire launch, every mod
in it**, including mods that had nothing to do with it.

"None of them fatal" is a claim about what is *detected*, not a proof. Anchor shapes the reachability
check does not model — `CONSTANT`, `JUMP` — and target bodies it cannot read are treated as
reachable, deliberately, because a false positive silently deletes a working injector. Those can
still fail at apply. The two libraries that were blocked on mixins are the evidence that the common
cases are covered; they are not evidence that none remain.

#### The one decision the phase turned on

Before this, a single bad coordinate deleted the **whole mixin class**. That is not a contained
loss: a dropped mixin can be the only path into a static initialiser, which is exactly how placebo
came to load and register nothing while reporting no error at all.

**So the granularity of failure moved from the mixin class to the individual injector.** What cannot
be repaired gets `require = 0`, which makes that one injector a silent no-op and leaves every other
injector in the same class applying normally. `MIXIN_DROP` is now the last resort rather than the
only tool.

That mechanism was verified against Mixin 0.8.5 rather than assumed, because the whole phase rests
on it: `InjectionInfo.validateTargets` and `InjectionInfo.postInject` both throw only when
`requiredCallbackCount > 0`, and `parseRequirements` takes an explicit `require` over the mixin
config's `defaultRequire`. Reading the bytecode took ten minutes and replaced a guess that would
have been found wrong by a launch.

Across all 433 corpus jars, 569 mixin actions in 96 jars:

| Action | Count |
|---|---|
| `MIXIN_SOFT_FAIL` | 309 |
| `MIXIN_ACCESSOR_STUB` | 99 |
| `MIXIN_SHADOW_FIELD_STUB` | 54 |
| `MIXIN_SHADOW_METHOD_STUB` | 42 |
| `MIXIN_DROP` | 36 |
| `MIXIN_SELECTOR_REPAIR` / `MIXIN_AT_REPAIR` / `MIXIN_AT_OWNER_RENAME` | 14 / 10 / 1 |

Only the bottom row is a repair. **The phase's value is in the top four rows, which are losses that
used to be launch failures**, and in the fact that `MIXIN_DROP` — the only tool that existed before —
now accounts for 36 of 569 rather than all of them.

#### Seven passes, and none of them needs a rule

| Pass | For |
|---|---|
| SRG-in-text | Every coordinate in every Forge mod (pre-existing) |
| Type rename in selectors | A descriptor naming a type that moved |
| `@At` owner rename | An anchor into a method Forge itself patched |
| Descriptor repair | The name survives, the descriptor moved |
| Injector defuse | Nothing above resolves it |
| Accessor / shadow stub | Points at a deleted member |
| Mixin drop | Target class gone, or became an interface (pre-existing) |

Not one of them consults `forward.rules.tsv`. This is the same property that carried Phase 4 — the
platform is asked what the answer is, rather than a human enumerating cases — and it matters more
here, because the mixin queue has no head to work down: nothing in it exceeds 8 jars, so a
rule-per-case approach would have been all cost and no leverage.

#### What Phase 5 built beyond the passes

**`MixinGaps`**, the third gap report, and it had to exist. `RenameGaps` and `VanillaGaps` both read
a mined usage file, which works because ordinary code addresses its targets through the constant
pool. Mixins address theirs as *text*, so a mixin-heavy mod gets a clean bill of health from both
reports right up to the launch that aborts. Pointed at a single jar it names a library's blocker in
seconds — it found yungsapi's `CriteriaTriggers.CRITERIA` and supermartijn642corelib's
`SpriteResourceLoader` offline, both of which had previously cost a launch each to discover.

It also answers the question the roadmap called the hard part, and that nothing else can: **an
anchor whose member still exists on a method that no longer calls it.** Both halves resolve, Mixin
scans exactly that method, finds nothing, and throws. The platform jar has the bodies, so it is
answerable offline — 53 of them, invisible to every signature check.

#### Three bugs worth remembering

**Removing `@Accessor` reclassifies the whole mixin.** Mixin decides a mixin's *variant* from the
mixin class, in `MixinInfo.getVariant`: an interface is an ACCESSOR mixin only while every method it
declares is an accessor **or synthetic**, and an INTERFACE mixin otherwise — which may only target
an interface. So stubbing out one dead accessor turned `InvalidAccessorException` into `@Mixin
target type mismatch: ... is not an interface`, which is no improvement whatsoever. It regressed
balm from 100% to not loading, and balm has nothing to do with the accessor that was stubbed. The
fix is to mark the degraded method `ACC_SYNTHETIC`, which keeps the variant and means nothing to
method resolution at the JVM level.

**A defused injector must also leave its `@Group`.** A named injector group carries its own `min`
check in `InjectorGroupInfo.validate` that `require` does not reach, so the soft-fail would look
applied while changing nothing.

**Mixin selectors have two spellings and only one was parsed.** `Lnet/minecraft/Foo;bar()V` and
`net/minecraft/Foo.bar()V` both occur, and they are not interchangeable in practice: the bare form
is what mods write for `remap = false` anchors, which is precisely where Forge types appear and need
renaming. 178 coordinates were silently unjudged.

#### Known-incomplete inside Phase 5, deliberately

**595 coordinates still lose their behaviour**, ranked in `api-report/mixin-gaps.txt`. The queue is
dominated by client rendering — `ModelBakery`, `LevelRenderer`, `GameRenderer`, `ParticleEngine`,
`ChunkMap` — which is worth knowing for two reasons: those are the mods most likely to look fine and
render wrong, and **`runData` never exercises any of it**, so no harness this project has can catch
a regression there.

**The corpus-driven repair the roadmap planned was not built.** The plan was to learn injection-point
repairs from the 1,786 author-ported mixin counterparts in ATM10. It was not needed to meet the exit
criterion, and the measurement argues against doing it blind: the distribution is flat, so it is a
per-case effort against a 595-item tail rather than a mechanism. Worth revisiting when a specific
mod's behaviour matters, with the 1,786 pairs still sitting there as worked examples.

**Mixins into other mods' classes are not judged at all.** 711 targets. Nothing in the platform index
covers them, and guessing would defuse working injectors.

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

0. **An index that is missing something reports a gap in the thing it is measuring, not in
   itself.** This is one lesson and it produced four separate defects before the shape of it was
   obvious, each wearing the costume of a finding:

   - `MemberScan` is given an owner prefix, and the standing vanilla queue has always been
     generated with `net/minecraft/`. `com.mojang.blaze3d` is not under it. 157 jars call a vertex
     method 1.21 deleted, and no report this project produced had ever counted one of them.
   - The backward platform did not carry DataFixerUpper. 275 of 445 "types absent from 1.20.1"
     were DFU.
   - It did not carry authlib or `com.mojang.logging` either, and those failed *differently*: the
     owner was in `targetClasses` but had no member set, so 969 findings said things like
     `GameProfile.getId does not exist in 1.20.1`.
   - `Translate` member-indexed a chosen prefix of the platform, so `FriendlyByteBuf` — the
     684-jar item near the top of the queue — inherits from an unindexed `io.netty.buffer.ByteBuf`
     and could not be judged at all.

   **Two rules came out of it, and the second matters more than the first.** Index the whole
   platform: the platform jars *are* the API the target version offers, and indexing a subset is
   not a cheaper version of the answer, it is a different question with the same output format.
   The filter was measured at 1.7s per jar with and without, so it was not buying anything either.
   And when the index cannot answer, **say so under its own name** — `SUPERTYPE_NOT_INDEXED`
   rather than silence. An invented gap gets investigated and disproved; a vanished one is never
   looked at again.

   The other half of the same trap: **every shared library has a different version on each side.**
   1.20.1 ships authlib 4.0.43, DFU 6.0.8, netty 4.1.82, fastutil 8.5.9; 1.21.1 ships 6.0.54,
   8.0.16, 4.1.97, 8.5.12. Read them from `devenv/spi/mc-1.20.1.json` and from the MDK's resolved
   `compileClasspath`, never from what happens to be newest in the Gradle cache. Putting a 1.21.1
   library on the 1.20.1 platform makes a real gap *resolve*, which is the same error inverted and
   far harder to notice than an invented one.

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

15. **A mixin's *variant* is decided by the mixin class, not by what you do to one member.**
    `MixinInfo.getVariant` calls an interface an ACCESSOR mixin only while every method it declares
    is an accessor or synthetic; otherwise it is an INTERFACE mixin, which may only be applied to an
    interface target. So removing a single dead `@Accessor` annotation reclassifies the whole mixin
    and swaps `InvalidAccessorException` for `@Mixin target type mismatch: ... is not an interface`.
    It regressed balm from 100% to not loading, on a mixin unrelated to the one being fixed. Mark
    degraded members `ACC_SYNTHETIC`: it preserves the variant and means nothing to the JVM's method
    resolution. **Read Mixin's own bytecode before assuming what an annotation change does** — this
    and the `require = 0` semantics both took ten minutes to check and would each have cost a launch
    to find.

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
