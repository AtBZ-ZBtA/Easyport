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
        ResourceLocation key = identifierFor(id);
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

    /**
     * The tool-item constructors, which 1.21 moved attack stats into the item properties.
     *
     * {@code PickaxeItem(Tier, int, float, Properties)} became {@code PickaxeItem(Tier,
     * Properties)}: the attack damage and speed are an {@code ItemAttributeModifiers} component
     * on the properties now, built by the same {@code createAttributes} vanilla uses itself. Four
     * parameters becoming two is past ARG_DROP, which handles one at a time, so each of these is
     * a factory.
     *
     * Nothing is lost. The values the mod passed are exactly the values createAttributes takes,
     * and vanilla builds its own tools this way -- this is a transcription of the migration, not
     * an approximation of it.
     */
    public static net.minecraft.world.item.PickaxeItem pickaxe(
            net.minecraft.world.item.Tier tier, int damage, float speed,
            net.minecraft.world.item.Item.Properties props) {
        return new net.minecraft.world.item.PickaxeItem(tier, toolProps(tier, damage, speed, props));
    }

    public static net.minecraft.world.item.AxeItem axe(
            net.minecraft.world.item.Tier tier, float damage, float speed,
            net.minecraft.world.item.Item.Properties props) {
        return new net.minecraft.world.item.AxeItem(tier, toolProps(tier, damage, speed, props));
    }

    public static net.minecraft.world.item.ShovelItem shovel(
            net.minecraft.world.item.Tier tier, float damage, float speed,
            net.minecraft.world.item.Item.Properties props) {
        return new net.minecraft.world.item.ShovelItem(tier, toolProps(tier, damage, speed, props));
    }

    public static net.minecraft.world.item.HoeItem hoe(
            net.minecraft.world.item.Tier tier, int damage, float speed,
            net.minecraft.world.item.Item.Properties props) {
        return new net.minecraft.world.item.HoeItem(tier, toolProps(tier, damage, speed, props));
    }

    public static net.minecraft.world.item.SwordItem sword(
            net.minecraft.world.item.Tier tier, int damage, float speed,
            net.minecraft.world.item.Item.Properties props) {
        return new net.minecraft.world.item.SwordItem(tier,
                props.attributes(net.minecraft.world.item.SwordItem.createAttributes(
                        tier, damage, speed)));
    }

    /**
     * The collapsed-argument forms, for ARG_COLLAPSE. Same conversion as the factories above,
     * returning the properties rather than the item, because a super(...) call still has to
     * reach the real constructor.
     */
    public static net.minecraft.world.item.Item.Properties pickaxeProps(
            net.minecraft.world.item.Tier tier, int damage, float speed,
            net.minecraft.world.item.Item.Properties props) {
        return toolProps(tier, damage, speed, props);
    }

    public static net.minecraft.world.item.Item.Properties diggerProps(
            net.minecraft.world.item.Tier tier, float damage, float speed,
            net.minecraft.world.item.Item.Properties props) {
        return toolProps(tier, damage, speed, props);
    }

    public static net.minecraft.world.item.Item.Properties swordProps(
            net.minecraft.world.item.Tier tier, int damage, float speed,
            net.minecraft.world.item.Item.Properties props) {
        return props.attributes(net.minecraft.world.item.SwordItem.createAttributes(
                tier, damage, speed));
    }

    private static net.minecraft.world.item.Item.Properties toolProps(
            net.minecraft.world.item.Tier tier, float damage, float speed,
            net.minecraft.world.item.Item.Properties props) {
        return props.attributes(net.minecraft.world.item.DiggerItem.createAttributes(
                tier, damage, speed));
    }

    /**
     * Forge's {@code CraftingHelper.register}, for condition and ingredient serializers.
     *
     * NeoForge kept the class name and dropped these methods when 1.20.5 replaced serializers
     * with codecs -- so the type renames cleanly and the call does not, which is exactly the
     * shape RenameGaps' member check exists to catch.
     *
     * A no-op returning its argument. The serializer it registers has nothing to read it back,
     * for the same reason IConditionSerializer is a link-only shim: a condition the mod builds in
     * code still works, and one written into a recipe file does not.
     */
    public static Object registerSerializer(Object serializer) {
        return serializer;
    }

    /**
     * Vanilla's two well-known attribute-modifier UUIDs, which 1.21 replaced with named ids.
     *
     * Mapping these exactly matters: an item that *replaces* the base attack damage modifier has
     * to use the same identifier vanilla does, or the two stack and the weapon does double
     * damage. A UUID-derived identifier would be unique, stable, and silently wrong in exactly
     * that way -- which is the failure mode this project cares most about, since nothing crashes.
     */
    private static final java.util.UUID BASE_ATTACK_DAMAGE_UUID =
            java.util.UUID.fromString("CB3F55D3-645C-4F38-A497-9C13A33DB5CF");
    private static final java.util.UUID BASE_ATTACK_SPEED_UUID =
            java.util.UUID.fromString("FA233E1C-4180-4865-B01B-BCCE9785ACA3");

    public static java.util.UUID baseAttackDamageUuid() {
        return BASE_ATTACK_DAMAGE_UUID;
    }

    public static java.util.UUID baseAttackSpeedUuid() {
        return BASE_ATTACK_SPEED_UUID;
    }

    private static ResourceLocation identifierFor(java.util.UUID id) {
        if (BASE_ATTACK_DAMAGE_UUID.equals(id)) return net.minecraft.world.item.Item.BASE_ATTACK_DAMAGE_ID;
        if (BASE_ATTACK_SPEED_UUID.equals(id)) return net.minecraft.world.item.Item.BASE_ATTACK_SPEED_ID;
        return id != null
                ? ResourceLocation.fromNamespaceAndPath("easyport", id.toString())
                : ResourceLocation.fromNamespaceAndPath("easyport", "modifier");
    }

    public static ResourceLocation modelId(ModelResourceLocation mrl) {
        return mrl == null ? null : mrl.id();
    }
}
