package easyport.vanilla;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;

/**
 * Relocated stand-in for {@code LootPoolSingletonContainer$Serializer}.
 *
 * The loot *entry* counterpart to {@link LootItemConditionalFunctionSerializer}, with the same
 * cause and the same limits.
 *
 * This is the type whose absence produced the most instructive failure in this project: placebo's
 * loot entry lived behind a mixin that got stripped, the registration ran from a static
 * initialiser nothing else touched, and the mod loaded cleanly while quietly registering nothing.
 * A clean load is not a clean port.
 */
public abstract class LootPoolSingletonContainerSerializer<T> {

    public void serializeCustom(JsonObject json, T value, JsonSerializationContext context) {}

    public abstract T deserialize(JsonObject json, JsonDeserializationContext context,
                                  int weight, int quality, Object[] conditions, Object[] functions);
}
