# api-report — what the corpus actually calls

Measured surface of the API across all 433 jars in All the Mods 9 — the Forge half and the
vanilla half. These files are the standing work queue: they say what is worth writing, in what
order, and — for a subsystem being ported — exactly which members have to exist.

Regenerate with:

```bash
java -cp "devenv/spi/asm.jar;devenv/spi/asm-tree.jar" tools/MemberScan.java "Scrapyard/forge 1.20.1 modpacks/All the Mods 9 - ATM9/mods" "net/minecraftforge/" > api-report/forge-api-usage.txt
java -cp "devenv/spi/asm.jar;devenv/spi/asm-tree.jar" tools/MemberScan.java "Scrapyard/forge 1.20.1 modpacks/All the Mods 9 - ATM9/mods" "net/minecraft/" > api-report/vanilla-api-usage.txt
```

## Files

| File | What it holds |
|---|---|
| `forge-api-usage.txt` | Every `net.minecraftforge` type and member the corpus references, ranked by how many jars use it |
| `vanilla-api-usage.txt` | The same for `net.minecraft` — 25,000 member references, the input to `VanillaGaps` |
| `network-usage.txt` | The Forge scan narrowed to `net.minecraftforge.network` — the input to the networking shims |
| `unresolved-types.txt` | Output of `RenameGaps`: what nothing resolves, plus four classes of *wrong* resolution |
| `vanilla-gaps.txt` | Output of `VanillaGaps`: vanilla members that no longer exist on types that still do |

## Two reports, because they are not the same question

`RenameGaps` asks whether a **Forge** type resolves to anything. Pointed at `net.minecraft` that
question is nearly always answered yes and is the wrong one: 1.21 mostly kept type names and
changed what is *inside* them. `ItemStack` still exists and no longer has `getTag`. So
`VanillaGaps` inverts the emphasis — missing types are a footnote, and the body of the report is
members that no longer exist on types that do.

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
| **Still resolve** | **23,230 — 91.9%** |
| of which, by Holder adaptation | 234 |
| of which, by `ARG_FILL` / `ARG_DROP` | 182 |
| Signature changed | 1,044 |
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
| `BuiltInLootTables` | 304 | `ResourceKey` wrapping; `register` itself is bridged |

`BlockEntity` names the one shape none of the seven Phase 4 mechanisms reaches. They all adapt
*call sites*, and a mod that declares `saveAdditional(CompoundTag)` is not calling anything — it
is failing to override something. The method links, the class loads, and vanilla never calls it.
Fixing it means rewriting the mod's own method signature and adapting its body, which is a
different kind of pass to anything here.
