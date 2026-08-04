package easyport.vanilla;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Relocated stand-in for {@code net.minecraft.world.item.BowlFoodItem}, 7 corpus mods.
 *
 * 1.21 deleted it: leaving a bowl behind is the {@code USE_REMAINDER} component's job now, so no
 * class is needed. Reproducing the behaviour outright is the honest thing here and it is three
 * lines -- the whole class ever did was hand back an empty bowl.
 */
public class BowlFoodItem extends Item {

    public BowlFoodItem(Properties properties) {
        super(properties);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        ItemStack result = super.finishUsingItem(stack, level, entity);
        return entity instanceof net.minecraft.world.entity.player.Player player
                && player.hasInfiniteMaterials()
                ? result
                : new ItemStack(net.minecraft.world.item.Items.BOWL);
    }
}
