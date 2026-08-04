package easyport.vanilla;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * The 1.20.1 shape of {@code net.minecraft.world.item.ArmorMaterial}, as an interface.
 *
 * 1.21 turned that type into a record. A mod that defines its own armour material implemented the
 * interface -- the standard idiom, and 53 corpus jars reference the type -- and an interface that
 * became a record cannot be implemented at all. The class fails to load with
 * {@code IncompatibleClassChangeError}, taking every item registered beside it.
 *
 * <h2>The substitution, and why it is not a rename</h2>
 *
 * A {@code TYPE_RENAME} would rewrite every reference in the mod, including reads of vanilla's
 * own {@code ArmorMaterials} constants -- which are real records, not implementations of this.
 * The mod would then hold a record in a variable typed as this interface and fail verification
 * somewhere else. That is exactly the trap the earlier {@code Holder} work fell into.
 *
 * So this is substituted only in the {@code implements} clause of classes that actually implement
 * it, and {@code ArmorMaterialBridge} converts an instance into a real record at each point where
 * one is passed to vanilla. Every other reference to {@code ArmorMaterial} in the mod keeps
 * meaning vanilla's.
 */
public interface ArmorMaterial {

    int getDurabilityForType(ArmorItem.Type type);

    int getDefenseForType(ArmorItem.Type type);

    int getEnchantmentValue();

    SoundEvent getEquipSound();

    Ingredient getRepairIngredient();

    String getName();

    float getToughness();

    float getKnockbackResistance();
}
