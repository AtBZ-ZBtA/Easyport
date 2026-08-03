package com.example.examplemod;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * PROBE MOD - NeoForge 1.21.1 side of the zero-drift ground-truth pair.
 *
 * Hand-ported from the Forge 1.20.1 side, kept functionally identical so that every
 * difference between the two built jars is migration and nothing else. See the Forge file for
 * why this pair exists and what it is used for.
 *
 * The migrations applied, one per top-of-work-list entry:
 *
 *   FMLJavaModLoadingContext.get().getModEventBus()  ->  IEventBus injected into constructor
 *   ModLoadingContext.get().registerConfig(...)      ->  ModContainer.registerConfig(...)
 *   DeferredRegister.create(ForgeRegistries.BLOCKS)  ->  DeferredRegister.createBlocks(...)
 *   RegistryObject<Block> / <Item>                   ->  DeferredBlock / DeferredItem
 *   ForgeConfigSpec                                  ->  ModConfigSpec
 *   MinecraftForge.EVENT_BUS                         ->  NeoForge.EVENT_BUS
 *   net.minecraftforge.fml.*                         ->  net.neoforged.fml.*
 *   new ResourceLocation(ns, path)                   ->  ResourceLocation.fromNamespaceAndPath
 *
 * The NBT block is the one that is not a rename. getOrCreateTag/setTag/hasTag are gone
 * outright; mod-private data now lives in the CUSTOM_DATA component. That asymmetry is
 * exactly why the vanilla bridge cannot be generated from a mapping table.
 */
@Mod(ExampleMod.MODID)
public class ExampleMod {

    public static final String MODID = "examplemod";

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredBlock<Block> PROBE_BLOCK =
            BLOCKS.registerBlock("probe_block", Block::new, BlockBehaviour.Properties.of());
    public static final DeferredItem<Item> PROBE_ITEM =
            ITEMS.registerSimpleItem("probe_item", new Item.Properties());

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.BooleanValue ENABLED =
            BUILDER.comment("Probe flag").define("enabled", true);
    public static final ModConfigSpec SPEC = BUILDER.build();

    public ExampleMod(IEventBus modBus, ModContainer container) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(this::commonSetup);

        NeoForge.EVENT_BUS.register(this);
        container.registerConfig(ModConfig.Type.COMMON, SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MODID, "probe");
        boolean minecraftLoaded = ModList.get().isLoaded("minecraft");

        // The data-component replacement for the Forge side's getOrCreateTag/setTag pair.
        // Mod-private NBT survives intact under CUSTOM_DATA, which is why this case is
        // shimmable at all -- vanilla-owned keys are the ones that are not.
        ItemStack stack = new ItemStack(PROBE_ITEM.get());
        CompoundTag tag = new CompoundTag();
        tag.putInt("charge", 5);
        tag.putString("owner", id.toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        boolean hasData = stack.has(DataComponents.CUSTOM_DATA);
        System.out.println("[probe] id=" + id + " mcLoaded=" + minecraftLoaded
                + " hasData=" + hasData + " enabled=" + ENABLED.get()
                + " block=" + PROBE_BLOCK.get());
    }
}
