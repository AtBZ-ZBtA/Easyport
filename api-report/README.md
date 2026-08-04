# api-report — what the corpus actually calls

Measured surface of the Forge 1.20.1 API across all 433 jars in All the Mods 9. These files are
the work queue for Phase 3: they say which shims are worth writing, in what order, and — for a
subsystem being ported — exactly which members have to exist.

Regenerate with:

```bash
java -cp "devenv/spi/asm.jar;devenv/spi/asm-tree.jar" tools/MemberScan.java "Scrapyard/forge 1.20.1 modpacks/All the Mods 9 - ATM9/mods" "net/minecraftforge/" > api-report/forge-api-usage.txt
```

## Files

| File | What it holds |
|---|---|
| `forge-api-usage.txt` | Every `net.minecraftforge` type and member the corpus references, ranked by how many jars use it |
| `network-usage.txt` | The same, narrowed to `net.minecraftforge.network` — the input to the networking shims |
| `unresolved-types.txt` | Output of `RenameGaps`: what nothing resolves, plus three classes of *wrong* resolution |

## The three silent-failure checks

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

## Ranked state of the top of the queue

As of the scan. "Done" means a shim or rename exists, not that it is complete.

| Jars | Type | State |
|---|---|---|
| 365 | `eventbus.api.IEventBus` | done |
| 360 | `fml.javafmlmod.FMLJavaModLoadingContext` | done |
| 291 | `common.MinecraftForge` | done |
| 268 | `registries.ForgeRegistries` | done |
| 252 | `fml.ModLoadingContext` | done |
| 234 | `registries.RegistryObject` | done |
| 219 | `common.ForgeConfigSpec$Builder` | done |
| 218 | `fml.ModList` | done |
| 213 | `registries.DeferredRegister` | done |
| 210 | `api.distmarker.Dist` | renamed |
| 207 | `common.util.LazyOptional` | done |
| 205 | `fml.config.ModConfig$Type` | done |
| 166 | `network.NetworkEvent$Context` | done |
| **164** | **`common.capabilities.ForgeCapabilities`** | **not started** |
| 162 | `network.simple.SimpleChannel` | done |
| 148 | `fml.DistExecutor` | done |
| 134 | `network.NetworkHooks` | done |
| **130** | **`common.Tags$Items`** | **not started** |
| 129 | `data.event.GatherDataEvent` | datagen only; no runtime impact |
| **124** | **`fluids.FluidStack`** | **not started** |
| **118** | **`items.IItemHandler`** | **not started** |
| **106** | **`common.capabilities.ICapabilityProvider`** | **not started** |
| **91** | **`common.ForgeHooks`** | **not started** |

The next cluster is **capabilities and the handlers built on them** —
`ForgeCapabilities` + `ICapabilityProvider` + `CapabilityManager` + `CapabilityToken` +
`IItemHandler` + `FluidStack` together cover most of what remains above 90 jars, and they are
one design problem rather than six. NeoForge replaced Forge's capability model outright
(`BlockCapability`/`ItemCapability`, resolved by lookup rather than attached per-object), so
this is the first item on the queue that a delegating shim cannot cover — the roadmap has always
flagged capabilities as a lifecycle change rather than a rename.
