package net.minecraftforge.registries;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Shim for {@code ForgeRegistries}. {@code ITEMS} alone appears in 143 corpus mods,
 * {@code BLOCKS} in 125.
 *
 * Each constant carries the vanilla registry key rather than a live registry, because these
 * are read during class initialisation — long before the registries themselves are populated.
 * Resolution is deferred to first use inside {@link IForgeRegistry}.
 *
 * Coverage here is the head of the distribution, not the whole of Forge's registry list.
 * Missing entries surface as a ClassNotFoundException naming exactly what to add, which is a
 * clear failure rather than a silent one.
 */
public final class ForgeRegistries {

    public static final IForgeRegistry<Block> BLOCKS = new ForgeRegistry<>(Registries.BLOCK);
    public static final IForgeRegistry<Item> ITEMS = new ForgeRegistry<>(Registries.ITEM);
    public static final IForgeRegistry<Fluid> FLUIDS = new ForgeRegistry<>(Registries.FLUID);
    public static final IForgeRegistry<MobEffect> MOB_EFFECTS = new ForgeRegistry<>(Registries.MOB_EFFECT);
    public static final IForgeRegistry<EntityType<?>> ENTITY_TYPES = new ForgeRegistry<>(Registries.ENTITY_TYPE);
    public static final IForgeRegistry<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            new ForgeRegistry<>(Registries.BLOCK_ENTITY_TYPE);
    public static final IForgeRegistry<MenuType<?>> MENU_TYPES = new ForgeRegistry<>(Registries.MENU);
    public static final IForgeRegistry<Enchantment> ENCHANTMENTS = new ForgeRegistry<>(Registries.ENCHANTMENT);
    public static final IForgeRegistry<net.minecraft.sounds.SoundEvent> SOUND_EVENTS =
            new ForgeRegistry<>(Registries.SOUND_EVENT);
    public static final IForgeRegistry<net.minecraft.world.item.crafting.RecipeType<?>> RECIPE_TYPES =
            new ForgeRegistry<>(Registries.RECIPE_TYPE);
    public static final IForgeRegistry<net.minecraft.world.item.crafting.RecipeSerializer<?>> RECIPE_SERIALIZERS =
            new ForgeRegistry<>(Registries.RECIPE_SERIALIZER);
    public static final IForgeRegistry<net.minecraft.world.entity.ai.attributes.Attribute> ATTRIBUTES =
            new ForgeRegistry<>(Registries.ATTRIBUTE);
    public static final IForgeRegistry<net.minecraft.core.particles.ParticleType<?>> PARTICLE_TYPES =
            new ForgeRegistry<>(Registries.PARTICLE_TYPE);

    // The tail, added from the shim audit rather than one launch failure at a time. Each of
    // these is a NoSuchFieldError the moment a mod touches it, and the class resolving fine
    // means nothing catches it earlier. Counts are corpus jars: POTIONS 31, FEATURES 28.
    public static final IForgeRegistry<net.minecraft.world.item.alchemy.Potion> POTIONS =
            new ForgeRegistry<>(Registries.POTION);
    public static final IForgeRegistry<net.minecraft.world.level.levelgen.feature.Feature<?>> FEATURES =
            new ForgeRegistry<>(Registries.FEATURE);
    public static final IForgeRegistry<net.minecraft.world.level.biome.Biome> BIOMES =
            new ForgeRegistry<>(Registries.BIOME);
    public static final IForgeRegistry<net.minecraft.world.entity.npc.VillagerProfession> VILLAGER_PROFESSIONS =
            new ForgeRegistry<>(Registries.VILLAGER_PROFESSION);
    public static final IForgeRegistry<net.minecraft.world.entity.ai.village.poi.PoiType> POI_TYPES =
            new ForgeRegistry<>(Registries.POINT_OF_INTEREST_TYPE);
    public static final IForgeRegistry<net.minecraft.stats.StatType<?>> STAT_TYPES =
            new ForgeRegistry<>(Registries.STAT_TYPE);
    public static final IForgeRegistry<net.minecraft.world.entity.decoration.PaintingVariant> PAINTING_VARIANTS =
            new ForgeRegistry<>(Registries.PAINTING_VARIANT);
    public static final IForgeRegistry<net.minecraft.world.level.levelgen.carver.WorldCarver<?>> WORLD_CARVERS =
            new ForgeRegistry<>(Registries.CARVER);
    public static final IForgeRegistry<net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacerType<?>>
            FOLIAGE_PLACER_TYPES = new ForgeRegistry<>(Registries.FOLIAGE_PLACER_TYPE);
    public static final IForgeRegistry<net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecoratorType<?>>
            TREE_DECORATOR_TYPES = new ForgeRegistry<>(Registries.TREE_DECORATOR_TYPE);
    public static final IForgeRegistry<net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProviderType<?>>
            BLOCK_STATE_PROVIDER_TYPES = new ForgeRegistry<>(Registries.BLOCK_STATE_PROVIDER_TYPE);
    // Typed over vanilla's element type rather than Forge's. Forge declared this constant over
    // brigadier's ArgumentType; vanilla's registry actually holds ArgumentTypeInfo. Mods name
    // only the erased IForgeRegistry in their descriptors, so the distinction never reaches
    // them -- and brigadier is not on this module's compile classpath anyway.
    public static final IForgeRegistry<net.minecraft.commands.synchronization.ArgumentTypeInfo<?, ?>>
            COMMAND_ARGUMENT_TYPES = new ForgeRegistry<>(Registries.COMMAND_ARGUMENT_TYPE);

    /**
     * Registry *keys*, as distinct from the registries above. 105 corpus jars.
     *
     * Forge exposed both: {@code ForgeRegistries.ITEMS} is a live registry you query,
     * {@code ForgeRegistries.Keys.ITEMS} is the key naming it. Keys are what
     * {@code DeferredRegister.create} and {@code RegisterEvent} take, so they are read during
     * class initialisation and must resolve without any registry existing yet.
     *
     * Two sources, because Forge drew no distinction between them and NeoForge does:
     * vanilla registries come from {@code Registries}, and the ones Forge itself added come from
     * {@code NeoForgeRegistries.Keys}. A mod referencing either sees one flat list, as before.
     *
     * Typed as {@code ResourceKey<? extends Registry<?>>} throughout. The exact type argument
     * differs between the two platforms for several of these -- NeoForge's loot and biome
     * modifier serializers hold {@code MapCodec} where Forge held {@code Codec} -- but the field
     * descriptor a mod compiled against is the erased {@code ResourceKey}, so the wildcard costs
     * nothing and avoids importing the difference into every declaration.
     */
    public static final class Keys {

        private Keys() {}

        // Vanilla registries.
        public static final ResourceKey<? extends Registry<?>> BLOCKS = Registries.BLOCK;
        public static final ResourceKey<? extends Registry<?>> ITEMS = Registries.ITEM;
        public static final ResourceKey<? extends Registry<?>> FLUIDS = Registries.FLUID;
        public static final ResourceKey<? extends Registry<?>> MOB_EFFECTS = Registries.MOB_EFFECT;
        public static final ResourceKey<? extends Registry<?>> ENTITY_TYPES = Registries.ENTITY_TYPE;
        public static final ResourceKey<? extends Registry<?>> BLOCK_ENTITY_TYPES = Registries.BLOCK_ENTITY_TYPE;
        public static final ResourceKey<? extends Registry<?>> MENU_TYPES = Registries.MENU;
        public static final ResourceKey<? extends Registry<?>> ENCHANTMENTS = Registries.ENCHANTMENT;
        public static final ResourceKey<? extends Registry<?>> SOUND_EVENTS = Registries.SOUND_EVENT;
        public static final ResourceKey<? extends Registry<?>> RECIPE_TYPES = Registries.RECIPE_TYPE;
        public static final ResourceKey<? extends Registry<?>> RECIPE_SERIALIZERS = Registries.RECIPE_SERIALIZER;
        public static final ResourceKey<? extends Registry<?>> ATTRIBUTES = Registries.ATTRIBUTE;
        public static final ResourceKey<? extends Registry<?>> PARTICLE_TYPES = Registries.PARTICLE_TYPE;
        public static final ResourceKey<? extends Registry<?>> BIOMES = Registries.BIOME;
        public static final ResourceKey<? extends Registry<?>> MEMORY_MODULE_TYPES = Registries.MEMORY_MODULE_TYPE;
        public static final ResourceKey<? extends Registry<?>> POTIONS = Registries.POTION;
        public static final ResourceKey<? extends Registry<?>> VILLAGER_PROFESSIONS = Registries.VILLAGER_PROFESSION;
        public static final ResourceKey<? extends Registry<?>> STRUCTURE_TYPES = Registries.STRUCTURE_TYPE;
        public static final ResourceKey<? extends Registry<?>> COMMAND_ARGUMENT_TYPES =
                Registries.COMMAND_ARGUMENT_TYPE;

        // Registries Forge added, which NeoForge kept under its own namespace.
        public static final ResourceKey<? extends Registry<?>> GLOBAL_LOOT_MODIFIER_SERIALIZERS =
                NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS;
        public static final ResourceKey<? extends Registry<?>> FLUID_TYPES =
                NeoForgeRegistries.Keys.FLUID_TYPES;
        public static final ResourceKey<? extends Registry<?>> BIOME_MODIFIERS =
                NeoForgeRegistries.Keys.BIOME_MODIFIERS;
        public static final ResourceKey<? extends Registry<?>> BIOME_MODIFIER_SERIALIZERS =
                NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS;
        public static final ResourceKey<? extends Registry<?>> STRUCTURE_MODIFIERS =
                NeoForgeRegistries.Keys.STRUCTURE_MODIFIERS;
        public static final ResourceKey<? extends Registry<?>> STRUCTURE_MODIFIER_SERIALIZERS =
                NeoForgeRegistries.Keys.STRUCTURE_MODIFIER_SERIALIZERS;
        public static final ResourceKey<? extends Registry<?>> ENTITY_DATA_SERIALIZERS =
                NeoForgeRegistries.Keys.ENTITY_DATA_SERIALIZERS;
        public static final ResourceKey<? extends Registry<?>> HOLDER_SET_TYPES =
                NeoForgeRegistries.Keys.HOLDER_SET_TYPES;
        public static final ResourceKey<? extends Registry<?>> INGREDIENT_TYPES =
                NeoForgeRegistries.Keys.INGREDIENT_TYPES;
    }

    private ForgeRegistries() {}
}
