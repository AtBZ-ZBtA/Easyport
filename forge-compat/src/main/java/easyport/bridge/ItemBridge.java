package easyport.bridge;

import net.minecraft.world.item.ItemStack;

/**
 * Static replacements for ItemHandlerHelper methods NeoForge dropped.
 *
 * {@code ItemHandlerHelper} itself is renamed to NeoForge's, which kept most of the class but
 * not these two. Both had exact vanilla equivalents by 1.21, so this is a redirect rather than
 * a reimplementation -- the bodies are one line each and exist only to give the rule a target
 * with a matching static descriptor.
 */
public final class ItemBridge {

    /**
     * Forge's stack-compatibility check, called by 47 corpus jars.
     *
     * 1.20.5 replaced NBT comparison with component comparison and vanilla absorbed the helper.
     * Semantics match: both ask "same item, same extra data, ignoring count".
     */
    public static boolean canItemStacksStack(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameComponents(a, b);
    }

    /** Forge's resize-a-copy helper, 39 jars. Vanilla's copyWithCount is the same operation. */
    public static ItemStack copyStackWithSize(ItemStack stack, int size) {
        return size <= 0 ? ItemStack.EMPTY : stack.copyWithCount(size);
    }

    /**
     * The 1.20.1 NBT surface on {@code ItemStack}, over the {@code CUSTOM_DATA} component.
     *
     * The single heaviest cluster in the vanilla drift report: 1,207 jar-weight, and the four
     * methods below are the ones the corpus leans on. 1.20.5 replaced stack NBT with typed data
     * components, and left one component -- {@code CUSTOM_DATA} -- carrying an arbitrary
     * {@code CompoundTag}, which is exactly what these used to return.
     *
     * <h2>Why this can be faithful, and where the seam is</h2>
     *
     * The 1.20.1 idiom is to mutate in place: {@code stack.getOrCreateTag().putInt("charge", 5)}.
     * Reproducing that needs a *live* reference to the component's tag, not a copy --
     * {@code CustomData.of} copies, so building one and handing it back would silently drop every
     * such write. {@code getUnsafe()} returns the real internal tag, and mutating it behaves as
     * 1.20.1 did. The name is a warning about component immutability, and taking it is the
     * deliberate choice: mods depend on the old behaviour, and preserving Forge's semantics over
     * the platform's purity is the rule this project has followed throughout.
     *
     * {@link #setTag} is the one that cannot be exact. Forge stored the caller's tag by
     * reference, so later mutations of it also stuck; the component copies on the way in, so they
     * do not. Mods overwhelmingly build a tag and set it once, which this reproduces exactly.
     */
    public static net.minecraft.nbt.CompoundTag getTag(ItemStack stack) {
        var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return data == null ? null : data.getUnsafe();
    }

    public static net.minecraft.nbt.CompoundTag getOrCreateTag(ItemStack stack) {
        var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) {
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(
                            new net.minecraft.nbt.CompoundTag()));
            data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        }
        return data.getUnsafe();
    }

    public static void setTag(ItemStack stack, net.minecraft.nbt.CompoundTag tag) {
        if (tag == null) {
            stack.remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        } else {
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(tag));
        }
    }

    /** 1.20.1 returned true whenever a tag was present, empty or not. */
    public static boolean hasTag(ItemStack stack) {
        return stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
    }

    /**
     * Forge's {@code EntityType.Builder.setCustomClientFactory}, which NeoForge removed.
     *
     * Forge needed it because its own spawn packet had to reconstruct the entity client-side.
     * 1.20.5 folded extra spawn data into vanilla's packet, so the factory has nothing left to
     * do and NeoForge dropped the method -- see the PlayMessages shim, which is the same change
     * seen from the packet end.
     *
     * Returns the builder so the call chain continues. The factory is discarded, which is correct
     * rather than lossy: the entity is now constructed by its registered type and populated
     * through IEntityWithComplexSpawn, both of which forge-compat already routes.
     */
    public static net.minecraft.world.entity.EntityType.Builder<?> setCustomClientFactory(
            net.minecraft.world.entity.EntityType.Builder<?> builder,
            java.util.function.BiFunction<?, ?, ?> factory) {
        return builder;
    }

    private ItemBridge() {}
}
