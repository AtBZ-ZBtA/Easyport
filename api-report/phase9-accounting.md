# Phase 9 accounting — every remaining verification error, placed

Phase 9's exit criterion is not "no errors". It is that **nothing is unaccounted for**: every
remaining verification error is either fixed, or on this list with a reason beside it.

Measured over the whole corpus by `tools/verify-sweep.sh` and `tools/verify-sweep-backward.sh`.

| | Swept | Type-check clean | With errors |
|---|---|---|---|
| Forward (ATM9 → NeoForge 1.21.1) | 433 | **263 (60.7%)** | 170 |
| Backward (ATM10 → Forge 1.20.1) | 479 | **110 (23.0%)** | 369 |

Error shapes, by kind:

| Kind | Forward | Backward | What it is |
|---|---|---|---|
| `[missing-platform-type]` | 458 | 3,597 | A type the target does not have |
| `[hierarchy]` | 90 | 6 | Extending something final, or overriding a now-final method |
| `[types]` | 61 | 37 | A real type mismatch — a translation defect |

## How a missing type is placed

Mechanically first: does the target platform have a type of the same simple name?

| | Counterpart exists | Nothing by that name |
|---|---|---|
| Forward | 35 | 74 |
| Backward | 109 | 265 |

**A counterpart is always a QUEUE item, never a wall.** The surface differs — that is why the
rename was refused — but an adapted shim or a per-member rule can bridge it. `SurfaceMatch` refusing
`IForgeItem`→`IItemExtension` at 58 members against 55 means *write the adapter*, not *give up*.

**"Nothing by that name" is not automatically a wall either.** 1.21 replaced `MobType` with entity
type tags: the name is gone, the concept is expressible. A wall is only where the **concept** has
no representation in the target, and each one below says which.

---

## WALLS — backward (1.21.1 → 1.20.1)

These are things a 1.20.1 game cannot represent. They are not deferred work; they are the boundary
of what this direction can do, and a mod that depends on one cannot be fully translated.

### The data-component system — 476 jar-hits across 36 types
`net/minecraft/core/component/**` (199, 10 types), `net/minecraft/world/item/component/**` (277, 26)

1.20.5 replaced item NBT with a typed component system: a registry of `DataComponentType`, a
per-stack `DataComponentMap`, patches, and ~26 built-in component types. **1.20.1 has none of the
machinery** — an `ItemStack` there carries a `CompoundTag` and nothing else.

Individual components whose payload is just data (`CustomData`) can be flattened back into NBT, and
that is queue work. The *type system* cannot be: a mod that registers its own `DataComponentType`
is asking for a registry that does not exist, and a shim that accepted the registration and dropped
it would produce items that silently lose their data on save. **Wall.**

### Data attachments — 30 across 6 types
`net/neoforged/neoforge/attachment/**`

NeoForge 1.21's replacement for capabilities-as-storage, attaching typed data to blocks, entities
and chunks with automatic serialisation. Forge 1.20.1 has capabilities, which are a different
model — attachments are registry-declared and serialise themselves; capabilities are per-object
providers. Bridgeable in the narrow case, not in general. **Wall**, with the narrow case noted as
queue work under capabilities below.

### Registry data maps — 38 across 9 types
`net/neoforged/neoforge/registries/datamaps/**`

Data-driven per-registry-entry data, added in NeoForge 1.21. No 1.20.1 equivalent in either the
loader or the game. **Wall.**

### Data-driven enchantments — 38 across 5 types
`net/minecraft/world/item/enchantment/**` (`ItemEnchantments`, `EnchantmentEffectComponents`, …)

1.21 made enchantments datapack-defined with component-based effects. In 1.20.1 an enchantment is a
registered Java object with overridable methods. The direction matters: a *1.21 mod* that ships
enchantment JSON and reads effect components has nothing in 1.20.1 to register against. **Wall.**

### Armour material as a registry — 43
`net/minecraft/world/item/ArmorMaterial$Layer` and the registry around it

1.21 made armour materials a registry with layer definitions; 1.20.1 reads an enum. This is the
backward mirror of the forward `ArmorMaterials` problem and the one `aquaculture` sits on. There is
no registry to register into. **Wall.**

---

## WALLS — forward (1.20.1 → 1.21.1)

Far fewer, because 1.21 mostly *added* rather than removed.

### `OutOfJarResourceLocation extends ResourceLocation` — 1 mod
1.21 made `ResourceLocation` final. A mod that subclasses it cannot be adapted without rewriting
every construction site to a different type, which changes the mod's own public API. **Wall.**

### Extending other now-final vanilla classes — 90 `[hierarchy]` hits
`OptionInstance`, `Enchantment`, and others 1.21 sealed.

Overriding a now-final *method* is handled (`fixIllegalHierarchy` renames the override away, 329
applications). **Extending a now-final class is not fixable by this project**: the mod's class *is*
the thing that no longer has a valid shape. Each one is a wall for that mod, and they are counted
rather than named individually because the list is per-mod rather than per-API.

---

## QUEUE — work with a known approach

Everything not walled above. Ranked by jars blocked.

### Backward, by cluster

| Cluster | Jars | Approach |
|---|---|---|
| `CustomPacketPayload` + `StreamCodec` + `ByteBufCodecs` | 193 / 75 / 103 | Shim the types under `easyport.neovanilla`, bridge registration onto 1.20.1's `SimpleChannel`. The forward direction already solved the mirror of this (`ForgeChannelPayload`) |
| `RegistryFriendlyByteBuf` | 149 | Shim extending 1.20.1's `FriendlyByteBuf`; the registry access it adds is available from the server instance |
| `RecipeInput` / `RecipeHolder` | 89 / 65 | 1.21 abstractions over `Container` and a recipe-plus-id pair. Both are thin wrappers with 1.20.1 equivalents underneath |
| NeoForge types with a differing Forge counterpart (109 types) | — | `ItemStackHandler` 72, `FluidStack` 71, `IClientItemExtensions` 60, `ModelBuilder` 51, `FakePlayer` 50, `ICondition` 49, `FluidType` 38, `IUnbakedGeometry` 38. Each needs an adapted shim, not a rename — `SurfaceMatch` refused all but 5 of 179 |
| Capabilities | 80 | Forge 1.20.1 has a capability system; NeoForge 1.21 rewrote the API around it. An adapter is possible for the common shapes |
| `IConfigScreenFactory` | 76 | Forge's equivalent is `ConfigScreenHandler$ConfigScreenFactory` — different nesting, same job |

### Forward, by cluster

The forward tail is flat: **60 of 170 dirty jars are blocked by exactly one type**, 28 more by two,
and the largest single blocker is 8 jars. There is no head to work down, only a long handle.

| Cluster | Jars | Approach |
|---|---|---|
| `Ingredient` and the Forge ingredient types | 29 + 37 | 1.21 rebuilt ingredients on components; `StrictNBTIngredient`→`DataComponentIngredient` needs an adapter (7 members against 18) |
| `CopyNbtFunction$Builder` | 25 | Renamed to `CopyCustomDataFunction`; surface differs, so per-member rules |
| `ITeleporter` | 23 | Confirmed gone from NeoForge 1.21.1. The concept moved into vanilla as `DimensionTransition` + `Portal`, so this is an adapter rather than a wall — a mod implementing `ITeleporter` and handing it to `changeDimension` needs both the interface shimmed and the call site retargeted |
| `AbstractTreeGrower` | 22 | Became `TreeGrower`, a data-driven type. Adapter |
| `MobType` | 22 | Replaced by entity type tags. Bridge that maps the old enum onto tag membership |
| `AbstractGlassBlock` / `GlassBlock` | 29 | Merged into `TransparentBlock`. Shim class under `easyport.vanilla` |
| `ForgeBiomeModifiers` family | 30 | NeoForge has `BiomeModifiers` with the same job |
| `BlockPathTypes` → `PathType`, `MapDecoration$Type` → `MapDecorationType`, `LiteralContents` → `PlainTextContents$LiteralContents` | 31 | Package/name moves whose surfaces differ; per-member rules on top of the rename |
| `AbstractProjectileDispenseBehavior` | 16 | Restructured in 1.21; adapter |

### Translation defects — highest value, smallest count

61 forward and 37 backward `[types]` hits. **These are bugs, not absent platform**, and 23 forward
jars fail on nothing else. Largest:

| Defect | Hits | Note |
|---|---|---|
| `ArmorMaterials` expected, `ArmorMaterial` found | 8 | The Holder-unwrap gives back the value type where the call site wants the old enum |
| `ModelResourceLocation` where `ResourceLocation` expected | 5 | 1.21 stopped `MRL` extending `RL`; the COERCE pass has a gap |
| `easyport/vanilla/AbstractCriterionTriggerInstance` return type | 2 | A defect in one of our own shims |

Three shapes are **verifier imprecision, not defects**, and are documented rather than chased:
`Expected an object reference, but found .` (5), a `BufferedReader` merged to `Object` (4), and
`IllegalAccessError … in unnamed module` (3). `SimpleVerifier` merges types by loading classes and
computing a common supertype; where it cannot be precise it reports an error the real JVM verifier
would not. The differencing against each mod's own reference port removes most of these, and what
survives here is where the reference had no counterpart to difference against.

---

## Regenerating this

```bash
bash tools/verify-sweep.sh          < batch-report/forward-all.tsv    # EASYPORT_SWEEP_OUT=...
bash tools/verify-sweep-backward.sh < batch-report/backward-all.tsv
```

Then classify: index both target platforms, and for each `[missing-platform-type]` check whether a
same-simple-name type exists in the target. A counterpart means queue; nothing by that name means
*read the concept* before calling it a wall.
