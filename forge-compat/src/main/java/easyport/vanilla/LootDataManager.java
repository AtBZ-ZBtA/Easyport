package easyport.vanilla;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Relocated stand-in for {@code net.minecraft.world.level.storage.loot.LootDataManager},
 * 37 corpus mods on {@code getLootTable} alone.
 *
 * 1.21 restructured loot loading into {@code ReloadableServerRegistries}, where loot tables are
 * ordinary registry entries reached through a {@code HolderLookup} rather than by id from a
 * manager.
 *
 * <h2>Why this returns empty rather than bridging</h2>
 *
 * The replacement needs a registry access, and every method here is one a mod calls with nothing
 * but an id -- there is no receiver to recover one from, because the receiver *was* the manager.
 * A static bridge holding a cached registry access would work and is the right shape, but it
 * belongs with the other registry-access work rather than being invented once here.
 *
 * Until then this is honest and inert: {@code LootTable.EMPTY} rather than null, so a caller that
 * immediately rolls the table gets no drops instead of a NullPointerException in the middle of a
 * block break.
 */
public class LootDataManager {

    public LootDataManager() {}

    public LootTable getLootTable(ResourceLocation id) {
        return LootTable.EMPTY;
    }

    public <T> Optional<T> getElementOptional(Object type, ResourceLocation id) {
        return Optional.empty();
    }

    public <T> T getElement(Object type, ResourceLocation id) {
        return null;
    }

    public Collection<ResourceLocation> getKeys(Object type) {
        return List.of();
    }
}
