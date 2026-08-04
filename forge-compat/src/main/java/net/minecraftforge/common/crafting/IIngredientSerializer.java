package net.minecraftforge.common.crafting;

import com.google.gson.JsonObject;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Shim for {@code IIngredientSerializer}, 30 corpus mods.
 *
 * The reader/writer pair a custom ingredient shipped alongside itself. 1.20.5 replaced it with
 * {@code IngredientType}, which pairs a codec with a stream codec, so there is no equivalent to
 * rename to and the shapes do not line up.
 *
 * Mods implement and register these; the caller was Forge's recipe loader. The shim exists so
 * those classes load. A custom ingredient the mod constructs in code still matches items -- see
 * {@link AbstractIngredient} -- while one written into a recipe file does not, because nothing
 * reads it back.
 */
public interface IIngredientSerializer<T extends Ingredient> {

    T parse(FriendlyByteBuf buffer);

    T parse(JsonObject json);

    void write(FriendlyByteBuf buffer, T ingredient);
}
