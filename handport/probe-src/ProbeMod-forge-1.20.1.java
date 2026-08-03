package com.example.examplemod;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * PROBE MOD - Forge 1.20.1 side of the zero-drift ground-truth pair.
 *
 * Every one of the 288 corpus pairs mixes genuine migration with whatever features its author
 * added along the way, so no single pair can be trusted alone. This pair has no drift at all:
 * both sides are written to be functionally identical, so every difference between the built
 * jars is migration and nothing else. It is the validation set the corpus-mined rules are
 * checked against.
 *
 * The contents are chosen deliberately, not for realism. Each call sits near the top of a
 * mined work list, with the number of corpus mods depending on it:
 *
 *   FMLJavaModLoadingContext   241     ForgeConfigSpec$Builder    137
 *   IEventBus#addListener      214     ModList#isLoaded           124
 *   MinecraftForge.EVENT_BUS   199     ResourceLocation#<init>    237 + 188
 *   ModLoadingContext          163     ItemStack#getOrCreateTag   105
 *   RegistryObject#get         148     ItemStack#setTag            97
 *   DeferredRegister#register  139
 *
 * Built with mapping_channel=official, so ForgeGradle reobfuscates to SRG on the way out. The
 * jar carries SRG members exactly like a real ATM9 mod, making this an end-to-end test of the
 * mapping pipeline as well as a rule source.
 */
@Mod(ExampleMod.MODID)
public class ExampleMod {

    public static final String MODID = "examplemod";

    private static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    public static final RegistryObject<Block> PROBE_BLOCK =
            BLOCKS.register("probe_block", () -> new Block(BlockBehaviour.Properties.of()));
    public static final RegistryObject<Item> PROBE_ITEM =
            ITEMS.register("probe_item", () -> new Item(new Item.Properties()));

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.BooleanValue ENABLED =
            BUILDER.comment("Probe flag").define("enabled", true);
    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public ExampleMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        ResourceLocation id = new ResourceLocation(MODID, "probe");
        boolean minecraftLoaded = ModList.get().isLoaded("minecraft");

        // Item NBT. This is the surface the data-component rewrite removed, and the reason the
        // ported side looks nothing like this one.
        ItemStack stack = new ItemStack(PROBE_ITEM.get());
        CompoundTag tag = stack.getOrCreateTag();
        tag.putInt("charge", 5);
        tag.putString("owner", id.toString());
        stack.setTag(tag);

        boolean hasData = stack.hasTag();
        System.out.println("[probe] id=" + id + " mcLoaded=" + minecraftLoaded
                + " hasData=" + hasData + " enabled=" + ENABLED.get()
                + " block=" + PROBE_BLOCK.get());
    }
}
