package net.minecraftforge.registries;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;

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

    public static final IForgeRegistry<Block> BLOCKS = new IForgeRegistry<>(Registries.BLOCK);
    public static final IForgeRegistry<Item> ITEMS = new IForgeRegistry<>(Registries.ITEM);
    public static final IForgeRegistry<Fluid> FLUIDS = new IForgeRegistry<>(Registries.FLUID);
    public static final IForgeRegistry<MobEffect> MOB_EFFECTS = new IForgeRegistry<>(Registries.MOB_EFFECT);
    public static final IForgeRegistry<EntityType<?>> ENTITY_TYPES = new IForgeRegistry<>(Registries.ENTITY_TYPE);
    public static final IForgeRegistry<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            new IForgeRegistry<>(Registries.BLOCK_ENTITY_TYPE);
    public static final IForgeRegistry<MenuType<?>> MENU_TYPES = new IForgeRegistry<>(Registries.MENU);
    public static final IForgeRegistry<Enchantment> ENCHANTMENTS = new IForgeRegistry<>(Registries.ENCHANTMENT);
    public static final IForgeRegistry<net.minecraft.sounds.SoundEvent> SOUND_EVENTS =
            new IForgeRegistry<>(Registries.SOUND_EVENT);
    public static final IForgeRegistry<net.minecraft.world.item.crafting.RecipeType<?>> RECIPE_TYPES =
            new IForgeRegistry<>(Registries.RECIPE_TYPE);
    public static final IForgeRegistry<net.minecraft.world.item.crafting.RecipeSerializer<?>> RECIPE_SERIALIZERS =
            new IForgeRegistry<>(Registries.RECIPE_SERIALIZER);
    public static final IForgeRegistry<net.minecraft.world.entity.ai.attributes.Attribute> ATTRIBUTES =
            new IForgeRegistry<>(Registries.ATTRIBUTE);
    public static final IForgeRegistry<net.minecraft.core.particles.ParticleType<?>> PARTICLE_TYPES =
            new IForgeRegistry<>(Registries.PARTICLE_TYPE);

    private ForgeRegistries() {}
}
