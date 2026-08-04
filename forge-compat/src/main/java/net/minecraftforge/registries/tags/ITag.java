package net.minecraftforge.registries.tags;

import java.util.Iterator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import net.minecraft.tags.TagKey;

/**
 * Shim for {@code ITag<V>}, 53 corpus jars.
 *
 * Forge wrapped vanilla tags in its own iterable view so a registry could hand back tag contents
 * directly. NeoForge dropped the wrapper -- tags are read from the vanilla registry -- so there
 * is nothing to delegate to and this is a view over what the registry returns.
 */
public interface ITag<V> extends Iterable<V> {

    TagKey<V> getKey();

    boolean isEmpty();

    int size();

    boolean contains(V value);

    @Override
    Iterator<V> iterator();

    /** 29 corpus jars. Defaulted over the iterator so implementations need not repeat it. */
    default Stream<V> stream() {
        return StreamSupport.stream(spliterator(), false);
    }
}
