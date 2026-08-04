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
    public static ResourceLocation modelId(ModelResourceLocation mrl) {
        return mrl == null ? null : mrl.id();
    }
}
