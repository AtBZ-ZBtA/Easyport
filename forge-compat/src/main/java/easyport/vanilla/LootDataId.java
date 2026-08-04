package easyport.vanilla;

import net.minecraft.resources.ResourceLocation;

/**
 * Relocated stand-in for {@code net.minecraft.world.level.storage.loot.LootDataId}.
 *
 * The (type, id) key {@link LootDataManager} was addressed by. A record, as it was, so equality
 * and hashing behave the way any mod holding these in a map expects.
 */
public record LootDataId<T>(Object type, ResourceLocation location) {}
