package easyport.bridge;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;

/**
 * Conversions between a 1.20.1 vanilla type and whatever 1.21 replaced it with.
 *
 * Each method here backs a {@code COERCE} rule, and the transformer inserts the call at the exact
 * argument position where the old type meets a signature that now wants the new one.
 */
public final class VanillaBridge {

    private VanillaBridge() {}

    /**
     * The resource location inside a model location, 4 of 22 sampled mods.
     *
     * In 1.20.1 {@code ModelResourceLocation extends ResourceLocation}, so a model location was
     * usable anywhere a resource location was and mods pass them around interchangeably. 1.21
     * made it a record that *holds* one instead, and every such call fails verification with
     * "expected ResourceLocation, but found ModelResourceLocation".
     *
     * The conversion is the record's own accessor, so nothing is invented -- but it is lossy in
     * one direction that matters: the variant is dropped. That is unavoidable and correct here,
     * because the callee only ever wanted the location; a caller that wanted the variant would
     * have taken a model location.
     */
    /**
     * The xp an ore block drops, for the DropExperienceBlock constructor 1.21 removed.
     *
     * 1.20.1 had a one-argument constructor meaning "no xp"; 1.21 kept only the form that takes
     * an IntProvider. Constant zero *is* what the old constructor meant, so this is one of the
     * few fillers that loses nothing.
     */
    public static net.minecraft.util.valueproviders.IntProvider noExperience() {
        return net.minecraft.util.valueproviders.ConstantInt.of(0);
    }

    /**
     * AttributeModifier.Operation constants, renamed in 1.21 with the same meanings.
     *
     * ADDITION -> ADD_VALUE, MULTIPLY_BASE -> ADD_MULTIPLIED_BASE, MULTIPLY_TOTAL ->
     * ADD_MULTIPLIED_TOTAL. A field read cannot be renamed by the type remapper -- it rewrites
     * owners, not member names -- so each goes through FIELD_TO_STATIC to one of these.
     */
    public static net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation addValue() {
        return net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE;
    }

    public static net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation multiplyBase() {
        return net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
    }

    public static net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation multiplyTotal() {
        return net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
    }

    /**
     * AttributeModifier's constructor, which 1.21 rebuilt around a ResourceLocation.
     *
     * (UUID, String, double, Operation) became (ResourceLocation, double, Operation): the id and
     * the display name collapsed into one namespaced identifier. Too big a change for ARG_DROP or
     * a coercion -- two parameters became one -- so the whole construction moves to a factory.
     *
     * The identifier is derived from the UUID rather than the name, because the UUID is what
     * vanilla used to deduplicate modifiers and two modifiers sharing a display name were always
     * allowed. A UUID prints as lowercase hex and dashes, which is a legal resource path.
     */
    public static net.minecraft.world.entity.ai.attributes.AttributeModifier attributeModifier(
            java.util.UUID id, String name, double amount,
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation operation) {
        ResourceLocation key = id != null
                ? ResourceLocation.fromNamespaceAndPath("easyport", id.toString())
                : ResourceLocation.fromNamespaceAndPath("easyport", "modifier");
        return new net.minecraft.world.entity.ai.attributes.AttributeModifier(key, amount, operation);
    }

    /**
     * The location inside a ResourceKey.
     *
     * 1.21 moved several registries from addressing entries by ResourceLocation to addressing
     * them by ResourceKey, and changed the returns to match --
     * {@code BuiltInLootTables.register} is the one the corpus leans on hardest, at 305
     * jar-weight. The key carries the location, so the conversion is exact in this direction.
     */
    public static ResourceLocation keyLocation(net.minecraft.resources.ResourceKey<?> key) {
        return key == null ? null : key.location();
    }

    /**
     * Forge's patched {@code BuiltInLootTables.register}, which NeoForge does not have.
     *
     * Forge made vanilla's private helper public so mods could register loot tables; 1.21 left
     * it private and moved registration to datapacks entirely. Neither a rename nor an argument
     * rule reaches a method that is inaccessible, so the call moves here.
     *
     * Returns the location unchanged, which is what the mod does with it -- the identifier is
     * still valid and the table itself still loads from the mod's data pack. What is lost is the
     * eager registration, and under 1.21 there is nothing to register into.
     */
    public static ResourceLocation registerLootTable(ResourceLocation id) {
        return id;
    }

    public static ResourceLocation modelId(ModelResourceLocation mrl) {
        return mrl == null ? null : mrl.id();
    }
}
