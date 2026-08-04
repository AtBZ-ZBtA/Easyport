package easyport.vanilla;

import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;

/**
 * Relocated stand-in for {@code net.minecraft.data.recipes.FinishedRecipe}, 17 corpus mods.
 *
 * The JSON-emitting half of 1.20.1's recipe datagen, deleted in 1.20.5 when recipes moved to
 * codecs and {@code RecipeOutput} took over.
 *
 * Datagen only, which is why this is cheap to stand in for: the classes implementing it run at
 * build time in the mod author's workspace, never in a game. What matters is that they *load*,
 * because they are frequently nested inside classes that also register real content, and one
 * unresolvable datagen type takes the lot.
 */
public interface FinishedRecipe {

    void serializeRecipeData(JsonObject json);

    ResourceLocation getId();

    RecipeSerializer<?> getType();

    default JsonObject serializeRecipe() {
        JsonObject json = new JsonObject();
        serializeRecipeData(json);
        return json;
    }

    default JsonObject serializeAdvancement() {
        return null;
    }

    default ResourceLocation getAdvancementId() {
        return null;
    }
}
