package net.minecraftforge.common.crafting.conditions;

import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;

/**
 * Shim for {@code IConditionSerializer}, 33 corpus mods.
 *
 * The JSON reader/writer pair every custom {@link ICondition} shipped alongside itself. 1.20.5
 * replaced the whole mechanism with codecs, so NeoForge has no counterpart at any name and this
 * cannot be a rename.
 *
 * Mods implement it, register it, and then never call it themselves -- the caller was Forge's
 * recipe loader. So the shim exists to let those classes load, and nothing invokes them. The
 * condition still works wherever the mod constructs one directly; what does not work is a
 * condition written into a datapack file, which needs the codec {@link ICondition} cannot yet
 * generate.
 */
public interface IConditionSerializer<T extends ICondition> {

    void write(JsonObject json, T value);

    T read(JsonObject json);

    ResourceLocation getID();
}
