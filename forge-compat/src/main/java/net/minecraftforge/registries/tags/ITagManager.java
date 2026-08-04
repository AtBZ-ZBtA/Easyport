package net.minecraftforge.registries.tags;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

/**
 * Shim for {@code ITagManager<V>}, reached through {@code IForgeRegistry.tags()} in 57 jars.
 *
 * Forge gave every registry its own tag manager. NeoForge removed the layer and mods read tags
 * from the vanilla registry, so this is a thin view rather than a delegate.
 *
 * {@code isKnownTagName} answers whether the tag exists at all, which mods use to guard against
 * a datapack that never defined one. Worth answering accurately rather than always-true: the
 * guard exists precisely to avoid a crash further down.
 */
public interface ITagManager<V> extends Iterable<ITag<V>> {

    ITag<V> getTag(TagKey<V> key);

    boolean isKnownTagName(TagKey<V> key);

    TagKey<V> createTagKey(ResourceLocation location);
}
