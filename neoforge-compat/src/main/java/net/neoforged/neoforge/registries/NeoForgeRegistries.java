package net.neoforged.neoforge.registries;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Shim: NeoForge's own registries, aliased onto Forge 1.20.1's.
 *
 * Named by 100 corpus jars through its {@code Keys} holder alone. Nothing here is a wrapper — the
 * keys are plain {@code ResourceKey}s that both loaders declare against the same vanilla
 * {@code Registry} type, so aliasing is exact rather than adapted.
 *
 * <h2>What is missing is the point</h2>
 *
 * {@code ATTACHMENT_TYPES} — data attachments, named by 38 jars — has no Forge 1.20.1 counterpart
 * at all. It is a NeoForge feature, not a rename of something older, so there is no key to alias
 * and no registry to register into. A field returning null would let a mod's static initialiser
 * run and fail somewhere else entirely; leaving it absent makes the mod fail at the reference to
 * it, naming exactly the feature that is missing.
 *
 * That is the same trade the whole project makes, arriving from the other direction: forward, the
 * hard cases are things 1.21 added that a 1.20.1 mod cannot know about; backward, they are things
 * 1.21 added that a 1.20.1 *game* cannot represent.
 */
public class NeoForgeRegistries {

    private NeoForgeRegistries() {}

    // No `Registry` fields, and the first attempt at them is worth recording rather than quietly
    // deleting. NeoForge exposes these as live vanilla Registry objects; Forge 1.20.1 exposes a
    // Supplier<IForgeRegistry> and only publishes the vanilla view during NewRegistryEvent --
    // which fires *after* mod construction. So a field initialised eagerly is null exactly when a
    // mod's static initialiser reads it, and the failure lands inside this shim as
    // `Cannot invoke "Registry.key()" because "<parameter1>" is null`, pointing at the wrong file.
    //
    // A lazy field is not available either: mod code reads the field and passes the object on, so
    // there is nothing to intercept. Serving it properly would mean implementing vanilla's
    // Registry interface as a stand-in that answers key() and refuses the rest, which is a real
    // piece of work and not one to guess at.
    //
    // Absent, a mod referencing it gets NoSuchFieldError naming the field. The `Keys` below are
    // plain ResourceKeys, need no live registry, and cover the common case -- 36 of the 38 jars
    // that touch this class use the key form.

    public static final class Keys {

        private Keys() {}

        public static final ResourceKey<? extends Registry<?>> FLUID_TYPES =
                ForgeRegistries.Keys.FLUID_TYPES;

        public static final ResourceKey<? extends Registry<?>> BIOME_MODIFIERS =
                ForgeRegistries.Keys.BIOME_MODIFIERS;

        public static final ResourceKey<? extends Registry<?>> GLOBAL_LOOT_MODIFIER_SERIALIZERS =
                ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS;

        public static final ResourceKey<? extends Registry<?>> STRUCTURE_MODIFIERS =
                ForgeRegistries.Keys.STRUCTURE_MODIFIERS;

        public static final ResourceKey<? extends Registry<?>> ENTITY_DATA_SERIALIZERS =
                ForgeRegistries.Keys.ENTITY_DATA_SERIALIZERS;
    }


}
