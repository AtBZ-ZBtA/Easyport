package net.neoforged.neoforge.common;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Shim: NeoForge's config spec, backed by Forge's.
 *
 * The third-largest cluster on the backward shim list. {@code Builder} is named by 212 corpus
 * jars, its constructor called by 211, {@code comment} by 180, {@code define(String, boolean)} by
 * 168, {@code push}/{@code pop} by 162/160 — a mod with a config file touches nearly all of it.
 *
 * <h2>Why this is mostly forwarding</h2>
 *
 * NeoForge renamed {@code ForgeConfigSpec} to {@code ModConfigSpec} and kept the shape: the same
 * builder, the same {@code define}/{@code defineInRange} families, the same typed value classes.
 * So almost every method here hands its arguments straight over and wraps what comes back. The
 * wrapping is the only real work, and it exists because a mod's field is declared
 * {@code ModConfigSpec.BooleanValue} and Forge returns its own type.
 *
 * <h2>Scoped to the measured surface</h2>
 *
 * Forge's builder has some forty overloads. The ones here are the ones the corpus calls, plus the
 * {@code List<String>} path variants that cost nothing to add alongside. Anything absent is a
 * {@code NoSuchMethodError} the report will name against a real call site, which is the same
 * bargain forge-compat was built on and the reason its 92 classes cover 652 referenced types.
 */
public class ModConfigSpec implements net.neoforged.fml.config.IConfigSpec {

    private final ForgeConfigSpec delegate;

    ModConfigSpec(ForgeConfigSpec delegate) {
        this.delegate = delegate;
    }

    @Override
    public ForgeConfigSpec forgeSpec() {
        return delegate;
    }

    public boolean isLoaded() {
        return delegate.isLoaded();
    }

    /** A config entry. NeoForge's is a {@code Supplier}, and so is Forge's. */
    public static class ConfigValue<T> implements Supplier<T> {

        final ForgeConfigSpec.ConfigValue<T> delegate;

        ConfigValue(ForgeConfigSpec.ConfigValue<T> delegate) {
            this.delegate = delegate;
        }

        @Override public T get() { return delegate.get(); }

        public T getDefault() { return delegate.getDefault(); }

        public List<String> getPath() { return delegate.getPath(); }

        public void set(T value) { delegate.set(value); }

        public void save() { delegate.save(); }

        public void clearCache() { delegate.clearCache(); }
    }

    /**
     * The typed values.
     *
     * They exist as distinct classes on both sides for the same reason: a mod declares
     * {@code ModConfigSpec.BooleanValue FOO} and the descriptor has to match exactly. Their
     * behaviour is entirely inherited.
     */
    public static class BooleanValue extends ConfigValue<Boolean> {
        BooleanValue(ForgeConfigSpec.ConfigValue<Boolean> d) { super(d); }
    }

    public static class IntValue extends ConfigValue<Integer> {
        IntValue(ForgeConfigSpec.ConfigValue<Integer> d) { super(d); }
    }

    public static class LongValue extends ConfigValue<Long> {
        LongValue(ForgeConfigSpec.ConfigValue<Long> d) { super(d); }
    }

    public static class DoubleValue extends ConfigValue<Double> {
        DoubleValue(ForgeConfigSpec.ConfigValue<Double> d) { super(d); }
    }

    public static class EnumValue<T extends Enum<T>> extends ConfigValue<T> {
        EnumValue(ForgeConfigSpec.ConfigValue<T> d) { super(d); }
    }

    /** The builder, which is what mods actually hold. */
    public static class Builder {

        private final ForgeConfigSpec.Builder delegate = new ForgeConfigSpec.Builder();

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

        public Builder worldRestart() {
            delegate.worldRestart();
            return this;
        }

        public Builder push(String path) {
            delegate.push(path);
            return this;
        }

        public Builder push(List<String> path) {
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

        public BooleanValue define(String path, boolean defaultValue) {
            return new BooleanValue(delegate.define(path, defaultValue));
        }

        public BooleanValue define(List<String> path, boolean defaultValue) {
            return new BooleanValue(delegate.define(path, defaultValue));
        }

        public <T> ConfigValue<T> define(String path, T defaultValue) {
            return new ConfigValue<>(delegate.define(path, defaultValue));
        }

        public <T> ConfigValue<T> define(List<String> path, T defaultValue) {
            return new ConfigValue<>(delegate.define(path, defaultValue));
        }

        public <T> ConfigValue<T> define(String path, T defaultValue, Predicate<Object> validator) {
            return new ConfigValue<>(delegate.define(path, defaultValue, validator));
        }

        public IntValue defineInRange(String path, int defaultValue, int min, int max) {
            return new IntValue(delegate.defineInRange(path, defaultValue, min, max));
        }

        public IntValue defineInRange(List<String> path, int defaultValue, int min, int max) {
            return new IntValue(delegate.defineInRange(path, defaultValue, min, max));
        }

        public LongValue defineInRange(String path, long defaultValue, long min, long max) {
            return new LongValue(delegate.defineInRange(path, defaultValue, min, max));
        }

        public DoubleValue defineInRange(String path, double defaultValue, double min, double max) {
            return new DoubleValue(delegate.defineInRange(path, defaultValue, min, max));
        }

        public DoubleValue defineInRange(List<String> path, double defaultValue, double min, double max) {
            return new DoubleValue(delegate.defineInRange(path, defaultValue, min, max));
        }

        public <T> ConfigValue<T> defineInList(String path, T defaultValue,
                                               java.util.Collection<? extends T> acceptable) {
            return new ConfigValue<>(delegate.defineInList(path, defaultValue, acceptable));
        }

        public <T> ConfigValue<List<? extends T>> defineList(String path, List<? extends T> defaultValue,
                                                             Predicate<Object> elementValidator) {
            return new ConfigValue<>(delegate.defineList(path, defaultValue, elementValidator));
        }

        public <V extends Enum<V>> EnumValue<V> defineEnum(String path, V defaultValue) {
            return new EnumValue<>(delegate.defineEnum(path, defaultValue));
        }

        public ModConfigSpec build() {
            return new ModConfigSpec(delegate.build());
        }

        /**
         * NeoForge's paired build, which hands back the spec and whatever the caller assembled.
         *
         * Forge's equivalent returns its own {@code Pair} of the same two things, so this rewraps
         * rather than reimplements.
         */
        public <T> org.apache.commons.lang3.tuple.Pair<T, ModConfigSpec> configure(
                java.util.function.Function<Builder, T> consumer) {
            T value = consumer.apply(this);
            return org.apache.commons.lang3.tuple.Pair.of(value, build());
        }
    }
}
