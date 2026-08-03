package net.minecraftforge.common;

import java.util.List;
import java.util.function.Supplier;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Shim shape 3 of 3: instance delegation with self-return.
 *
 * The important and awkward shape. Builder methods return the builder for chaining, so the
 * shim cannot hand back the delegate's return value — that would leak a NeoForge type into
 * code expecting a Forge one and break the very next call in the chain. Every chaining method
 * must discard the delegate's return and hand back {@code this}.
 *
 * This pattern recurs across most of forge-compat, which is why it is proven here first.
 *
 * {@code ForgeConfigSpec$Builder} is used by 137 of the 288 corpus mods, ninth on the shim
 * work list. Coverage below is deliberately limited to the methods the corpus shows mods
 * actually calling; the rest arrives in Phase 3.
 */
public class ForgeConfigSpec implements net.minecraftforge.fml.config.IConfigSpec {

    private final ModConfigSpec delegate;

    private ForgeConfigSpec(ModConfigSpec delegate) {
        this.delegate = delegate;
    }

    /** Unwraps to the NeoForge spec, for shim code that has to hand the real object onward. */
    public ModConfigSpec unwrap() {
        return delegate;
    }

    // ---- Builder -----------------------------------------------------------------------

    public static class Builder {

        private final ModConfigSpec.Builder delegate = new ModConfigSpec.Builder();

        public Builder comment(String comment) {
            delegate.comment(comment);
            return this;
        }

        public Builder comment(String... comment) {
            delegate.comment(comment);
            return this;
        }

        public Builder translation(String translationKey) {
            delegate.translation(translationKey);
            return this;
        }

        public Builder push(String path) {
            delegate.push(path);
            return this;
        }

        public Builder pop() {
            delegate.pop();
            return this;
        }

        public Builder pop(int count) {
            delegate.pop(count);
            return this;
        }

        public <T> ConfigValue<T> define(String path, T defaultValue) {
            return new ConfigValue<>(delegate.define(path, defaultValue));
        }

        public <T> ConfigValue<T> define(List<String> path, T defaultValue) {
            return new ConfigValue<>(delegate.define(path, defaultValue));
        }

        public BooleanValue define(String path, boolean defaultValue) {
            return new BooleanValue(delegate.define(path, defaultValue));
        }

        public <V extends Comparable<? super V>> ConfigValue<V> defineInRange(
                String path, V defaultValue, V min, V max, Class<V> clazz) {
            return new ConfigValue<>(delegate.defineInRange(path, defaultValue, min, max, clazz));
        }

        public <T> ConfigValue<T> define(String path, java.util.function.Supplier<T> defaultSupplier,
                                         java.util.function.Predicate<Object> validator) {
            return new ConfigValue<>(delegate.define(path, defaultSupplier, validator));
        }

        public <T> ConfigValue<T> defineInList(String path, T defaultValue,
                                               java.util.Collection<? extends T> acceptableValues) {
            return new ConfigValue<>(delegate.defineInList(path, defaultValue, acceptableValues));
        }

        public <T> ConfigValue<java.util.List<? extends T>> defineList(
                String path, java.util.List<? extends T> defaultValue,
                java.util.function.Predicate<Object> elementValidator) {
            return new ConfigValue<>(delegate.defineList(path, defaultValue, elementValidator));
        }

        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue) {
            return new EnumValue<>(delegate.defineEnum(path, defaultValue));
        }

        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue,
                                                           java.util.Collection<V> acceptableValues) {
            return new EnumValue<>(delegate.defineEnum(path, defaultValue, acceptableValues));
        }

        // Each numeric width is a distinct overload in Forge returning a distinct value type,
        // and mods hold the result at that subtype -- so these cannot be collapsed into one
        // generic method without breaking the descriptors mods were compiled against.
        public IntValue defineInRange(String path, int defaultValue, int min, int max) {
            return new IntValue(delegate.defineInRange(path, defaultValue, min, max));
        }

        public LongValue defineInRange(String path, long defaultValue, long min, long max) {
            return new LongValue(delegate.defineInRange(path, defaultValue, min, max));
        }

        public DoubleValue defineInRange(String path, double defaultValue, double min, double max) {
            return new DoubleValue(delegate.defineInRange(path, defaultValue, min, max));
        }

        public Builder worldRestart() {
            delegate.worldRestart();
            return this;
        }

        /**
         * Returns the spec paired with the config object, matching Forge.
         *
         * The delegate's Pair carries a NeoForge spec, so it is rebuilt around the shim type —
         * handing back the raw pair would leak a NeoForge spec into a field typed as
         * ForgeConfigSpec and fail on the very next use.
         */
        public <T> org.apache.commons.lang3.tuple.Pair<T, ForgeConfigSpec> configure(
                java.util.function.Function<Builder, T> consumer) {
            T result = consumer.apply(this);
            return org.apache.commons.lang3.tuple.Pair.of(result, new ForgeConfigSpec(delegate.build()));
        }

        public ForgeConfigSpec build() {
            return new ForgeConfigSpec(delegate.build());
        }
    }

    // ---- Values ------------------------------------------------------------------------

    /**
     * Wraps a NeoForge config value.
     *
     * Forge exposes IntValue and BooleanValue as subclasses of ConfigValue, and mods call
     * {@code get()} on all three (75-79 corpus mods each), so the hierarchy has to be
     * reproduced rather than collapsed — mods hold references at the subclass type.
     */
    public static class ConfigValue<T> implements Supplier<T> {

        protected final ModConfigSpec.ConfigValue<T> delegate;

        ConfigValue(ModConfigSpec.ConfigValue<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public T get() {
            return delegate.get();
        }

        public void set(T value) {
            delegate.set(value);
        }

        public List<String> getPath() {
            return delegate.getPath();
        }

        public ModConfigSpec.ConfigValue<T> unwrap() {
            return delegate;
        }
    }

    public static class IntValue extends ConfigValue<Integer> {
        IntValue(ModConfigSpec.ConfigValue<Integer> delegate) {
            super(delegate);
        }
    }

    public static class BooleanValue extends ConfigValue<Boolean> {
        BooleanValue(ModConfigSpec.ConfigValue<Boolean> delegate) {
            super(delegate);
        }
    }

    public static class LongValue extends ConfigValue<Long> {
        LongValue(ModConfigSpec.ConfigValue<Long> delegate) {
            super(delegate);
        }
    }

    public static class DoubleValue extends ConfigValue<Double> {
        DoubleValue(ModConfigSpec.ConfigValue<Double> delegate) {
            super(delegate);
        }
    }

    public static class EnumValue<T extends Enum<T>> extends ConfigValue<T> {
        EnumValue(ModConfigSpec.ConfigValue<T> delegate) {
            super(delegate);
        }
    }

    // ---- IConfigSpec, delegated ---------------------------------------------------------
    //
    // Forge declares registerConfig against IConfigSpec rather than the concrete spec type, so
    // a mod's bytecode names that interface in the call descriptor even when the mod itself
    // only ever touches ForgeConfigSpec. This type therefore has to satisfy it. Since it wraps
    // ModConfigSpec rather than extending it, each method forwards.

    @Override public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override public void validateSpec(net.neoforged.fml.config.ModConfig config) {
        delegate.validateSpec(config);
    }

    @Override public boolean isCorrect(com.electronwill.nightconfig.core.UnmodifiableCommentedConfig config) {
        return delegate.isCorrect(config);
    }

    @Override public void correct(com.electronwill.nightconfig.core.CommentedConfig config) {
        delegate.correct(config);
    }

    @Override public void acceptConfig(net.neoforged.fml.config.IConfigSpec.ILoadedConfig config) {
        delegate.acceptConfig(config);
    }

    /**
     * Forge's raw config setter, adapted to NeoForge's wrapped form.
     *
     * Forge handed the spec a {@code CommentedConfig} directly; NeoForge expects an
     * {@code ILoadedConfig}, which is that config plus a {@code save()} callback. Mods that
     * drive their own config loading call this — appleskin does — rather than leaving it to
     * FML.
     *
     * {@code save()} is a no-op here. Forge's setConfig carried no save capability at all, so
     * there is nothing to forward to; inventing one would write files the mod never asked to
     * write. A config attached this way is read-only in practice, which matches how it behaved
     * on Forge.
     */
    public void setConfig(com.electronwill.nightconfig.core.CommentedConfig config) {
        // ILoadedConfig is sealed, permitting only net.neoforged.fml.config.LoadedConfig, so
        // the wrapper this obviously wants cannot be written -- not by an anonymous class and
        // not by a named one either.
        //
        // correct() is the closest reachable behaviour: it validates the supplied config
        // against the spec and fills in defaults, which is the useful half of what Forge's
        // setConfig did. What is lost is the spec retaining a reference to the config, so
        // later reads through the spec will not see it.
        //
        // Mods calling this drive their own config loading and generally read values back from
        // the CommentedConfig they already hold, so this is usually sufficient. When it is not,
        // the failure is quiet -- values read as defaults rather than as configured, which is
        // worth knowing before blaming the translation.
        delegate.correct(config);
    }
}
