package net.minecraftforge.common.crafting.conditions;

import com.google.gson.JsonElement;
import com.mojang.serialization.MapCodec;

import net.minecraft.resources.ResourceLocation;

/**
 * Shim for {@code ICondition}, the recipe/loot condition mods implement to gate content on
 * whether another mod is present or a tag is populated.
 *
 * <h2>Why a shim and not a rename</h2>
 *
 * NeoForge kept the name, the package shape and {@code test(IContext)} -- so a rename looks
 * right, resolves, and would break every mod that implements one. 1.20.5 added a second abstract
 * method, {@code codec()}, because conditions are serialised by codec now instead of by a
 * registered serializer. Renaming leaves that unimplemented and each condition class fails with
 * {@code AbstractMethodError} the first time a recipe carrying it is loaded.
 *
 * Extending NeoForge's interface and defaulting {@code codec()} keeps the mod's own
 * {@code test} satisfying NeoForge's abstract method -- {@code IContext} is renamed to NeoForge's,
 * so the descriptors line up exactly -- while supplying the piece the mod could not have written.
 *
 * <h2>The default codec is a placeholder, and it shows</h2>
 *
 * {@code MapCodec.unit} returns the condition instance itself without reading anything, which is
 * correct for evaluating a condition already in memory and wrong for reading one out of a recipe
 * file. A mod's conditions therefore work where the mod constructs them and not where a datapack
 * names them. Generating a real codec means deriving it from the serializer the mod wrote, which
 * is the same unsolved problem as the loot serializers.
 */
public interface ICondition extends net.neoforged.neoforge.common.conditions.ICondition {

    /** Forge's identity for the condition. NeoForge identifies conditions by codec instead. */
    default ResourceLocation getID() {
        return null;
    }

    /** Forge's datagen hook, consulted when writing a recipe out. Unused at runtime. */
    default boolean shouldRegisterEntry(JsonElement json) {
        return true;
    }

    @Override
    default MapCodec<? extends net.neoforged.neoforge.common.conditions.ICondition> codec() {
        return MapCodec.unit(this);
    }
}
