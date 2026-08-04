# api-report — what the corpus actually calls

Measured surface of the Forge 1.20.1 API across all 433 jars in All the Mods 9. These files are
the standing work queue: they say what is worth writing, in what order, and -- for a subsystem
being ported -- exactly which members have to exist. Written during Phase 3; still the right
starting point for Phase 4, since vanilla drift surfaces through Forge types too.

Regenerate with:

```bash
java -cp "devenv/spi/asm.jar;devenv/spi/asm-tree.jar" tools/MemberScan.java "Scrapyard/forge 1.20.1 modpacks/All the Mods 9 - ATM9/mods" "net/minecraftforge/" > api-report/forge-api-usage.txt
```

## Files

| File | What it holds |
|---|---|
| `forge-api-usage.txt` | Every `net.minecraftforge` type and member the corpus references, ranked by how many jars use it |
| `network-usage.txt` | The same, narrowed to `net.minecraftforge.network` — the input to the networking shims |
| `unresolved-types.txt` | Output of `RenameGaps`: what nothing resolves, plus four classes of *wrong* resolution |

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

Regenerate `unresolved-types.txt` rather than trusting a table here — it goes stale. As of
Phase 3 sign-off:

| | |
|---|---|
| Referenced Forge types | 792 |
| Resolved by forge-compat | 71 |
| Resolved by a rule | 581 |
| **Unresolved** | **140** |
| Unresolved, weighted by jars using them | ~1,400 of 17,771 — **92% resolved** |

The head of the distribution is done: the event bus, mod-loading context, registries, config,
networking, capabilities, item/fluid/energy handlers and the tag layer are all shimmed or
renamed.

What remains splits three ways, and only the first is really shim work:

1. **Split helper classes** — `ForgeHooks` (91 jars) and `ForgeEventFactory` (80) were divided
   across NeoForge's `EventHooks` and `CommonHooks`. Needs per-method rules, not a type rename.
2. **Restructured events** — the `LivingEvent` family. NeoForge renamed the accessors along with
   the events, so a type rename cannot follow; these need the shim-and-bridge shape used for
   `TickEvent`.
3. **Vanilla drift wearing a Forge hat** — the bulk of the 426 rename-target member mismatches
   are the 1.20.5 NBT-to-components migration surfacing through Forge types (`FluidStack.getTag`,
   `ItemStackHandler.serializeNBT`). Phase 4.
