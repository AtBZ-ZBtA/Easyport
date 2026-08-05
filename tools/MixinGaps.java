import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The Phase 5 work queue: every mixin coordinate in the corpus that does not resolve against
 * 1.21.1, ranked by how many jars it blocks.
 *
 * <h2>Why a third gap report</h2>
 *
 * {@link RenameGaps} and {@link VanillaGaps} both read a mined usage file, because ordinary code
 * addresses its targets through the constant pool and the remapper reaches all of it. Mixins do
 * not. They address targets as <em>text</em> -- {@code @Inject(method = "tick()V")},
 * {@code @At(target = "Lnet/minecraft/...;hurt(...)Z")}, {@code @Accessor("CRITERIA")} -- so
 * nothing in the mined member scan sees them and both existing reports give a mixin-heavy mod a
 * clean bill of health right up to the launch that aborts.
 *
 * The failure is also worse than an ordinary one. A member reference that does not resolve throws
 * {@code NoSuchMethodError} on the path that reaches it; a mixin coordinate that does not resolve
 * throws {@code InvalidInjectionException} or {@code InvalidAccessorException} during mixin apply,
 * which takes down the <em>whole launch</em>, every mod in it. That is why yungsapi and
 * supermartijn642corelib fail the way they do.
 *
 * So this tool reads the jars themselves rather than a usage file, and resolves each coordinate the
 * way Mixin will.
 *
 * <h2>The findings, deliberately separated</h2>
 *
 * <ul>
 *   <li><b>SELECTOR SIGNATURE CHANGED</b> -- the target method is still there under that name and
 *       the descriptor written into the annotation is stale. This is the mechanically repairable
 *       class: the platform's own descriptor is the answer, so where exactly one candidate exists
 *       no rule is needed at all. Printed with the candidates for exactly that reason.</li>
 *   <li><b>SELECTOR METHOD GONE</b> -- no method of that name on the target. Renamed or deleted,
 *       and the report cannot tell which. Needs a rule or a retarget.</li>
 *   <li><b>INJECTION POINT GONE</b> -- the {@code @At} anchor names a member that no longer
 *       exists. The mixin's own target may be perfectly fine; what changed is a call <em>inside</em>
 *       the method being patched. This is the shape the roadmap called the hard part.</li>
 *   <li><b>ACCESSOR TARGET GONE</b> -- {@code @Accessor} / {@code @Invoker} onto a member 1.21
 *       removed. yungsapi's {@code CriteriaTriggers.CRITERIA} is this.</li>
 *   <li><b>SHADOW GONE</b> -- a {@code @Shadow} member absent under that name and descriptor.
 *       Fields were already checked during translation; methods were not.</li>
 *   <li><b>TARGET CLASS GONE</b> -- reported last, because the transformer already drops these.
 *       Kept in the report so the count is visible rather than implied.</li>
 * </ul>
 *
 * <h2>Guards, all of them learned by an earlier report getting it wrong</h2>
 *
 * <ul>
 *   <li>SRG names are mapped to official ones <em>before</em> resolution, exactly as the
 *       transformer does to the same strings. Without it every coordinate in every Forge mod reads
 *       as missing and the report is 100% noise.</li>
 *   <li>Inherited members count as present, so a {@code @Shadow} of something a supertype declares
 *       is not a finding.</li>
 *   <li>Only targets under {@code net/minecraft/} are judged. A mixin into another mod's class or
 *       into Forge cannot be resolved against the platform index and would report as gone.</li>
 *   <li>A coordinate with {@code require = 0} is allowed to miss by its author, so a failure to
 *       resolve is not a launch failure. Counted separately, never listed as work.</li>
 *   <li>Wildcard and bare-name selectors resolve by name only -- matching Mixin, which accepts any
 *       overload when no descriptor is given.</li>
 * </ul>
 *
 * Usage:
 *   java -cp asm.jar tools/MixinGaps.java \
 *       "&lt;source-mods-folder&gt;" rules/forward.rules.tsv mappings/srg2official.tsv \
 *       &lt;platform.jar&gt;...
 */
public class MixinGaps {

    /**
     * Findings below this many jars are summarised rather than listed.
     *
     * Dropped to 1 when the run covers a single jar. Corpus-wide the threshold keeps a 500-line
     * queue readable; pointed at one mod it hid the finding -- every one of the blocked libraries
     * has exactly one or two broken coordinates, so the whole report read "(none)".
     */
    private static int listThreshold = 2;

    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.err.println("usage: MixinGaps <source-mods-folder> <rules.tsv> "
                             + "<srg2official.tsv> <platform.jar>...");
            System.exit(2);
        }

        Path mods = Path.of(args[0]);
        Rules rules = Rules.read(Path.of(args[1]));
        Map<String, String> srg = readSrg(Path.of(args[2]));

        List<Path> platformJars = new ArrayList<>();
        for (int i = 3; i < args.length; i++) platformJars.add(Path.of(args[i]));
        Index index = Index.build(platformJars);

        Report report = new Report();
        List<Path> jars;
        if (Files.isRegularFile(mods)) {
            // A single jar, for working one mod. The corpus is an input, not a component -- see
            // tools/README on chasing one mod that will not translate.
            jars = List.of(mods);
            listThreshold = 1;
        } else {
            try (var stream = Files.list(mods)) {
                jars = stream.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
            }
        }

        for (Path jar : jars) {
            try {
                new JarScan(jar, rules, srg, index, report).run();
            } catch (Exception e) {
                report.unreadable++;
            }
        }

        report.print(jars.size());
    }

    // ---- per-jar scan ------------------------------------------------------------------

    private static final class JarScan {
        private final Path jar;
        private final Rules rules;
        private final Map<String, String> srg;
        private final Index index;
        private final Report report;
        /** Findings this jar produced, so one jar counts once towards each. */
        private final Map<String, Set<String>> hits = new LinkedHashMap<>();

        JarScan(Path jar, Rules rules, Map<String, String> srg, Index index, Report report) {
            this.jar = jar;
            this.rules = rules;
            this.srg = srg;
            this.index = index;
            this.report = report;
        }

        void run() throws IOException {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                List<String> mixinClasses = mixinClassEntries(zip);
                if (mixinClasses.isEmpty()) return;
                report.mixinJars++;
                for (String entry : mixinClasses) {
                    ZipEntry e = zip.getEntry(entry);
                    if (e == null) continue;
                    try {
                        scanMixin(read(zip, e));
                    } catch (Exception ignored) {
                        // One unparseable mixin does not invalidate the jar.
                    }
                }
            }
            for (var e : hits.entrySet()) {
                report.record(e.getKey(), jar.getFileName().toString(), e.getValue());
            }
        }

        /**
         * Mixin class entries named by this jar's configs.
         *
         * Read from the configs rather than by scanning for the {@code @Mixin} annotation, because
         * a class carrying the annotation but absent from every config is never applied and its
         * coordinates are not a launch risk. The config is what Mixin actually reads.
         */
        private List<String> mixinClassEntries(ZipFile zip) {
            List<String> out = new ArrayList<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (!n.endsWith(".json") || !n.contains("mixins") || n.contains("/")) continue;
                try {
                    String json = new String(read(zip, e), StandardCharsets.UTF_8);
                    Matcher pm = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                    if (!pm.find()) continue;
                    String pkg = pm.group(1).replace('.', '/');
                    Matcher cm = Pattern.compile("\"([A-Za-z0-9_$.]+)\"").matcher(json);
                    while (cm.find()) {
                        String simple = cm.group(1);
                        if (simple.contains(" ") || simple.startsWith("net.")) continue;
                        String path = pkg + "/" + simple.replace('.', '/') + ".class";
                        if (zip.getEntry(path) != null) out.add(path);
                    }
                } catch (Exception ignored) {
                    // Unparseable config; its classes are simply not scanned.
                }
            }
            return out;
        }

        private void scanMixin(byte[] bytes) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);

            List<String> targets = mixinTargets(node);
            if (targets.isEmpty()) return;

            for (String target : targets) {
                // Deliberately *not* resolved through TYPE_RENAME. A rename points ordinary code at
                // a relocated stand-in, and a mixin can never target one -- mixins are applied to
                // the real loaded class, so the stand-in is not the thing being patched. Judging
                // the renamed name would report our own easyport/vanilla classes as missing
                // vanilla, which is true and useless.
                // Anything the platform index covers is judgeable, which includes forge-compat's
                // shims when it is on the jar list. A mixin into a Forge class is applied to
                // whatever the shim supplies, and a shim missing the patched method fails the
                // launch exactly like a deleted vanilla one -- supermartijn642corelib patches
                // net.minecraftforge.registries.GameData and died there long after its vanilla
                // mixins were fine. Anything else is another mod's class and is left alone.
                if (!index.classes.contains(target)) {
                    if (target.startsWith("net/minecraft/")) {
                        report.vanillaTargets++;
                        add("TARGET_CLASS_GONE", target, "");
                    } else {
                        report.nonVanillaTargets++;
                    }
                    continue;
                }
                report.vanillaTargets++;
                scanMembers(node, target);
            }
        }

        private void scanMembers(ClassNode node, String target) {
            for (FieldNode f : node.fields) {
                for (AnnotationNode ann : annotations(f.visibleAnnotations, f.invisibleAnnotations)) {
                    String kind = simpleAnnotationName(ann.desc);
                    if (!"Shadow".equals(kind)) continue;
                    String name = official(f.name);
                    if (index.membersOf(target).contains(name + " " + f.desc)) {
                        report.resolved++;
                    } else if (index.descriptorsOf(target, name).isEmpty()) {
                        report.stubbed++;
                        add("SHADOW_GONE", target + "#" + name, "field " + f.desc);
                    } else {
                        report.stubbed++;
                        add("SHADOW_RETYPED", target + "#" + name,
                            f.desc + " -> " + String.join(" | ", index.descriptorsOf(target, name)));
                    }
                }
            }

            for (MethodNode m : node.methods) {
                for (AnnotationNode ann : annotations(m.visibleAnnotations, m.invisibleAnnotations)) {
                    String kind = simpleAnnotationName(ann.desc);
                    Map<String, Object> values = valuesOf(ann);
                    switch (kind) {
                        case "Shadow" -> checkShadowMethod(m, target);
                        case "Overwrite" -> checkSelector(target, official(m.name) + m.desc, kind,
                                                          false, null);
                        case "Accessor", "Invoker" -> checkAccessor(m, target, kind, values);
                        case "Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable",
                             "ModifyConstant", "ModifyReturnValue", "WrapOperation", "WrapWithCondition" ->
                                checkInjector(target, kind, values, m);
                        default -> { }
                    }
                }
            }
        }

        // ---- individual coordinate kinds ------------------------------------------------

        private void checkShadowMethod(MethodNode m, String target) {
            // A shadow method's own name and descriptor are the coordinate; there is no string.
            String name = official(m.name);
            if (index.membersOf(target).contains(name + " " + m.desc)) {
                report.resolved++;
                return;
            }
            Set<String> candidates = index.descriptorsOf(target, name);
            report.stubbed++;
            if (candidates.isEmpty()) {
                add("SHADOW_GONE", target + "#" + name, "method " + m.desc);
            } else {
                add("SHADOW_RETYPED", target + "#" + name,
                    m.desc + " -> " + String.join(" | ", candidates));
            }
        }

        /**
         * {@code @Accessor} / {@code @Invoker}, which fail loudest of all.
         *
         * The target member name is the annotation value when given and inferred from the method
         * name otherwise -- {@code getSeed()} accesses {@code seed}, {@code invokeTick()} calls
         * {@code tick}. Mixin's own inference, reproduced here, because most real accessors omit
         * the value.
         */
        private void checkAccessor(MethodNode m, String target, String kind,
                                   Map<String, Object> values) {
            String declared = values.get("value") instanceof String s ? s : null;
            String name = declared != null ? official(stripSelectorName(declared))
                                           : inferAccessorName(m.name);
            if (name == null) return;

            boolean invoker = "Invoker".equals(kind);
            Set<String> present = index.descriptorsOf(target, name);
            if (!present.isEmpty()) {
                report.resolved++;
                return;
            }
            report.stubbed++;
            add(invoker ? "INVOKER_TARGET_GONE" : "ACCESSOR_TARGET_GONE",
                target + "#" + name, kind + " " + m.name + m.desc);
        }

        /**
         * {@code @Inject} and its relatives: a method selector plus zero or more {@code @At}
         * anchors, each of which can carry a member reference of its own.
         */
        private void checkInjector(String target, String kind, Map<String, Object> values,
                                   MethodNode handler) {
            boolean optional = isOptional(values);
            boolean inject = kind.equals("Inject");
            for (String selector : stringList(values.get("method"))) {
                // The handler constrains the repair only for @Inject. Every other injector's
                // handler matches the redirected call, not the enclosing method.
                checkSelector(target, selector, kind, optional, inject ? handler : null);
            }
            for (Object at : atNodes(values)) {
                // An @At on @Inject only locates a position, so a stale descriptor there can be
                // rewritten freely. On @Redirect and friends the anchor is what the handler
                // matches, so repairing it would trade one hard failure for another.
                if (at instanceof AnnotationNode an) checkAt(an, optional, inject);
            }

            // The anchor may name a member that still exists on a method that no longer calls it.
            // Nothing above sees that -- both halves resolve -- and Mixin still throws.
            if (optional) return;
            Set<String> selected = new LinkedHashSet<>();
            for (String selector : stringList(values.get("method"))) {
                selected.addAll(resolveTargetMethods(official(selector), target));
            }
            for (Object at : atNodes(values)) {
                if (!(at instanceof AnnotationNode an)) continue;
                String unreachable = unreachableAnchor(an, selected);
                if (unreachable == null) continue;
                report.defused++;
                add("INJECTION_POINT_UNREACHABLE", unreachable, kind + " into " + target);
            }
        }

        /** The concrete platform methods a selector picks out, as {@code owner\tname desc}. */
        private List<String> resolveTargetMethods(String selector, String target) {
            List<String> out = new ArrayList<>();
            if (selector.isEmpty() || selector.contains("*") || selector.startsWith("@")) return out;
            String owner = selectorOwner(selector);
            String effective = owner != null ? owner : target;
            if (!index.classes.contains(effective)) return out;
            String name = stripSelectorName(selector);
            String desc = selectorDesc(selector);
            for (String d : index.declaredDescriptorsOf(effective, name)) {
                if (desc != null && !desc.equals(d)) continue;
                out.add(effective + "\t" + name + " " + d);
            }
            return out;
        }

        /**
         * The anchor's symbol when it can no longer be found, or null when it can (or cannot be
         * judged). Mirrors {@code Translate.anchorReachable}, and is conservative the same way:
         * unreadable bodies and unmodelled anchor shapes count as reachable.
         */
        private String unreachableAnchor(AnnotationNode at, Set<String> selected) {
            if (selected.isEmpty()) return null;
            Map<String, Object> values = valuesOf(at);
            if (!(values.get("target") instanceof String spec) || spec.isBlank()
                    || spec.contains("*")) {
                return null;
            }
            String kind = values.get("value") instanceof String s ? s : "";
            char want;
            switch (kind) {
                case "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING" -> want = 'M';
                case "FIELD" -> want = 'F';
                default -> { return null; }
            }
            String resolved = official(spec);
            String owner = selectorOwner(resolved);
            if (owner == null) return null;
            String rest = afterOwner(resolved);
            if (rest.isEmpty()) return null;
            boolean field = want == 'F';
            String name = field && rest.contains(":") ? rest.substring(0, rest.indexOf(':'))
                                                      : stripSelectorName(resolved);
            if (name.isEmpty()) return null;

            boolean anyKnown = false;
            for (String key : selected) {
                int tab = key.indexOf('\t');
                Map<String, Set<String>> bodies = index.bodyRefsOf(key.substring(0, tab));
                Set<String> refs = bodies.get(key.substring(tab + 1));
                if (refs == null) continue;
                anyKnown = true;
                for (String ref : refs) {
                    if (!ref.startsWith(want + " ")) continue;
                    String body = ref.substring(2);
                    int dot = body.indexOf('.');
                    if (dot < 0) continue;
                    if (!owner.equals(body.substring(0, dot))) continue;
                    if (body.substring(dot + 1).startsWith(name)) return null;
                }
            }
            return anyKnown ? owner + "#" + name : null;
        }

        /** Whether the author already told Mixin this coordinate is allowed to match nothing. */
        private boolean isOptional(Map<String, Object> values) {
            Object require = values.get("require");
            Object expect = values.get("expect");
            if (require instanceof Integer i && i == 0) return true;
            return expect instanceof Integer i && i == 0 && require == null;
        }

        private List<Object> atNodes(Map<String, Object> values) {
            List<Object> out = new ArrayList<>();
            for (String key : new String[] {"at", "ats", "slice", "constant"}) {
                Object v = values.get(key);
                if (v instanceof AnnotationNode) out.add(v);
                else if (v instanceof List<?> list) out.addAll(list);
            }
            return out;
        }

        /**
         * A method selector against the mixin's own target class.
         *
         * Bare names resolve by name alone, matching Mixin: given no descriptor it accepts every
         * overload. A selector carrying an explicit owner is resolved against that owner instead,
         * which is how a mixin into an inner class addresses its outer.
         */
        private void checkSelector(String target, String rawSelector, String kind, boolean optional,
                                   MethodNode handler) {
            String selector = official(rawSelector);
            if (selector.isEmpty() || selector.equals("*") || selector.contains("*")
                    || selector.startsWith("@")) {
                report.wildcards++;
                return;
            }
            String owner = selectorOwner(selector);
            String effectiveOwner = owner != null ? owner : target;
            // Judgeable when the platform index covers it -- vanilla, NeoForge, or a forge-compat
            // shim. Another mod's class is not, and is left alone rather than reported gone.
            if (!index.classes.contains(effectiveOwner)) {
                report.unjudgeable++;
                return;
            }

            String name = stripSelectorName(selector);
            String desc = selectorDesc(selector);
            // Constructors are not inherited (STATE gotcha #14), so an <init> selector is judged
            // against what the class itself declares. Resolving one through the hierarchy reports
            // a removed constructor as present whenever any supertype declares its shape.
            boolean ctor = name.equals("<init>");
            Set<String> candidates = ctor ? index.declaredDescriptorsOf(effectiveOwner, name)
                                          : index.descriptorsOf(effectiveOwner, name);

            if (candidates.isEmpty()) {
                if (optional) { report.optionalMisses++; return; }
                report.defused++;
                add("SELECTOR_METHOD_GONE", effectiveOwner + "#" + name, kind);
                return;
            }
            if (desc == null || candidates.contains(desc)) {
                report.resolved++;
                return;
            }
            if (optional) { report.optionalMisses++; return; }

            // What the transformer will actually do with this. A coordinate it repairs is
            // completed work and must not sit at the head of the queue -- the same requirement
            // the Holder and arity passes put on VanillaGaps, where 1,600 jar-references of
            // finished work were being reported before the report was taught about them.
            String repaired = chooseDescriptor(desc, candidates);
            if (repaired != null && (handler == null || handlerStillMatches(handler, repaired))) {
                report.repaired++;
                return;
            }
            report.defused++;
            add("SELECTOR_SIGNATURE_CHANGED", effectiveOwner + "#" + name,
                desc + " -> " + String.join(" | ", candidates));
        }

        /**
         * An {@code @At} anchor's member reference -- the injection point proper.
         *
         * Only anchors that name a member are checkable. HEAD, RETURN and TAIL carry no target and
         * cannot go stale by themselves; what breaks them is the method body changing, which no
         * static check sees.
         */
        private void checkAt(AnnotationNode at, boolean optional, boolean mayRepair) {
            Map<String, Object> values = valuesOf(at);
            Object targetValue = values.get("target");
            if (!(targetValue instanceof String raw) || raw.isBlank()) {
                report.anchorless++;
                return;
            }
            String spec = official(raw);
            String owner = selectorOwner(spec);
            if (owner == null) { report.unjudgeable++; return; }
            String effectiveOwner = owner;
            // Judgeable when the platform index covers it -- vanilla, NeoForge, or a forge-compat
            // shim. Another mod's class is not, and is left alone rather than reported gone.
            if (!index.classes.contains(effectiveOwner)) {
                report.unjudgeable++;
                return;
            }

            String rest = afterOwner(spec);
            // @At("NEW") names only the type being constructed, with no member after it. Judged as
            // a member reference it yields the empty name, resolves to nothing, and reads as a
            // loss on an anchor whose class is right there in the index.
            if (rest.isEmpty()) { report.resolved++; return; }
            boolean field = rest.contains(":") && !rest.contains("(");
            String name = field ? rest.substring(0, rest.indexOf(':')) : stripSelectorName(spec);
            String desc = field ? rest.substring(rest.indexOf(':') + 1) : selectorDesc(spec);
            if (name.isEmpty()) { report.resolved++; return; }

            boolean ctor = name.equals("<init>");
            Set<String> candidates = ctor ? index.declaredDescriptorsOf(effectiveOwner, name)
                                          : index.descriptorsOf(effectiveOwner, name);
            if (candidates.isEmpty()) {
                if (optional) { report.optionalMisses++; return; }
                report.defused++;
                add("INJECTION_POINT_GONE", effectiveOwner + "#" + name, field ? "field" : "method");
                return;
            }
            if (desc == null || desc.isEmpty() || candidates.contains(desc)) {
                report.resolved++;
                return;
            }
            if (optional) { report.optionalMisses++; return; }
            if (mayRepair && chooseDescriptor(desc, candidates) != null) {
                report.repaired++;
                return;
            }
            report.defused++;
            add("INJECTION_POINT_SIGNATURE_CHANGED", effectiveOwner + "#" + name,
                desc + " -> " + String.join(" | ", candidates));
        }

        // ---- helpers --------------------------------------------------------------------

        private void add(String section, String symbol, String detail) {
            hits.computeIfAbsent(section + "\t" + symbol, k -> new LinkedHashSet<>()).add(detail);
        }

        /**
         * A selector as 1.21.1 would have to spell it: SRG members mapped to official names, and
         * renamed types renamed.
         *
         * The type half matters more than it looks. {@code BlockRenderDispatcher.renderBreakingTexture}
         * takes a {@code net/minecraftforge/client/model/data/ModelData} in 1.20.1 and a
         * {@code net/neoforged/...} one in 1.21.1, so the selector is stale for a reason that has
         * nothing to do with the method changing -- and the transformer, which rewrites that type
         * everywhere in bytecode, never touches the string.
         *
         * Renames are applied only where the result exists in the platform, and never to
         * {@code net/minecraft} types: those are renamed to relocated stand-ins, which are real
         * classes for ordinary code and meaningless as mixin coordinates.
         */
        private String official(String text) {
            if (text == null) return text;
            String out = text;
            if (!srg.isEmpty()) {
                Matcher m = SRG_TOKEN.matcher(out);
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    String mapped = srg.get(m.group(1));
                    m.appendReplacement(sb, Matcher.quoteReplacement(
                            mapped != null ? mapped : m.group(1)));
                }
                m.appendTail(sb);
                out = sb.toString();
            }
            return renameTypesIn(out, rules, index.classes);
        }

    }

    // ---- selector parsing ---------------------------------------------------------------

    private static final Pattern SRG_TOKEN = Pattern.compile("\\b([mf]_\\d+_)\\b");

    /** Every {@code Lsome/Type;} in a string, rewritten by the rule set where that is safe. */
    private static String renameTypesIn(String text, Rules rules, Set<String> platform) {
        if (text.indexOf('L') < 0) return text;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            int semi;
            if (ch == 'L' && (semi = text.indexOf(';', i)) > i + 1) {
                String type = text.substring(i + 1, semi);
                String renamed = type.startsWith("net/minecraft/") ? null : rules.rename(type);
                sb.append('L')
                  .append(renamed != null && platform.contains(renamed) ? renamed : type)
                  .append(';');
                i = semi + 1;
            } else {
                sb.append(ch);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * The owner an explicit selector names, or null when it addresses the mixin's own target.
     *
     * Mixin accepts two spellings, {@code Lnet/minecraft/Foo;bar()V} and
     * {@code net/minecraft/Foo.bar()V}, and they are not interchangeable in practice: the bare form
     * is what mods write for {@code remap = false} anchors, which is where Forge types appear.
     * A '/' is what distinguishes an owner from a bare member name containing a dot.
     */
    private static String selectorOwner(String selector) {
        if (selector.startsWith("L")) {
            int semi = selector.indexOf(';');
            return semi > 1 ? selector.substring(1, semi) : null;
        }
        int paren = selector.indexOf('(');
        String head = paren < 0 ? selector : selector.substring(0, paren);
        int lastDot = head.lastIndexOf('.');
        if (lastDot <= 0) return null;
        String candidate = head.substring(0, lastDot);
        return candidate.indexOf('/') >= 0 ? candidate : null;
    }

    private static String stripSelectorName(String selector) {
        String s = selector;
        int semi = s.indexOf(';');
        if (s.startsWith("L") && semi > 0) {
            s = s.substring(semi + 1);
        } else if (selectorOwner(selector) != null) {
            int p = s.indexOf('(');
            String h = p < 0 ? s : s.substring(0, p);
            s = s.substring(h.lastIndexOf('.') + 1);
        }
        int paren = s.indexOf('(');
        String head = paren < 0 ? s : s.substring(0, paren);
        int colon = head.indexOf(':');
        if (colon >= 0) head = head.substring(0, colon);
        return head.trim();
    }

    /** The part of a selector after its owner, in either spelling. */
    private static String afterOwner(String selector) {
        int semi = selector.indexOf(';');
        if (selector.startsWith("L") && semi > 0) return selector.substring(semi + 1);
        String owner = selectorOwner(selector);
        return owner == null ? selector : selector.substring(owner.length() + 1);
    }

    /** The descriptor a selector carries, or null when it gives only a name. */
    private static String selectorDesc(String selector) {
        int paren = selector.indexOf('(');
        return paren < 0 ? null : selector.substring(paren).trim();
    }

    /**
     * Which of the platform's descriptors a stale one meant, or null when it cannot be told.
     *
     * Must stay in step with {@code Translate.chooseDescriptor} -- this report's whole claim is
     * that it knows what the transformer does, and a divergence here puts completed work back at
     * the head of the queue or hides work that is still outstanding.
     */
    private static String chooseDescriptor(String stale, Set<String> candidates) {
        if (candidates.size() == 1) return candidates.iterator().next();
        int arity = org.objectweb.asm.Type.getArgumentTypes(stale).length;
        String only = null;
        for (String c : candidates) {
            if (org.objectweb.asm.Type.getArgumentTypes(c).length != arity) continue;
            if (only != null) return null;
            only = c;
        }
        return only;
    }

    /** Whether an {@code @Inject} handler survives a retarget. Mirrors {@code Translate}. */
    private static boolean handlerStillMatches(MethodNode handler, String newTargetDesc) {
        org.objectweb.asm.Type[] params = org.objectweb.asm.Type.getArgumentTypes(handler.desc);
        int callback = -1;
        for (int i = 0; i < params.length; i++) {
            String n = params[i].getSort() == org.objectweb.asm.Type.OBJECT
                     ? params[i].getInternalName() : "";
            if (n.endsWith("injection/callback/CallbackInfo")
                    || n.endsWith("injection/callback/CallbackInfoReturnable")) {
                callback = i;
                break;
            }
        }
        if (callback <= 0) return true;
        org.objectweb.asm.Type[] target = org.objectweb.asm.Type.getArgumentTypes(newTargetDesc);
        if (callback > target.length) return false;
        for (int i = 0; i < callback; i++) {
            if (!params[i].equals(target[i])) return false;
        }
        return true;
    }

    /**
     * Mixin's own accessor-name inference, for the common case of an omitted value.
     *
     * {@code getFoo}/{@code isFoo}/{@code setFoo} address field {@code foo}; {@code invokeTick}
     * or {@code callTick} address method {@code tick}. Anything else is left alone rather than
     * guessed at -- a wrong inference here would invent a finding on a working accessor.
     */
    private static String inferAccessorName(String methodName) {
        for (String prefix : new String[] {"get", "set", "is", "invoke", "call", "create", "new"}) {
            if (methodName.length() > prefix.length() && methodName.startsWith(prefix)
                    && Character.isUpperCase(methodName.charAt(prefix.length()))) {
                String rest = methodName.substring(prefix.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return null;
    }

    // ---- annotation reading -------------------------------------------------------------

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible,
                                                    List<AnnotationNode> invisible) {
        List<AnnotationNode> all = new ArrayList<>();
        if (visible != null) all.addAll(visible);
        // Mixin annotations have CLASS retention, so ASM files them under invisibleAnnotations.
        // Reading only the visible list finds nothing at all and reports a clean corpus.
        if (invisible != null) all.addAll(invisible);
        return all;
    }

    private static String simpleAnnotationName(String desc) {
        if (desc == null || !desc.startsWith("L") || !desc.endsWith(";")) return "";
        String internal = desc.substring(1, desc.length() - 1);
        if (!internal.contains("spongepowered") && !internal.contains("llamalad7")) return "";
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    private static Map<String, Object> valuesOf(AnnotationNode ann) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ann.values == null) return out;
        for (int i = 0; i + 1 < ann.values.size(); i += 2) {
            if (ann.values.get(i) instanceof String key) out.put(key, ann.values.get(i + 1));
        }
        return out;
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof String s) out.add(s);
        else if (value instanceof List<?> list) {
            for (Object o : list) if (o instanceof String s) out.add(s);
        }
        return out;
    }

    private static List<String> mixinTargets(ClassNode node) {
        List<String> targets = new ArrayList<>();
        for (AnnotationNode ann : annotations(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(ann.desc) || ann.values == null) {
                continue;
            }
            for (int i = 1; i < ann.values.size(); i += 2) {
                if (ann.values.get(i) instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof org.objectweb.asm.Type t) targets.add(t.getInternalName());
                        else if (o instanceof String s) targets.add(s.replace('.', '/'));
                    }
                }
            }
        }
        return targets;
    }

    private static byte[] read(ZipFile zip, ZipEntry e) throws IOException {
        try (var in = zip.getInputStream(e)) {
            return in.readAllBytes();
        }
    }

    // ---- report --------------------------------------------------------------------------

    private static final class Report {
        /** section -> symbol -> jars naming it. */
        private final Map<String, Map<String, Set<String>>> bySection = new LinkedHashMap<>();
        /** section+symbol -> details seen, for printing the platform's own answer alongside. */
        private final Map<String, Set<String>> details = new LinkedHashMap<>();

        int mixinJars, unreadable;
        int vanillaTargets, nonVanillaTargets;
        int wildcards, unjudgeable, optionalMisses, anchorless;

        /** Coordinates that still point at what their author meant. */
        int resolved;
        /** Stale descriptors the transformer rewrites off the platform. Also still intact. */
        int repaired;
        /** Injectors the transformer disables with {@code require = 0}. Behaviour lost. */
        int defused;
        /** Accessors and shadows given an inert stand-in. Behaviour lost. */
        int stubbed;

        void record(String key, String jar, Set<String> detail) {
            int tab = key.indexOf('\t');
            String section = key.substring(0, tab);
            String symbol = key.substring(tab + 1);
            bySection.computeIfAbsent(section, k -> new HashMap<>())
                     .computeIfAbsent(symbol, k -> new LinkedHashSet<>()).add(jar);
            details.computeIfAbsent(key, k -> new LinkedHashSet<>()).addAll(detail);
        }

        void print(int totalJars) {
            int checked = resolved + repaired + defused + stubbed;
            int intact = resolved + repaired;
            System.out.println("=== MIXIN COORDINATE RESOLUTION ===");
            System.out.println();
            System.out.printf("jars scanned              %d%n", totalJars);
            System.out.printf("jars carrying mixins      %d%n", mixinJars);
            System.out.printf("unreadable jars           %d%n", unreadable);
            System.out.printf("vanilla mixin targets     %d%n", vanillaTargets);
            System.out.printf("non-vanilla targets       %d  (not judged)%n", nonVanillaTargets);
            System.out.println();
            // The headline is what still does what the author intended, not what happens to link.
            // Every one of the four outcomes below loads; the bottom two load and do nothing,
            // which is the failure this project spends most of its checks avoiding, so it is
            // counted rather than folded into a pass rate.
            System.out.printf("coordinates checked       %d%n", checked);
            System.out.printf("  intact                  %d  (%.1f%%)%n",
                    intact, checked == 0 ? 100.0 : 100.0 * intact / checked);
            System.out.printf("    resolve unchanged     %d%n", resolved);
            System.out.printf("    descriptor repaired   %d%n", repaired);
            System.out.printf("  behaviour lost          %d%n", defused + stubbed);
            System.out.printf("    injector defused      %d%n", defused);
            System.out.printf("    accessor/shadow stub  %d%n", stubbed);
            System.out.println();
            System.out.printf("excluded, by reason%n");
            System.out.printf("  wildcard selectors      %d%n", wildcards);
            System.out.printf("  owner not judgeable     %d%n", unjudgeable);
            System.out.printf("  anchors with no target  %d%n", anchorless);
            System.out.printf("  author marked optional  %d%n", optionalMisses);
            System.out.println();
            System.out.println("Everything listed below loads. What it lists is behaviour the");
            System.out.println("translated mod no longer has -- the Phase 5 work queue proper.");
            System.out.println();

            String[] order = {
                "SELECTOR_SIGNATURE_CHANGED", "SELECTOR_METHOD_GONE",
                "INJECTION_POINT_SIGNATURE_CHANGED", "INJECTION_POINT_GONE",
                "INJECTION_POINT_UNREACHABLE",
                "ACCESSOR_TARGET_GONE", "INVOKER_TARGET_GONE",
                "SHADOW_RETYPED", "SHADOW_GONE", "TARGET_CLASS_GONE",
            };
            for (String section : order) printSection(section);
            printRollup();
        }

        private void printSection(String section) {
            Map<String, Set<String>> found = bySection.get(section);
            System.out.println("=== " + section.replace('_', ' ') + " ===");
            if (found == null || found.isEmpty()) {
                System.out.println("  (none)");
                System.out.println();
                return;
            }
            List<Map.Entry<String, Set<String>>> sorted = new ArrayList<>(found.entrySet());
            sorted.sort((a, b) -> b.getValue().size() != a.getValue().size()
                                ? b.getValue().size() - a.getValue().size()
                                : a.getKey().compareTo(b.getKey()));
            int summarised = 0;
            for (var e : sorted) {
                if (e.getValue().size() < listThreshold) { summarised++; continue; }
                System.out.printf("%5d  %s%n", e.getValue().size(), e.getKey());
                Set<String> d = details.get(section + "\t" + e.getKey());
                if (d != null) {
                    for (String line : d) {
                        if (!line.isBlank()) System.out.println("         " + line);
                    }
                }
            }
            if (summarised > 0) {
                System.out.printf("       ... and %d more in a single jar each%n", summarised);
            }
            System.out.println();
        }

        /** Which subsystem to fix, rather than which symbol -- the rollup VanillaGaps proved out. */
        private void printRollup() {
            Map<String, Set<String>> byType = new TreeMap<>();
            for (var section : bySection.entrySet()) {
                if (section.getKey().equals("TARGET_CLASS_GONE")) continue;
                for (var e : section.getValue().entrySet()) {
                    String owner = e.getKey().substring(0, Math.max(0, e.getKey().indexOf('#')));
                    byType.computeIfAbsent(owner, k -> new LinkedHashSet<>()).addAll(e.getValue());
                }
            }
            List<Map.Entry<String, Set<String>>> sorted = new ArrayList<>(byType.entrySet());
            sorted.sort((a, b) -> b.getValue().size() - a.getValue().size());
            System.out.println("=== BY TARGET TYPE ===");
            for (var e : sorted) {
                if (e.getValue().size() < listThreshold) continue;
                System.out.printf("%5d  %s%n", e.getValue().size(), e.getKey());
            }
            System.out.println();
        }
    }

    // ---- platform index ------------------------------------------------------------------

    /** Classes and their members, with inheritance folded in. Mirrors {@code VanillaGaps.Index}. */
    private static final class Index {
        final Set<String> classes = new HashSet<>();
        private final Map<String, Set<String>> declared = new HashMap<>();
        private final Map<String, String> superOf = new HashMap<>();
        private final Map<String, List<String>> interfacesOf = new HashMap<>();
        private final Map<String, Set<String>> resolvedCache = new HashMap<>();
        private final Map<String, Set<String>> jdkCache = new HashMap<>();

        /** Kept so method bodies can be read on demand; the index itself skips code. */
        private List<Path> jars = List.of();
        private final Map<String, Map<String, Set<String>>> bodyRefs = new HashMap<>();

        /**
         * What each method of a platform class references, read from its body.
         *
         * Needed only by the injection-point check, which asks a question the signature index
         * cannot answer: not "does this member exist" but "does the method being patched still
         * call it". Read lazily -- a mod has a handful of mixin targets, and reading bodies for
         * the whole platform would dominate the run.
         */
        Map<String, Set<String>> bodyRefsOf(String cls) {
            Map<String, Set<String>> hit = bodyRefs.get(cls);
            if (hit != null) return hit;
            Map<String, Set<String>> out = new HashMap<>();
            for (Path jar : jars) {
                try (ZipFile zf = new ZipFile(jar.toFile())) {
                    ZipEntry e = zf.getEntry(cls + ".class");
                    if (e == null) continue;
                    ClassNode node = new ClassNode();
                    try (var in = zf.getInputStream(e)) {
                        new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG);
                    }
                    for (MethodNode m : node.methods) {
                        Set<String> refs = new HashSet<>();
                        for (var insn : m.instructions.toArray()) {
                            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode min) {
                                refs.add("M " + min.owner + "." + min.name + min.desc);
                            } else if (insn instanceof org.objectweb.asm.tree.FieldInsnNode fin) {
                                refs.add("F " + fin.owner + "." + fin.name + fin.desc);
                            }
                        }
                        out.put(m.name + " " + m.desc, refs);
                    }
                    break;
                } catch (Exception ignored) {
                    // Unreadable: an empty map, which the caller treats as "cannot tell".
                }
            }
            bodyRefs.put(cls, out);
            return out;
        }

        static Index build(List<Path> jars) throws IOException {
            Index idx = new Index();
            idx.jars = jars;
            for (Path jar : jars) {
                try (ZipFile zf = new ZipFile(jar.toFile())) {
                    var entries = zf.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        if (!e.getName().endsWith(".class")) continue;
                        try (var in = zf.getInputStream(e)) {
                            var reader = new ClassReader(in);
                            Set<String> members = new HashSet<>();
                            reader.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                                @Override public org.objectweb.asm.MethodVisitor visitMethod(
                                        int a, String n, String d, String s, String[] ex) {
                                    members.add(n + " " + d);
                                    return null;
                                }
                                @Override public org.objectweb.asm.FieldVisitor visitField(
                                        int a, String n, String d, String s, Object v) {
                                    members.add(n + " " + d);
                                    return null;
                                }
                            }, ClassReader.SKIP_CODE);
                            String cn = reader.getClassName();
                            idx.classes.add(cn);
                            idx.declared.put(cn, members);
                            if (reader.getSuperName() != null) {
                                idx.superOf.put(cn, reader.getSuperName());
                            }
                            idx.interfacesOf.put(cn, List.of(reader.getInterfaces()));
                        } catch (Exception ignored) {
                            String n = e.getName();
                            idx.classes.add(n.substring(0, n.length() - 6));
                        }
                    }
                }
            }
            return idx;
        }

        Set<String> membersOf(String cls) {
            Set<String> hit = resolvedCache.get(cls);
            if (hit != null) return hit;
            Set<String> all = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            queue.add(cls);
            while (!queue.isEmpty()) {
                String k = queue.poll();
                if (!seen.add(k)) continue;
                Set<String> own = declared.get(k);
                if (own == null) own = jdk(k);
                if (own != null) all.addAll(own);
                String sup = superOf.get(k);
                if (sup != null) queue.add(sup);
                List<String> ifs = interfacesOf.get(k);
                if (ifs != null) queue.addAll(ifs);
            }
            resolvedCache.put(cls, all);
            return all;
        }

        /** Supertypes outside the platform jars -- in practice the JDK. See VanillaGaps for why. */
        private Set<String> jdk(String internalName) {
            Set<String> cached = jdkCache.get(internalName);
            if (cached != null) return cached;
            Set<String> members = new HashSet<>();
            try (var in = ClassLoader.getSystemResourceAsStream(internalName + ".class")) {
                if (in != null) {
                    var reader = new ClassReader(in);
                    reader.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                        @Override public org.objectweb.asm.MethodVisitor visitMethod(
                                int a, String n, String d, String s, String[] ex) {
                            members.add(n + " " + d);
                            return null;
                        }
                        @Override public org.objectweb.asm.FieldVisitor visitField(
                                int a, String n, String d, String s, Object v) {
                            members.add(n + " " + d);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE);
                    if (reader.getSuperName() != null) superOf.put(internalName, reader.getSuperName());
                    interfacesOf.put(internalName, List.of(reader.getInterfaces()));
                }
            } catch (Exception ignored) {
                // Not on the system class path; reads as having no members.
            }
            jdkCache.put(internalName, members);
            return members;
        }

        Set<String> descriptorsOf(String cls, String name) {
            return descriptors(membersOf(cls), name);
        }

        Set<String> declaredDescriptorsOf(String cls, String name) {
            return descriptors(declared.getOrDefault(cls, Set.of()), name);
        }

        private static Set<String> descriptors(Set<String> members, String name) {
            Set<String> out = new LinkedHashSet<>();
            String prefix = name + " ";
            for (String m : members) {
                if (m.startsWith(prefix)) out.add(m.substring(prefix.length()));
            }
            return out;
        }
    }

    // ---- rules ---------------------------------------------------------------------------

    private record Rules(Map<String, String> exact, Map<String, String> prefix) {
        String rename(String type) {
            String hit = exact.get(type);
            if (hit != null) return hit;
            for (var e : prefix.entrySet()) {
                if (type.startsWith(e.getKey())) {
                    return e.getValue() + type.substring(e.getKey().length());
                }
            }
            return null;
        }

        static Rules read(Path p) throws IOException {
            Map<String, String> exact = new LinkedHashMap<>();
            Map<String, String> prefix = new LinkedHashMap<>();
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] c = t.split("\t");
                switch (c[0]) {
                    case "TYPE_RENAME" -> { if (c.length >= 3) exact.put(c[1], c[2]); }
                    case "TYPE_PREFIX_RENAME" -> { if (c.length >= 3) prefix.put(c[1], c[2]); }
                    default -> { }
                }
            }
            return new Rules(exact, prefix);
        }
    }

    private static Map<String, String> readSrg(Path p) throws IOException {
        Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(p)) return out;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            out.put(line.substring(0, tab), line.substring(tab + 1).trim());
        }
        return out;
    }
}
