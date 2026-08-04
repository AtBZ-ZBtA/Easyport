package net.minecraftforge.common.crafting;

import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Shim for {@code AbstractIngredient}, 14 corpus mods.
 *
 * <h2>Why this one could not be a shim in the usual sense</h2>
 *
 * Forge's {@code AbstractIngredient extends Ingredient}, and 1.21 made {@code Ingredient} final.
 * There is no version of this class that is an {@code Ingredient}, so a mod's custom ingredient
 * cannot be one either -- no rename, no relocation and no shim hierarchy changes that.
 *
 * NeoForge's answer is {@code ICustomIngredient}, which is deliberately *not* an
 * {@code Ingredient}: an implementation is converted into one by {@code toVanilla()}. That is the
 * same shape as the {@code ArmorMaterial} substitution -- implement something else, convert at
 * the boundary -- so it is handled the same way, with a COERCE rule inserting the conversion
 * wherever a mod passes one of these to something expecting an {@code Ingredient}.
 *
 * <h2>What survives</h2>
 *
 * Matching does, which is the part that runs during play: {@code IngredientBridge} adapts this
 * class's {@code test} and {@code getItems} onto NeoForge's interface, so a recipe using a custom
 * ingredient built in code accepts exactly the items it always did.
 *
 * Serialization does not. A custom ingredient named in a recipe *file* needs an
 * {@code IngredientType} registered under the mod's own id, which cannot be reconstructed from a
 * serializer written against Forge's JSON API. That is reported rather than approximated.
 */
public abstract class AbstractIngredient {

    protected AbstractIngredient() {}

    protected AbstractIngredient(Stream<? extends Ingredient.Value> values) {}

    /** The stacks this ingredient accepts. Mods override it; the default is the empty set. */
    public ItemStack[] getItems() {
        return new ItemStack[0];
    }

    /**
     * Whether a stack matches. The default compares against {@link #getItems()} by item and
     * components, which is what a plain item-list ingredient did; mods with real logic override.
     */
    public boolean test(ItemStack stack) {
        if (stack == null) return false;
        for (ItemStack candidate : getItems()) {
            if (ItemStack.isSameItemSameComponents(candidate, stack)) return true;
        }
        return false;
    }

    /**
     * Forge's "can this be matched by item id alone" hint, used to decide whether a recipe can
     * take the fast lookup path. Custom ingredients answered false, and false is also the safe
     * answer under NeoForge: it costs a slower match, never a wrong one.
     */
    public boolean isSimple() {
        return false;
    }

    public boolean isVanilla() {
        return false;
    }

    public void invalidate() {}

    public IIngredientSerializer<? extends Ingredient> getSerializer() {
        return null;
    }

    public JsonElement toJson() {
        return new JsonObject();
    }
}
