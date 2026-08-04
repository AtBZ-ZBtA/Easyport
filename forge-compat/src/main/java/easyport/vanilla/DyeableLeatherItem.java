package easyport.vanilla;

import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;

/**
 * Relocated stand-in for {@code net.minecraft.world.item.DyeableLeatherItem}, 16 corpus mods.
 *
 * 1.21 deleted the interface when dye colour became the {@code DYED_COLOR} data component, and a
 * mod jar cannot supply a replacement under {@code net.minecraft} -- module resolution refuses
 * it -- so this lives in a package Easyport owns and a TYPE_RENAME redirects references here.
 *
 * <h2>This one actually works</h2>
 *
 * Most relocated stand-ins restore loading and not behaviour, because the vanilla code that used
 * to call them is gone. This is the exception worth pointing at: dye colour was not removed, it
 * moved, and every one of these methods has an exact expression in terms of the component. A mod
 * calling {@code getColor} gets the real colour of the real stack, and one calling
 * {@code setColor} dyes it in a way vanilla itself will render.
 *
 * The one visible difference is that vanilla no longer routes through this interface, so
 * implementing it no longer makes an item dyeable on its own -- that now comes from the item
 * having the component. Mods that both implement the interface and set the component are
 * unaffected, which is nearly all of them, since the 1.20.1 idiom was to do both.
 */
public interface DyeableLeatherItem {

    /** Vanilla's undyed leather colour, which mods read directly. */
    int DEFAULT_LEATHER_COLOR = 0xA06540;

    static boolean hasCustomColor(ItemStack stack) {
        return stack.has(DataComponents.DYED_COLOR);
    }

    static int getColor(ItemStack stack) {
        DyedItemColor color = stack.get(DataComponents.DYED_COLOR);
        return color != null ? color.rgb() : DEFAULT_LEATHER_COLOR;
    }

    static void setColor(ItemStack stack, int color) {
        // showInTooltip=true reproduces 1.20.1, where a dyed item always said so in its tooltip.
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(color, true));
    }

    static void clearColor(ItemStack stack) {
        stack.remove(DataComponents.DYED_COLOR);
    }

    static ItemStack dyeArmor(ItemStack stack, List<DyeItem> dyes) {
        return DyedItemColor.applyDyes(stack, dyes);
    }

}
