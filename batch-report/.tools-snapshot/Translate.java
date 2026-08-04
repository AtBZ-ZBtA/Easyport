import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Easyport transformer: rewrites a mod jar from Forge 1.20.1 to NeoForge 1.21.1.
 *
 * Deliberately does as little as possible. Under the shim-first architecture most loader-API
 * references need no rewriting at all — forge-compat ships a real net.minecraftforge.* tree,
 * so a mod's existing references resolve against it untouched. Rewriting is reserved for
 * cases where a shim is impossible, which in practice means net.minecraft.*: that package is
 * owned by the game and cannot be shadowed.
 *
 * Three passes, in this order:
 *
 *   1. SRG -> official member names. Forge 1.20.1 runs SRG at runtime, NeoForge runs official
 *      Mojang names, so this must happen before anything else — every later rule is written
 *      against official names and would not match otherwise.
 *   2. Structural and call-site rules from the rule file.
 *   3. Resources: descriptor rename, datapack directory renames.
 *
 * Anything it cannot translate is reported rather than guessed. Inventing a plausible target
 * for a removed API is the measured failure mode from handport/ — 5 false positives out of 26
 * symbols — and a jar that loads while quietly doing the wrong thing is worse than one that
 * refuses.
 *
 * Run:
 *   java -cp "<asm>;<asm-tree>;<asm-commons>" tools/Translate.java \
 *       <inputJar> <outputJar> <srg2official.tsv> <rules.tsv>
 */
public class Translate {

    /** 1.21 singularised the datapack tree. Mined from the corpus, not assumed. */
    private static final Map<String, String> DIR_RENAMES = new LinkedHashMap<>();
    static {
        DIR_RENAMES.put("recipes", "recipe");
        DIR_RENAMES.put("loot_tables", "loot_table");
        DIR_RENAMES.put("advancements", "advancement");
        DIR_RENAMES.put("structures", "structure");
        DIR_RENAMES.put("predicates", "predicate");
        DIR_RENAMES.put("item_modifiers", "item_modifier");
        DIR_RENAMES.put("functions", "function");
    }
    /** Tag subdirectories singularised alongside their parent registries. */
    private static final Map<String, String> TAG_RENAMES = new LinkedHashMap<>();
    static {
        TAG_RENAMES.put("blocks", "block");
        TAG_RENAMES.put("items", "item");
        TAG_RENAMES.put("fluids", "fluid");
        TAG_RENAMES.put("entity_types", "entity_type");
        TAG_RENAMES.put("game_events", "game_event");
    }

    record CtorRule(String owner, String ctorDesc, String factoryName, String factoryDesc) {}

    /** A constructor whose two arguments were reordered, optionally narrowing the new first one. */
    record SwapRule(String owner, String oldDesc, String newDesc, String castTop) {}

    /** A method that kept its shape but changed owner or name. The plain call-site rewrite. */
    record RenameRule(String owner, String name, String desc,
                      String newOwner, String newName, String newDesc) {}

    private final Map<String, String> srgToOfficial = new HashMap<>();
    private final List<CtorRule> ctorRules = new ArrayList<>();
    private final List<SwapRule> swapRules = new ArrayList<>();
    private final List<RenameRule> renameRules = new ArrayList<>();
    private final Set<String> removed = new LinkedHashSet<>();
    private final Map<String, String> typeRenames = new LinkedHashMap<>();
    private final Map<String, String> prefixRenames = new LinkedHashMap<>();

    private final Map<String, Integer> appliedCounts = new TreeMap<>();
    private final Map<String, Integer> unresolved = new TreeMap<>();
    private int classesRewritten = 0, resourcesMoved = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: java -cp \"<asm>;<asm-tree>;<asm-commons>\" tools/Translate.java "
                             + "<inputJar> <outputJar> <srg2official.tsv> <rules.tsv>");
            System.exit(2);
        }
        Translate t = new Translate();
        // Optional trailing arguments are target-platform jars. With them, mixins whose target
        // class no longer exists can be identified and dropped; without them that check is
        // skipped entirely rather than guessed at.
        if (args.length > 4) {
            t.loadTargetIndex(java.util.Arrays.copyOfRange(args, 4, args.length));
        }
        t.run(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]));
    }

    /**
     * Every class the target platform provides, used to decide whether a mixin can still apply.
     *
     * Empty when no platform jars are supplied, in which case mixin stripping is skipped
     * entirely — guessing that a target is absent would silently delete working mixins.
     */
    private final Set<String> targetClasses = new HashSet<>();

    /** Mixin classes to drop from their configs because their target no longer exists. */
    private final Set<String> deadMixins = new LinkedHashSet<>();

    private void loadTargetIndex(String[] jars) {
        for (String j : jars) {
            Path p = Paths.get(j);
            if (!Files.isRegularFile(p)) continue;
            try (ZipFile zip = new ZipFile(p.toFile())) {
                Enumeration<? extends ZipEntry> e = zip.entries();
                while (e.hasMoreElements()) {
                    String n = e.nextElement().getName();
                    if (n.endsWith(".class")) targetClasses.add(n.substring(0, n.length() - 6));
                }
            } catch (IOException ignored) {
                // A missing or unreadable platform jar just means a smaller index; the guard in
                // stripDeadMixins keeps that from being interpreted as "target absent".
            }
        }
    }

    private void run(Path in, Path out, Path mappings, Path rules) throws Exception {
        loadMappings(mappings);
        loadRules(rules);
        System.out.printf("Loaded %d SRG mappings, %d type renames, %d ctor rules, %d removed-API entries%n",
                          srgToOfficial.size(), typeRenames.size(), ctorRules.size(), removed.size());

        Files.createDirectories(out.toAbsolutePath().getParent());
        try (ZipFile zip = new ZipFile(in.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {

            findDeadMixins(zip);

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                byte[] data = read(zip, e);
                String name = e.getName();

                // Bundled libraries the target platform already provides must be dropped, not
                // copied. Mods ship dependencies under META-INF/jarjar/, and when two mods
                // bundle the same library -- or NeoForge already carries it -- the duplicates
                // become separate modules exporting one package and the layer refuses to
                // resolve:
                //   Modules MixinExtras and mixinextras.neoforge export package
                //   com.llamalad7.mixinextras to ...
                // That kills the whole launch, so it reads as the candidate mod failing rather
                // than as a packaging conflict between its dependencies.
                if (name.startsWith("META-INF/jarjar/") && shouldDropBundled(name)) {
                    count(appliedCounts, "JARJAR_DROP " + name.substring(name.lastIndexOf('/') + 1));
                    continue;
                }

                // Bundled mods must be translated too, not copied. They are ordinary Forge
                // 1.20.1 jars carrying META-INF/mods.toml, so NeoForge rejects them outright
                // and the outer mod then fails on a dependency that is physically present --
                // create ships flywheel and ponder inside itself and still reports both as
                // "not installed".
                if (name.startsWith("META-INF/jarjar/") && name.endsWith(".jar")) {
                    data = transformNestedJar(data, name, 1);
                    zos.putNextEntry(new ZipEntry(name));
                    zos.write(data);
                    zos.closeEntry();
                    continue;
                }

                if (name.endsWith(".class")) {
                    data = transformClass(data);
                    classesRewritten++;
                } else {
                    String moved = renamePath(name);
                    if (!moved.equals(name)) { resourcesMoved++; name = moved; }
                    if (name.equals("META-INF/neoforge.mods.toml")) data = migrateDescriptor(data);
                    // The jarjar index lists every bundled jar by path. Dropping a jar without
                    // removing its entry leaves FML resolving a path that is no longer there,
                    // which surfaces as "Invalid paths argument" and an IOException naming the
                    // *outer* mod -- so create.jar reads as corrupt when the real cause is a
                    // stale index entry for a library that was deliberately removed.
                    if (name.equals("META-INF/jarjar/metadata.json")) data = pruneJarJarIndex(data);
                    if (isMixinConfig(name) && !deadMixins.isEmpty()) data = stripDeadMixins(data);
                    // Access transformers address members by name as text, exactly like refmaps,
                    // and are just as invisible to the bytecode remapper. 234 of the 433 corpus
                    // jars ship one -- more than half -- and every line in every one of them
                    // names an SRG member that does not exist under official mappings.
                    //
                    // Nothing reports it. The AT is parsed, no line matches, the widening simply
                    // does not happen, and the mod loads normally until it touches the member it
                    // asked to be made public. Placebo surfaced it as an IllegalAccessError on
                    // TextColor.NAMED_COLORS, several rounds after its classes translated cleanly.
                    if (isRefmap(name) || isAccessTransformer(name)) {
                        data = remapSrgInText(new String(data, StandardCharsets.UTF_8))
                                .getBytes(StandardCharsets.UTF_8);
                    }
                }
                // Signatures cover the pre-translation bytes and cannot survive rewriting; a
                // stale signature file makes the jar fail verification outright.
                if (name.startsWith("META-INF/") &&
                    (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) continue;

                zos.putNextEntry(new ZipEntry(name));
                zos.write(data);
                zos.closeEntry();
            }
        }
        report(out);
    }

    // ---- bytecode ----------------------------------------------------------------------

    private byte[] transformClass(byte[] data) {
        // Pass 1: SRG -> official. Must precede rule matching, which uses official names.
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(new ClassRemapper(node, new Remapper() {
            @Override public String mapMethodName(String owner, String name, String desc) {
                return srgToOfficial.getOrDefault(name, name);
            }
            @Override public String mapFieldName(String owner, String name, String desc) {
                return srgToOfficial.getOrDefault(name, name);
            }
            @Override public String mapInvokeDynamicMethodName(String name, String desc) {
                return srgToOfficial.getOrDefault(name, name);
            }
            /**
             * Type renames, applied only to the explicitly listed types.
             *
             * Deliberately not a blanket net.minecraftforge -> net.neoforged rename: under
             * shim-first, most references should keep resolving against forge-compat. Only
             * types the loader itself scans for by name must move, because a shimmed copy is
             * a different type to that scan.
             */
            @Override public String map(String internalName) {
                String renamed = typeRenames.get(internalName);
                if (renamed != null) {
                    if (!renameTargetExists(renamed)) {
                        count(unresolved, "TYPE_RENAME target missing: " + renamed);
                        return internalName;
                    }
                    count(appliedCounts, "TYPE_RENAME " + internalName);
                    return renamed;
                }
                // Scoped prefix renames, for packages that are wholly event classes. Events are
                // dispatched by exact class identity, so every one of them must be rewritten --
                // enumerating several hundred individually would be noise, and any that is
                // missed fails silently by never firing.
                for (var e : prefixRenames.entrySet()) {
                    if (internalName.startsWith(e.getKey())) {
                        String target = e.getValue() + internalName.substring(e.getKey().length());
                        if (!renameTargetExists(target)) {
                            count(unresolved, "TYPE_PREFIX target missing: " + target);
                            return internalName;
                        }
                        count(appliedCounts, "TYPE_PREFIX " + e.getKey());
                        return target;
                    }
                }
                return internalName;
            }
        }), 0);

        fixEventBusSubscriber(node);

        // Mixin annotations address their targets as text, which the remapper never sees.
        remapAnnotationStrings(node.visibleAnnotations);
        remapAnnotationStrings(node.invisibleAnnotations);
        for (MethodNode m : node.methods) {
            remapAnnotationStrings(m.visibleAnnotations);
            remapAnnotationStrings(m.invisibleAnnotations);
        }
        for (var f : node.fields) {
            remapAnnotationStrings(f.visibleAnnotations);
            remapAnnotationStrings(f.invisibleAnnotations);
        }

        // Pass 2: structural rules.
        for (MethodNode m : node.methods) {
            if (m.instructions == null) continue;
            applyRenameRules(m.instructions);
            applyCtorRules(m.instructions);
            applySwapRules(m.instructions);
            recordRemoved(m.instructions);
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Rewrites {@code new Foo(a, b)} into {@code Foo.factory(a, b)}.
     *
     * The constructor call is three instructions apart from its allocation: NEW and DUP run
     * before the arguments are pushed, INVOKESPECIAL after. Removing the first two and
     * switching the third to INVOKESTATIC leaves the operand stack exactly as the factory
     * expects, so this is safe without any stack analysis — but only because NEW/DUP are
     * adjacent, which javac always emits for a plain constructor call. A NEW without an
     * immediately following DUP means the result is discarded or the pattern is something
     * else entirely, so it is left alone rather than guessed at.
     */
    private void applyCtorRules(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            if (min.getOpcode() != Opcodes.INVOKESPECIAL || !min.name.equals("<init>")) continue;

            CtorRule rule = null;
            for (CtorRule r : ctorRules) {
                if (r.owner().equals(min.owner) && r.ctorDesc().equals(min.desc)) { rule = r; break; }
            }
            if (rule == null) continue;

            TypeInsnNode newInsn = findMatchingNew(insns, min);
            if (newInsn == null) {
                count(unresolved, "CTOR_TO_STATIC (no NEW/DUP pair) " + min.owner + min.desc);
                continue;
            }
            AbstractInsnNode dup = newInsn.getNext();
            insns.remove(newInsn);
            insns.remove(dup);

            min.setOpcode(Opcodes.INVOKESTATIC);
            min.name = rule.factoryName();
            min.desc = rule.factoryDesc();
            min.itf = false;
            count(appliedCounts, "CTOR_TO_STATIC " + rule.owner() + "#" + rule.factoryName());
        }
    }

    private static final String EBS_DESC = "Lnet/neoforged/fml/common/EventBusSubscriber;";
    private static final String EBS_BUS_DESC = "Lnet/neoforged/fml/common/EventBusSubscriber$Bus;";
    private static final String SUBSCRIBE_DESC = "Lnet/neoforged/bus/api/SubscribeEvent;";

    /**
     * Repairs {@code @EventBusSubscriber} after the type rename, in two ways the remapper
     * cannot handle on its own.
     *
     * **The enum constant.** Forge's bus enum is FORGE/MOD, NeoForge's is GAME/MOD. Renaming
     * the *type* is not enough: ASM rewrites an annotation's enum descriptor but passes the
     * constant *name* through untouched, so {@code bus = Bus.FORGE} survives as a reference to
     * a constant that no longer exists. That fails at annotation resolution rather than at
     * load, which makes it hard to trace back here.
     *
     * **Classes with no handlers.** FML registers annotated classes itself, and NeoForge throws
     * when handed one with no {@code @SubscribeEvent} methods where Forge accepted it silently.
     * The ForgeEventBus shim absorbs that for explicit {@code register()} calls, but cannot for
     * these — FML does the registering, so the shim is never involved. The only fix available
     * at translation time is to drop the annotation, which restores Forge's behaviour exactly:
     * a class with no handlers subscribes to nothing either way.
     */
    private void fixEventBusSubscriber(ClassNode node) {
        if (node.visibleAnnotations == null) return;

        var subscriber = node.visibleAnnotations.stream()
                .filter(a -> EBS_DESC.equals(a.desc)).findFirst().orElse(null);
        if (subscriber == null) return;

        boolean hasHandlers = node.methods.stream().anyMatch(m ->
                m.visibleAnnotations != null &&
                m.visibleAnnotations.stream().anyMatch(a -> SUBSCRIBE_DESC.equals(a.desc)));

        if (!hasHandlers) {
            node.visibleAnnotations.remove(subscriber);
            count(appliedCounts, "EBS_STRIP (no @SubscribeEvent methods) " + node.name);
            return;
        }

        if (subscriber.values == null) return;
        // AnnotationNode stores values as alternating name/value; an enum value is a
        // String[]{descriptor, constantName}.
        for (int i = 1; i < subscriber.values.size(); i += 2) {
            if (subscriber.values.get(i) instanceof String[] enumRef
                    && enumRef.length == 2
                    && EBS_BUS_DESC.equals(enumRef[0])
                    && "FORGE".equals(enumRef[1])) {
                enumRef[1] = "GAME";
                count(appliedCounts, "EBS_BUS FORGE -> GAME");
            }
        }
    }

    /**
     * Rewrites calls to methods that kept their shape but changed owner or name.
     *
     * The simplest rule kind, and the one a rename table handles well — which is exactly the
     * 13-of-13 the corpus mining got right in the hand-port scoring. Runs before the
     * structural rules so those match against already-corrected owners.
     */
    private void applyRenameRules(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            for (RenameRule r : renameRules) {
                if (!r.owner().equals(min.owner) || !r.name().equals(min.name)
                        || !r.desc().equals(min.desc)) continue;
                min.owner = r.newOwner();
                min.name = r.newName();
                min.desc = r.newDesc();
                count(appliedCounts, "RENAME_METHOD " + r.owner() + "#" + r.name()
                                     + " -> " + r.newName());
                break;
            }
        }
    }

    /**
     * Reorders the two arguments of a constructor whose signature was rearranged upstream.
     *
     * Example: {@code TorchBlock(Properties, ParticleOptions)} became
     * {@code TorchBlock(SimpleParticleType, Properties)} in 1.21 — reordered *and* narrowed.
     *
     * Both arguments are already on the operand stack in source order when INVOKESPECIAL is
     * reached, so a single SWAP reorders them. Where the new signature also narrows a type, a
     * CHECKCAST must be emitted *before* the swap, while the value being narrowed is still on
     * top — after the swap it is buried and no longer reachable without more shuffling.
     *
     * Only valid for two category-1 arguments. A long or double occupies two stack slots and
     * SWAP would corrupt it, so those are refused and reported rather than mangled.
     */
    private void applySwapRules(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            if (min.getOpcode() != Opcodes.INVOKESPECIAL || !min.name.equals("<init>")) continue;

            for (SwapRule r : swapRules) {
                if (!r.owner().equals(min.owner) || !r.oldDesc().equals(min.desc)) continue;

                if (hasWideArgument(r.oldDesc())) {
                    count(unresolved, "SWAP2 refused (wide argument) " + r.owner() + r.oldDesc());
                    break;
                }
                if (r.castTop() != null && !r.castTop().isEmpty()) {
                    insns.insertBefore(min, new TypeInsnNode(Opcodes.CHECKCAST, r.castTop()));
                }
                insns.insertBefore(min, new org.objectweb.asm.tree.InsnNode(Opcodes.SWAP));
                min.desc = r.newDesc();
                count(appliedCounts, "CTOR_SWAP2 " + r.owner());
                break;
            }
        }
    }

    /** True if any argument is a long or double, which occupy two stack slots. */
    private static boolean hasWideArgument(String desc) {
        for (org.objectweb.asm.Type t : org.objectweb.asm.Type.getArgumentTypes(desc)) {
            if (t.getSort() == org.objectweb.asm.Type.LONG || t.getSort() == org.objectweb.asm.Type.DOUBLE) {
                return true;
            }
        }
        return false;
    }

    /** Walks back to the NEW that allocated the object this constructor initialises. */
    private static TypeInsnNode findMatchingNew(InsnList insns, MethodInsnNode ctor) {
        int depth = 0;
        for (AbstractInsnNode p = ctor.getPrevious(); p != null; p = p.getPrevious()) {
            if (p instanceof MethodInsnNode m && m.name.equals("<init>")) depth++;
            if (p instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW
                    && t.desc.equals(ctor.owner)) {
                if (depth == 0) {
                    AbstractInsnNode next = t.getNext();
                    return (next != null && next.getOpcode() == Opcodes.DUP) ? t : null;
                }
                depth--;
            }
        }
        return null;
    }

    /** Records calls into APIs with no replacement, so the report can name them. */
    private void recordRemoved(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            String sym = min.owner + "#" + min.name + min.desc;
            if (removed.contains(sym)) count(unresolved, "REMOVED " + sym);
        }
    }

    // ---- resources ---------------------------------------------------------------------

    /**
     * Whether a rename target actually exists on the target platform.
     *
     * Renaming to a class that does not exist is worse than not renaming at all: it converts a
     * reportable problem into a ClassNotFoundException naming a {@code net.neoforged} class
     * that Easyport invented, which reads as a platform bug rather than a translation gap.
     *
     * The prefix rules make this easy to hit. {@code net/minecraftforge/event/} →
     * {@code net/neoforged/neoforge/event/} is right for most of the tree, but NeoForge
     * restructured tick events — Forge's single {@code TickEvent$ClientTickEvent} with a phase
     * field became {@code event/tick/ClientTickEvent$Pre} and {@code $Post}. The prefix rule
     * happily produced {@code neoforge/event/TickEvent$ClientTickEvent}, which exists nowhere.
     *
     * Returns true when no platform index is loaded, so validation is skipped rather than
     * blocking every rename.
     */
    private boolean renameTargetExists(String internalName) {
        if (targetClasses.isEmpty()) return true;
        if (targetClasses.contains(internalName)) return true;

        // Only claim a class is absent when the index demonstrably covers its package. An
        // index is never guaranteed complete -- platform classes are spread across several
        // jars, and a forgotten one makes every rename into it look invalid.
        //
        // This has now caused two regressions. First the FML loader jar was missing, so @Mod
        // was rejected. Then distmarker was missing, so OnlyIn was rejected 105 times and every
        // library that had been loading stopped. In both cases the check was confidently wrong
        // about classes that existed.
        //
        // Requiring a sibling in the same package makes the failure mode safe: an unindexed
        // package yields "assume present" and the rename proceeds as it did before validation
        // existed, while a package we genuinely know still catches invented targets like
        // neoforge/event/TickEvent$ClientTickEvent.
        int lastSlash = internalName.lastIndexOf('/');
        if (lastSlash < 0) return true;
        String pkg = internalName.substring(0, lastSlash + 1);
        boolean packageKnown = knownPackages.computeIfAbsent(pkg, p ->
                targetClasses.stream().anyMatch(c -> c.startsWith(p)));
        return !packageKnown;
    }

    /** Cached per-package answers; the scan over targetClasses is otherwise repeated constantly. */
    private final Map<String, Boolean> knownPackages = new HashMap<>();

    /** SRG member names appearing inside text: refmaps, @At targets, @Accessor names. */
    private static final java.util.regex.Pattern SRG_TOKEN =
        java.util.regex.Pattern.compile("\\b([mf]_\\d+_)\\b");

    /**
     * Rewrites SRG member names embedded in strings.
     *
     * The bytecode remapper only reaches real member references. Mixins address their targets
     * as *text* — refmap JSON entries, {@code @At(target = "...m_12345_...")},
     * {@code @Accessor("f_678_")} — and those strings pass through untouched, so every
     * injection point still names a member that does not exist under official mappings.
     *
     * This is why four of the highest-fan-in libraries failed with InvalidInjectionException
     * and InvalidAccessorException after their classes translated cleanly: the code was right
     * and the coordinates pointing into it were stale.
     */
    private String remapSrgInText(String text) {
        if (srgToOfficial.isEmpty() || text == null) return text;
        java.util.regex.Matcher m = SRG_TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            String official = srgToOfficial.get(m.group(1));
            if (official != null) changed = true;
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    official != null ? official : m.group(1)));
        }
        m.appendTail(sb);
        if (changed) count(appliedCounts, "SRG_IN_TEXT");
        return sb.toString();
    }

    /** Walks annotation values, rewriting SRG names in any string they contain. */
    private void remapAnnotationStrings(List<org.objectweb.asm.tree.AnnotationNode> anns) {
        if (anns == null) return;
        for (var ann : anns) remapAnnotationValues(ann.values);
    }

    private void remapAnnotationValues(List<Object> values) {
        if (values == null) return;
        for (int i = 0; i < values.size(); i++) {
            Object v = values.get(i);
            if (v instanceof String s) {
                values.set(i, remapSrgInText(s));
            } else if (v instanceof List<?> list) {
                @SuppressWarnings("unchecked") List<Object> l = (List<Object>) list;
                remapAnnotationValues(l);
            } else if (v instanceof org.objectweb.asm.tree.AnnotationNode nested) {
                remapAnnotationValues(nested.values);
            }
        }
    }

    private static boolean isRefmap(String name) {
        return name.endsWith(".json") && name.contains("refmap");
    }

    /**
     * Access transformer configs, which NeoForge still reads from the conventional path.
     *
     * Matched on the filename rather than the exact path: the default location is
     * META-INF/accesstransformer.cfg, but a mod may declare additional ones under other names,
     * and remapping a file that turns out not to be an AT is harmless -- SRG tokens only appear
     * in files that mean them.
     */
    private static boolean isAccessTransformer(String name) {
        return name.startsWith("META-INF/") && name.endsWith(".cfg") && name.contains("accesstransformer");
    }

    private static boolean isMixinConfig(String name) {
        return name.endsWith(".json") && name.contains("mixins") && !name.contains("/");
    }

    /**
     * Finds mixins whose target class no longer exists in the target version.
     *
     * A mixin naming a deleted vanilla class can never apply — architectury's
     * MixinLootDataManager targets {@code LootDataManager}, which 1.21 removed when loot
     * handling moved to registries. Unlike an ordinary class reference this cannot be fixed by
     * relocating a stub: mixins are applied to the real loaded class, so a stand-in in another
     * package is not the thing being patched.
     *
     * Dropping the mixin is the honest outcome. It removes a behaviour the mod intended, so it
     * is reported rather than done quietly — but the alternative is the whole mod failing to
     * load, taking every one of its dependents with it. architectury alone has 12.
     *
     * Only skips a mixin when the target is confidently known to be absent: the target must
     * live in {@code net/minecraft/} and the platform index must be populated. Anything else is
     * left alone, since wrongly deleting a working mixin is far worse than leaving a dead one.
     */
    private void findDeadMixins(ZipFile zip) {
        if (targetClasses.isEmpty()) return;

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (!isMixinConfig(e.getName())) continue;
            try {
                String json = new String(read(zip, e), StandardCharsets.UTF_8);
                java.util.regex.Matcher pm = java.util.regex.Pattern
                        .compile("\"package\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                if (!pm.find()) continue;
                String pkg = pm.group(1).replace('.', '/');

                java.util.regex.Matcher cm = java.util.regex.Pattern
                        .compile("\"([A-Za-z0-9_$.]+)\"").matcher(json);
                while (cm.find()) {
                    String simple = cm.group(1);
                    if (simple.contains(" ") || simple.startsWith("net.")) continue;
                    ZipEntry mixinEntry = zip.getEntry(pkg + "/" + simple.replace('.', '/') + ".class");
                    if (mixinEntry == null) continue;
                    for (String target : mixinTargets(read(zip, mixinEntry))) {
                        if (target.startsWith("net/minecraft/") && !targetClasses.contains(target)) {
                            deadMixins.add(simple);
                            count(unresolved, "MIXIN_DROP (target gone: " + target + ") " + simple);
                        }
                    }
                }
            } catch (Exception ignored) {
                // A config we cannot parse is left untouched rather than guessed at.
            }
        }
    }

    /** Reads the class names a mixin declares in its {@code @Mixin} annotation. */
    private static List<String> mixinTargets(byte[] classBytes) {
        List<String> targets = new ArrayList<>();
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE);

        // @Mixin has CLASS retention, so ASM files it under invisibleAnnotations. Checking only
        // visibleAnnotations finds nothing and silently concludes every mixin is fine.
        List<org.objectweb.asm.tree.AnnotationNode> all = new ArrayList<>();
        if (node.visibleAnnotations != null) all.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) all.addAll(node.invisibleAnnotations);

        for (var ann : all) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(ann.desc) || ann.values == null) continue;
            for (int i = 1; i < ann.values.size(); i += 2) {
                if (ann.values.get(i) instanceof List<?> list) {
                    for (Object o : list) {
                        // value = Class[]; targets = String[] of fully-qualified names.
                        if (o instanceof org.objectweb.asm.Type t) targets.add(t.getInternalName());
                        else if (o instanceof String s) targets.add(s.replace('.', '/'));
                    }
                }
            }
        }
        return targets;
    }

    /** Removes dead mixin entries from a config's class lists. */
    private byte[] stripDeadMixins(byte[] data) {
        String json = new String(data, StandardCharsets.UTF_8);
        for (String dead : deadMixins) {
            json = json.replaceAll("\\s*\"" + java.util.regex.Pattern.quote(dead) + "\"\\s*,", "")
                       .replaceAll(",\\s*\"" + java.util.regex.Pattern.quote(dead) + "\"\\s*", "")
                       .replaceAll("\"" + java.util.regex.Pattern.quote(dead) + "\"", "");
        }
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Bundled jars can bundle their own; stop before a malformed or hostile jar recurses forever. */
    private static final int MAX_NESTING = 3;

    /**
     * Translates a bundled jar in place, in memory.
     *
     * Applies the same passes as the outer jar — class rewriting, descriptor migration,
     * resource renames — so a bundled mod ends up as loadable as a top-level one. Anything it
     * bundles in turn is translated too, up to {@link #MAX_NESTING}.
     *
     * Failures are swallowed and the original bytes returned. A bundled jar that will not parse
     * is usually a plain library rather than a mod, and losing it outright is worse than
     * shipping it untranslated: the outer mod may not need it rewritten at all.
     */
    private byte[] transformNestedJar(byte[] jarBytes, String name, int depth) {
        if (depth > MAX_NESTING) return jarBytes;
        try {
            Path tmp = Files.createTempFile("easyport-nested", ".jar");
            Files.write(tmp, jarBytes);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

            try (ZipFile zip = new ZipFile(tmp.toFile());
                 ZipOutputStream zos = new ZipOutputStream(out)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory()) continue;
                    byte[] data = read(zip, e);
                    String entryName = e.getName();

                    if (entryName.startsWith("META-INF/jarjar/") && shouldDropBundled(entryName)) continue;
                    if (entryName.startsWith("META-INF/") &&
                        (entryName.endsWith(".SF") || entryName.endsWith(".RSA")
                         || entryName.endsWith(".DSA"))) continue;

                    if (entryName.startsWith("META-INF/jarjar/") && entryName.endsWith(".jar")) {
                        data = transformNestedJar(data, entryName, depth + 1);
                    } else if (entryName.endsWith(".class")) {
                        data = transformClass(data);
                    } else {
                        String moved = renamePath(entryName);
                        if (!moved.equals(entryName)) entryName = moved;
                        if (entryName.equals("META-INF/neoforge.mods.toml")) data = migrateDescriptor(data);
                        if (entryName.equals("META-INF/jarjar/metadata.json")) data = pruneJarJarIndex(data);
                    }
                    zos.putNextEntry(new ZipEntry(entryName));
                    zos.write(data);
                    zos.closeEntry();
                }
            }
            Files.deleteIfExists(tmp);
            count(appliedCounts, "JARJAR_TRANSLATE (depth " + depth + ")");
            return out.toByteArray();
        } catch (Exception e) {
            count(unresolved, "JARJAR_TRANSLATE failed, shipped as-is: " + name);
            return jarBytes;
        }
    }

    /**
     * True if a bundled library should be dropped rather than carried into the output.
     *
     * Matched on filename prefix because bundled jars carry their version in the name and
     * every mod bundles a different one — mixinextras-forge-0.2.0-beta.8 in ars_nouveau,
     * mixinextras-forge-0.4.1 in create.
     *
     * Only libraries NeoForge itself provides belong here. Dropping anything else would remove
     * a dependency nothing replaces.
     *
     * Note what this does *not* fix: bundled jars that stay are copied through untranslated, so
     * they still contain Forge 1.20.1 bytecode. Translating them recursively is the real fix
     * and is not done yet.
     */
    private static boolean shouldDropBundled(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1).toLowerCase();
        return file.startsWith("mixinextras");
    }

    /**
     * Removes entries for bundled jars that {@link #shouldDropBundled} discarded.
     *
     * Splits the "jars" array by brace depth rather than by regex. Each entry contains nested
     * "identifier" and "version" objects, so a character-class pattern cannot span one — an
     * earlier attempt matched nothing and emitted an empty index, which is strictly worse than
     * the stale index it was meant to fix.
     */
    private byte[] pruneJarJarIndex(byte[] data) {
        String json = new String(data, StandardCharsets.UTF_8);
        int arrayStart = json.indexOf('[', json.indexOf("\"jars\""));
        if (arrayStart < 0) return data;

        List<String> kept = new ArrayList<>();
        int depth = 0, entryStart = -1;
        for (int i = arrayStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) entryStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && entryStart >= 0) {
                    String entry = json.substring(entryStart, i + 1);
                    java.util.regex.Matcher pm = java.util.regex.Pattern
                            .compile("\"path\"\\s*:\\s*\"([^\"]+)\"").matcher(entry);
                    boolean drop = pm.find() && shouldDropBundled(pm.group(1));
                    if (drop) count(appliedCounts, "JARJAR_INDEX_PRUNE");
                    else kept.add(entry.replaceAll("(?m)^", "    ").strip());
                    entryStart = -1;
                }
            } else if (c == ']' && depth == 0) {
                break;
            }
        }
        return ("{\n  \"jars\": [\n    " + String.join(",\n    ", kept) + "\n  ]\n}\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Applies the descriptor rename and the 1.21 datapack directory singularisation. */
    private static String renamePath(String path) {
        if (path.equals("META-INF/mods.toml")) return "META-INF/neoforge.mods.toml";

        String[] p = path.split("/");
        if (p.length >= 4 && p[0].equals("data")) {
            String renamed = DIR_RENAMES.get(p[2]);
            if (renamed != null) p[2] = renamed;
            if (p[2].equals("tags") && p.length >= 5) {
                String tagRenamed = TAG_RENAMES.get(p[3]);
                if (tagRenamed != null) p[3] = tagRenamed;
            }
            return String.join("/", p);
        }
        return path;
    }

    /**
     * Migrates the mod descriptor.
     *
     * Mining showed the key set is essentially identical between loaders, so this is mostly a
     * file rename — but the *dependency declarations* are not. A renamed descriptor whose
     * dependencies still say `minecraft [1.20.1,1.21)` gets past discovery only to be refused
     * during resolution, which is a more confusing failure than being rejected outright.
     *
     * Version ranges cannot be rewritten by pattern alone: `versionRange` is the same key in
     * every dependency block, so which value it should take depends on the `modId` declared
     * above it. This tracks the enclosing block instead of matching lines in isolation.
     */
    private static byte[] migrateDescriptor(byte[] data) {
        String[] lines = new String(data, StandardCharsets.UTF_8).split("\\R", -1);
        StringBuilder out = new StringBuilder();
        String depModId = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("[[dependencies.")) {
                depModId = null;
            } else if (trimmed.startsWith("[")) {
                depModId = null;                        // left the dependency block entirely
            }

            if (depModId == null || !trimmed.startsWith("versionRange")) {
                var m = java.util.regex.Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"").matcher(line);
                if (m.find()) {
                    String id = m.group(1);
                    if (id.equals("forge")) {
                        line = line.replace("\"forge\"", "\"neoforge\"");
                        depModId = "neoforge";
                    } else {
                        depModId = id;
                    }
                }
            }

            if (trimmed.startsWith("versionRange") && depModId != null) {
                String range = switch (depModId) {
                    case "neoforge" -> "[21.1,)";
                    case "minecraft" -> "[1.21.1,1.22)";
                    default -> null;                    // another mod's range; leave it alone
                };
                if (range != null) {
                    line = line.replaceAll("versionRange\\s*=\\s*\"[^\"]*\"",
                                           "versionRange=\"" + range + "\"");
                }
            }

            // NeoForge expresses dependency strength as type=, not the older mandatory= flag.
            if (trimmed.startsWith("mandatory")) {
                line = line.replaceAll("mandatory\\s*=\\s*true", "type=\"required\"")
                           .replaceAll("mandatory\\s*=\\s*false", "type=\"optional\"");
            }

            if (trimmed.startsWith("loaderVersion")) {
                line = line.replaceAll("loaderVersion\\s*=\\s*\"[^\"]*\"", "loaderVersion=\"[4,)\"");
            }

            out.append(line).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---- reporting ---------------------------------------------------------------------

    /**
     * The per-jar confidence report. Unresolved entries are the point: a translated jar that
     * loads while quietly doing the wrong thing is worse than one that admits what it could
     * not handle.
     */
    private void report(Path out) throws IOException {
        System.out.printf("%nRewrote %d classes, moved %d resources%n", classesRewritten, resourcesMoved);

        System.out.println("\nApplied:");
        if (appliedCounts.isEmpty()) System.out.println("  (none)");
        appliedCounts.forEach((k, v) -> System.out.printf("  %4d  %s%n", v, k));

        System.out.println("\nUNRESOLVED - these need the vanilla bridge or a structural rule:");
        if (unresolved.isEmpty()) System.out.println("  (none)");
        unresolved.forEach((k, v) -> System.out.printf("  %4d  %s%n", v, k));

        StringBuilder sb = new StringBuilder("status\tcount\tsymbol\n");
        appliedCounts.forEach((k, v) -> sb.append("APPLIED\t").append(v).append('\t').append(k).append('\n'));
        unresolved.forEach((k, v) -> sb.append("UNRESOLVED\t").append(v).append('\t').append(k).append('\n'));
        Path reportFile = Paths.get(out.toString().replaceAll("\\.jar$", "") + ".report.tsv");
        Files.writeString(reportFile, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("\nWrote " + out + "\n      " + reportFile);
    }

    private static void count(Map<String, Integer> m, String k) { m.merge(k, 1, Integer::sum); }

    // ---- inputs ------------------------------------------------------------------------

    private void loadMappings(Path p) throws IOException {
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String[] c = line.split("\t");
            if (c.length == 2 && !c[0].equals("srg")) srgToOfficial.put(c[0], c[1]);
        }
    }

    private void loadRules(Path p) throws IOException {
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            switch (c[0]) {
                case "CTOR_TO_STATIC" -> {
                    if (c.length >= 5) ctorRules.add(new CtorRule(c[1], c[2], c[3], c[4]));
                }
                case "RENAME_METHOD" -> {
                    if (c.length >= 7) renameRules.add(new RenameRule(c[1], c[2], c[3], c[4], c[5], c[6]));
                }
                case "CTOR_SWAP2" -> {
                    if (c.length >= 4) swapRules.add(new SwapRule(c[1], c[2], c[3],
                                                                  c.length > 4 ? c[4] : null));
                }
                case "TYPE_PREFIX_RENAME" -> {
                    if (c.length >= 3) prefixRenames.put(c[1], c[2]);
                }
                case "TYPE_RENAME" -> {
                    if (c.length >= 3) typeRenames.put(c[1], c[2]);
                }
                case "REMOVED" -> {
                    if (c.length >= 2) removed.add(c[1]);
                }
                default -> System.err.println("  unknown rule kind, ignored: " + c[0]);
            }
        }
    }

    private static byte[] read(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) { return in.readAllBytes(); }
    }
}
