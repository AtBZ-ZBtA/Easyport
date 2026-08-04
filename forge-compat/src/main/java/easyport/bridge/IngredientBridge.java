package easyport.bridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.AbstractIngredient;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;

/**
 * Turns a Forge-shaped custom ingredient into a 1.21 {@code Ingredient}.
 *
 * {@code Ingredient} is final in 1.21, so a mod's custom ingredient cannot be one. NeoForge's
 * {@code ICustomIngredient} is the supported way to have custom matching logic, and it converts
 * into an {@code Ingredient} rather than being one -- which is exactly the shape a COERCE rule
 * inserts at a boundary.
 *
 * Cached by identity, because ingredients are compared by reference inside recipe matching and a
 * fresh wrapper per call would make an ingredient stop equalling itself.
 */
public final class IngredientBridge {

    private IngredientBridge() {}

    private static final Map<AbstractIngredient, Ingredient> CACHE = new ConcurrentHashMap<>();

    public static Ingredient toVanilla(AbstractIngredient ingredient) {
        if (ingredient == null) return Ingredient.EMPTY;
        return CACHE.computeIfAbsent(ingredient, i -> new Adapter(i).toVanilla());
    }

    /**
     * The adapter. Matching is forwarded, so a custom ingredient built in code behaves exactly as
     * it did.
     *
     * {@code getType} is the one method with no honest answer. NeoForge uses it to serialise the
     * ingredient back out, and it must return a type registered under the owning mod's id --
     * which cannot be reconstructed from a Forge serializer, because Forge identified ingredients
     * by a serializer registry that no longer exists. Returning null keeps matching working and
     * makes serialization fail loudly rather than writing something that would not read back.
     */
    private record Adapter(AbstractIngredient delegate) implements ICustomIngredient {

        @Override
        public boolean test(ItemStack stack) {
            return delegate.test(stack);
        }

        @Override
        public Stream<ItemStack> getItems() {
            return Stream.of(delegate.getItems());
        }

        @Override
        public boolean isSimple() {
            return delegate.isSimple();
        }

        @Override
        public IngredientType<?> getType() {
            return null;
        }
    }
}
