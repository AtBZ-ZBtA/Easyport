package easyport.bridge;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Builds a real 1.21 {@code ArmorMaterial} record from a mod's 1.20.1-shaped implementation.
 *
 * The conversion is total: every component of the record has a getter on the old interface, so
 * nothing is invented and nothing is dropped except the render layers, which 1.20.1 derived from
 * the material's name in exactly the way reproduced here.
 *
 * Cached by identity because armour materials are singletons -- typically enum constants -- and a
 * record rebuilt on every call would break the {@code ==} comparisons vanilla does internally
 * when deciding whether a full set is being worn.
 */
public final class ArmorMaterialBridge {

    private ArmorMaterialBridge() {}

    private static final Map<easyport.vanilla.ArmorMaterial, net.minecraft.world.item.ArmorMaterial>
            CACHE = new ConcurrentHashMap<>();

    public static net.minecraft.world.item.ArmorMaterial toVanilla(
            easyport.vanilla.ArmorMaterial material) {
        if (material == null) return null;
        return CACHE.computeIfAbsent(material, ArmorMaterialBridge::build);
    }

    private static net.minecraft.world.item.ArmorMaterial build(
            easyport.vanilla.ArmorMaterial material) {
        // Every armour slot 1.21 knows about, asked one at a time and defaulted on failure.
        //
        // 1.21 added BODY, for wolf armour. A 1.20.1 material has never heard of it, and the
        // common idiom -- an EnumMap keyed by the slots that existed then -- returns null for it,
        // so asking blows up inside the *mod's* method with a NullPointerException that names
        // EnumMap and nothing else. Defaulting to zero is right on the merits too: a material
        // written before wolf armour existed has no opinion about it.
        Map<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
        for (ArmorItem.Type type : ArmorItem.Type.values()) {
            int value = 0;
            try {
                value = material.getDefenseForType(type);
            } catch (RuntimeException unknownToThisMaterial) {
                // Left at zero.
            }
            defense.put(type, value);
        }

        Holder<net.minecraft.sounds.SoundEvent> equipSound =
                HolderBridge.wrap(material.getEquipSound());

        // 1.20.1 built the armour texture path from the material name, so a mod that shipped
        // textures for its material already has them at this location. Namespacing it "minecraft"
        // would look for them in the wrong place.
        ResourceLocation layer = parseName(material.getName());
        Ingredient repair = material.getRepairIngredient();

        return new net.minecraft.world.item.ArmorMaterial(
                defense,
                material.getEnchantmentValue(),
                equipSound,
                () -> repair,
                List.of(new net.minecraft.world.item.ArmorMaterial.Layer(layer)),
                material.getToughness(),
                material.getKnockbackResistance());
    }

    /**
     * Material names were namespaced strings in 1.20.1 and plain paths for vanilla's own
     * materials, so both forms have to parse. {@code tryParse} returns null on anything else,
     * which falls back rather than throwing during item registration.
     */
    private static ResourceLocation parseName(String name) {
        if (name == null) return ResourceLocation.withDefaultNamespace("empty");
        ResourceLocation parsed = ResourceLocation.tryParse(name);
        return parsed != null ? parsed : ResourceLocation.withDefaultNamespace("empty");
    }
}
