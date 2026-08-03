package easyport.vanilla;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;

/**
 * Relocated stand-in for {@code net.minecraft.world.level.storage.loot.Serializer}, which 1.21
 * deleted when loot serialization moved to codecs.
 *
 * Deliberately **not** in its original package. A mod jar cannot supply classes under
 * {@code net.minecraft.*} — proven directly:
 *
 * <pre>
 * java.lang.module.ResolutionException: Modules vanillapkgprobe and minecraft
 * export package net.minecraft.world.level.storage.loot to module easyport_inspector
 * </pre>
 *
 * So the type lives in a package Easyport owns, and the transformer rewrites references to it
 * with a TYPE_RENAME. That is the general technique for every vanilla type 1.21 removed:
 * relocate, then rename. It needs no structural surgery on the mods that implement them.
 *
 * The {@code easyport.vanilla} package name is intentional — these are relocated *vanilla*
 * types, not Forge API, and mixing them into {@code net.minecraftforge.*} would misrepresent
 * where they came from.
 *
 * <h2>What this does not do</h2>
 *
 * Loading is restored; behaviour is not. Vanilla drives loot serialization through codecs now
 * and will never call these methods, so a mod's custom loot conditions and functions will not
 * work — they simply will not be invoked. The mod's other content registers and runs normally.
 *
 * That makes this a genuinely partial translation, and the per-jar report should say so rather
 * than let a clean load imply a clean port. Fully fixing it means generating a codec from the
 * mod's serialize/deserialize pair, which is Phase 4 vanilla-bridge work.
 */
public interface LootSerializer<T> {

    void serialize(JsonObject json, T value, JsonSerializationContext context);

    T deserialize(JsonObject json, JsonDeserializationContext context);
}
