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

    private ItemBridge() {}
}
