package easyport.vanilla;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;

/**
 * Relocated stand-in for {@code LootItemConditionalFunction$Serializer}.
 *
 * The base serializer every conditional loot function extended in 1.20.1, deleted when loot moved
 * to codecs. Same situation as {@link LootSerializer}, and the same limits: the mod's function
 * class loads, and vanilla will never ask it to serialize because it does not go through
 * serializers any more.
 *
 * Worth reading {@link LootSerializer} before assuming a clean load means a working loot function
 * -- dropping one of these silently cost placebo its only registration once already.
 */
public abstract class LootItemConditionalFunctionSerializer<T> {

    public void serialize(JsonObject json, T value, JsonSerializationContext context) {}

    public abstract T deserialize(JsonObject json, JsonDeserializationContext context,
                                  Object[] conditions);
}
