package net.minecraftforge.common;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shim for {@code PlantType}, 23 corpus mods.
 *
 * Forge's answer to "what can this plant grow on", consulted by {@code IPlantable} and by
 * {@code BlockState#canSustainPlant}. NeoForge dropped the concept entirely in favour of block
 * tags, so there is nothing to rename to and this is a shim rather than a rule.
 *
 * The corpus almost only reads the constants -- CROP in 16 jars, PLAINS in 12 -- and hands them
 * straight back to Forge API that forge-compat also supplies. So an interned value type carrying
 * a name reproduces everything mods actually observe: two references to the same plant type
 * compare equal, {@code get} returns the same instance for the same name, and {@code getName}
 * round-trips.
 *
 * What it does not do is make vanilla honour it. A mod's custom soil check will not be consulted
 * by NeoForge, because NeoForge asks a block tag instead. That is a behaviour gap, not a load
 * failure, and it is the honest state of this type until the Phase 4 bridge maps plant types onto
 * the tags NeoForge replaced them with.
 */
public class PlantType {

    private static final Map<String, PlantType> VALUES = new ConcurrentHashMap<>();

    public static final PlantType PLAINS = get("plains");
    public static final PlantType DESERT = get("desert");
    public static final PlantType BEACH = get("beach");
    public static final PlantType CAVE = get("cave");
    public static final PlantType WATER = get("water");
    public static final PlantType NETHER = get("nether");
    public static final PlantType CROP = get("crop");

    private final String name;

    protected PlantType(String name) {
        this.name = name;
    }

    /** Interning factory, so equality by identity keeps working for mod-declared types too. */
    public static PlantType get(String name) {
        return VALUES.computeIfAbsent(name.toLowerCase(Locale.ROOT), PlantType::new);
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
