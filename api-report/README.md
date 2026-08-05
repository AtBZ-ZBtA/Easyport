# api-report — what the corpus actually calls

Measured surface of the API across all 433 jars in All the Mods 9 — the Forge half and the
vanilla half. These files are the standing work queue: they say what is worth writing, in what
order, and — for a subsystem being ported — exactly which members have to exist.

Regenerate with:

```bash
java -cp "devenv/spi/asm.jar;devenv/spi/asm-tree.jar" tools/MemberScan.java "Scrapyard/forge 1.20.1 modpacks/All the Mods 9 - ATM9/mods" "net/minecraftforge/" > api-report/forge-api-usage.txt
java -cp "devenv/spi/asm.jar;devenv/spi/asm-tree.jar" tools/MemberScan.java "Scrapyard/forge 1.20.1 modpacks/All the Mods 9 - ATM9/mods" "net/minecraft/" > api-report/vanilla-api-usage.txt
java -cp "devenv/spi/asm.jar;devenv/spi/asm-tree.jar" tools/MemberScan.java "Scrapyard/forge 1.20.1 modpacks/All the Mods 9 - ATM9/mods" "com/mojang/" > api-report/lib-api-usage.txt
```

**The prefix is the scope of everything downstream.** For a long time only the first two were
run, so `com.mojang.blaze3d` — same jar, same obfuscation, and reworked in 1.21 harder than most
of vanilla — appeared in no report here. `VertexConsumer.endVertex` is 157 of the 433 jars and had
never been counted. If you add a scan, add it to this list; the reports cannot tell you about a
prefix nobody asked them for.

`org/joml/` and `it/unimi/dsi/` were checked the same way and are clean at 100% (434 and 1,404
references), so they are not kept as standing files.

## Files

| File | What it holds |
|---|---|
| `forge-api-usage.txt` | Every `net.minecraftforge` type and member the corpus references, ranked by how many jars use it |
| `vanilla-api-usage.txt` | The same for `net.minecraft` — 25,000 member references, the input to `VanillaGaps` |
| `network-usage.txt` | The Forge scan narrowed to `net.minecraftforge.network` — the input to the networking shims |
| `unresolved-types.txt` | Output of `RenameGaps`: what nothing resolves, plus four classes of *wrong* resolution |
| `lib-api-usage.txt` | The same for `com.mojang` — blaze3d, DataFixerUpper, authlib, brigadier |
| `vanilla-gaps.txt` | Output of `VanillaGaps`: vanilla members that no longer exist on types that still do |
| `lib-gaps.txt` | `VanillaGaps` over the `com.mojang` scan. Needs the shared libraries on the platform list at the versions **1.21.1** resolves, or a library that both versions ship reads as deleted |
| `mixin-gaps.txt` | Output of `MixinGaps`: mixin coordinates that no longer point at anything. **Reads the jars, not a usage file** |

## Three reports, because they are not the same question

`RenameGaps` asks whether a **Forge** type resolves to anything. Pointed at `net.minecraft` that
question is nearly always answered yes and is the wrong one: 1.21 mostly kept type names and
changed what is *inside* them. `ItemStack` still exists and no longer has `getTag`. So
`VanillaGaps` inverts the emphasis — missing types are a footnote, and the body of the report is
members that no longer exist on types that do.

`MixinGaps` asks a question **neither of the others can even see**. Both of them read the mined
usage files above, which works because ordinary code addresses its targets through the constant
pool. Mixins address theirs as *text* — `@Inject(method = "...")`, `@At(target = "...")`,
`@Accessor("...")` — so no member scan contains them, and a mixin-heavy mod gets a clean bill of
health from both right up to the launch that aborts. It reads the jars directly, and its output is
not a list of unresolved symbols but a list of **behaviour the translated mod no longer has**:
everything it reports already loads.

Read `vanilla-gaps.txt`'s **`BY OWNING TYPE`** rollup before its ranked lists. It says which
*subsystem* to fix, which is a better question than which single member: forty one-jar findings
on one class are worth more than one forty-jar finding on a class nothing else touches.

**Both reports know what the rule-free passes handle, and that has to be maintained.** Holder
adaptation, `ARG_FILL` and `ARG_DROP` discover their targets from the platform's own descriptors,
so nothing in `forward.rules.tsv` marks that work done. Before the reports were taught about them
they showed roughly 1,600 jar-references of *finished* work at the head of the queue. If you add a
pass that needs no rules, teach the reports about it in the same change — a queue that lists
completed work is worse than a shorter one, because the top of it stops being where to look.

## The silent-failure checks

`unresolved-types.txt` leads with warnings, not gaps, because a rule that resolves incorrectly
is worse than a missing one — the gap report stops mentioning it, and the failure moves to
runtime.

**Rename target missing a called member.** The rule resolves but the target does not have what
the corpus calls on it. NeoForge kept many Forge names on differently-shaped types:
`ICapabilityProvider` is a one-method `LazyOptional` producer in Forge and a three-parameter
generic interface in NeoForge. 106 jars *implement* the Forge one; renaming leaves NeoForge's
abstract method unimplemented and fails with `AbstractMethodError` only when something asks for
a capability. This check also withdrew a rule written the same hour —
`LivingTickEvent` → `EntityTickEvent$Pre` is the right event, but `getEntity()` narrowed from
`LivingEntity` to `Entity`, so all 41 callers would take a `NoSuchMethodError`.

**Rename onto an abstract type with `Pre`/`Post` children.** NeoForge split several concrete
Forge events and kept the old name as an abstract parent. The rename resolves, the mod loads,
the listener registers — and nothing ever posts that class. Caught `LivingDamageEvent`, where
24 jars would have had listeners that load cleanly and never fire.

**Shim shadowed by a rule.** A broadly-scoped prefix rule can quietly disable a shim written to
paper over a signature difference.

Narrow beats complete here. Flagging *every* abstract target produced 91 hits, nearly all
correct interfaces like `IItemHandler`; a warning list that size gets skimmed and ignored.

Counts are **jars, not call sites**. One mod calling something forty times is one mod's worth of
evidence; raw occurrence counts let a single heavy user outvote the corpus.

## Why this exists

Shims were being written from recollection of the Forge API, which covers the methods that come
to mind and misses the ones that don't. The corpus then fails on the difference, one mod at a
time, and each failure costs a full verify cycle to discover.

It also corrects priorities that look obvious and aren't. Networking was on the roadmap as a
late, geckolib-shaped problem; the scan put `SimpleChannel` in 162 of 433 jars and moved it to
the front. Conversely `create` felt like the high-value target for a long time and turns out to
be barely referenced by anything.

## Ranked state

Regenerate both reports rather than trusting a table here — they go stale. As of Phase 4
sign-off:

| Forge side | |
|---|---|
| Referenced Forge types | 792 |
| Resolved by forge-compat | 88 |
| Resolved by a rule | 587 |
| **Unresolved** | **117** |

| Vanilla side | |
|---|---|
| Member references checked | 25,288 |
| **Still resolve** | **23,320 — 92.2%** |
| of which, by Holder adaptation | 234 |
| of which, by `ARG_FILL` / `ARG_DROP` | 182 |
| of which, by a `COERCE` conversion | 90 |
| Signature changed | 954 |
| Member gone | 1,014 |
| Types deleted outright | 114 |

The head of the distribution is done: the event bus, mod-loading context, registries, config,
networking, capabilities, item/fluid/energy handlers and the tag layer are all shimmed or
renamed.

What remains splits three ways, and only the first is really shim work:

1. **Split helper classes** — `ForgeHooks` (91 jars) and `ForgeEventFactory` (80) were divided
   across NeoForge's `EventHooks` and `CommonHooks`. Needs per-method rules, not a type rename.
2. **Restructured events** — the `LivingEvent` family. NeoForge renamed the accessors along with
   the events, so a type rename cannot follow; these need the shim-and-bridge shape used for
   `TickEvent`.
3. **Vanilla drift wearing a Forge hat** — the bulk of the 427 rename-target member mismatches
   are the 1.20.5 NBT-to-components migration surfacing through Forge types (`FluidStack.getTag`,
   `ItemStackHandler.serializeNBT`). Shares its fix with the vanilla side below.

On the vanilla side, ranked by the `BY OWNING TYPE` rollup:

| Owner | Weight | What it is |
|---|---|---|
| `ItemStack` | 1,207 | NBT → data components. The four commonest -- getTag, getOrCreateTag, setTag, hasTag -- are now bridged onto CUSTOM_DATA; the weight is the rest of the type |
| `FriendlyByteBuf` | 684 | The `StreamCodec` migration |
| `Enchantments` | 492 | Enchantments became data-driven; the constants are `ResourceKey`s now, which need a registry lookup rather than a wrap |
| `Ingredient` | 349 | Final in 1.21; custom ingredients go through `ICustomIngredient` |
| `BlockEntity` | 312 | **Only the override half.** `ARG_FILL` handles the call sites |
| `EnchantmentHelper` | 281 | Follows the `Enchantments` change |

`BlockEntity` names the one shape none of the seven Phase 4 mechanisms reaches. They all adapt
*call sites*, and a mod that declares `saveAdditional(CompoundTag)` is not calling anything — it
is failing to override something. The method links, the class loads, and vanilla never calls it.
Fixing it means rewriting the mod's own method signature and adapting its body, which is a
different kind of pass to anything here.

On the mixin side, the numbers mean something different and the difference matters. As of Phase 5
sign-off, of 5,129 coordinates **88.4% are intact** — 4,485 unchanged plus 49 whose descriptor was
repaired off the platform — and the remaining 595 are split 366 defused injectors and 229 stubbed
accessors and shadows.

**All 595 load.** None of them aborts a launch any more, and every one of them has stopped doing
what its author intended. That is the trade the phase made deliberately, and it is why the report
counts the four outcomes separately instead of printing a pass rate.

| Target | Jars | What it is |
|---|---|---|
| `ItemStack` | 12 | The same data-component migration, reached through mixins |
| `ModelBakery` | 8 | 1.21 restructured model loading; `loadModel` is gone outright |
| `WorldGenRegion` | 8 | Accessors onto fields that moved |
| `PotionBrewing` | 8 | Became instance-based with a builder; `POTION_MIXES` and `addMix` both gone |
| `RecipeManager` | 7 | `RecipeInput` replaced `Container` throughout |
| `LevelRenderer` | 6 | Renderer signatures changed wholesale |
| `ChunkMap` | 6 | Chunk tracking rewritten |
| `EnchantmentHelper` | 6 | Follows the data-driven enchantment change |

**Read that list for its shape, not its entries.** It is overwhelmingly *client rendering*, which is
worth knowing twice over: those mods will load and then draw the wrong thing, and `runData` — the
only launch harness this project has — never touches any of it. Nothing here will catch a regression
in that column.
