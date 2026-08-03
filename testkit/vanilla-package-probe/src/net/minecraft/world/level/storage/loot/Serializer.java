package net.minecraft.world.level.storage.loot;

/**
 * PROBE — answers whether a mod jar may ship classes in a vanilla package.
 *
 * This is the deciding question for the hardest translation category found so far: vanilla
 * types that 1.21 removed outright. {@code Serializer} is a real example — aquaculture
 * implements it, 1.21 deleted it when loot serialization moved to codecs, and there is no
 * replacement to rename to.
 *
 * If a jar can supply the missing class in its original package, the entire category collapses
 * into ordinary shim work. If it cannot, every mod implementing a removed vanilla type needs
 * structural surgery — stripping the interface from the class and deleting its overrides —
 * which is far harder and far riskier.
 *
 * Expectation is that this FAILS. The game jar declares no module-info and no
 * Automatic-Module-Name, so it becomes an automatic module owning {@code net.minecraft.*}, and
 * a second jar contributing to that package should trip a split-package error when the module
 * layer is built. But that is a chain of inference about someone else's classloader, and the
 * answer decides a large amount of downstream work, so it gets tested rather than assumed.
 *
 * Deliberately trivial: any failure observed is about *where the class lives*, not what it does.
 */
public interface Serializer<T> {
    // Forge-era shape, reduced to nothing. The probe tests placement, not behaviour.
}
