package net.neoforged.fml.config;

/**
 * Shim: the marker NeoForge's {@code registerConfig} takes.
 *
 * Shimmed rather than renamed, even though Forge 1.20.1 has an interface at the same path, and
 * the reason is the rule the 118-warning experiment established: a matching name is not a reason
 * to rename. Forge's {@code IConfigSpec} is a generic self-referential interface with five
 * abstract methods about correction and validation; NeoForge's is a much narrower thing. Renaming
 * would make every {@code ModConfigSpec} claim to implement five methods it does not have.
 *
 * So this declares only what the shim layer needs to pass a spec along, and
 * {@code ModContainer.registerConfig} unwraps it to the real Forge spec at the boundary.
 */
public interface IConfigSpec {

    /** The Forge spec this ultimately stands for, for the shim that has to hand it over. */
    net.minecraftforge.common.ForgeConfigSpec forgeSpec();
}
