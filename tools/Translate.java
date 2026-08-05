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
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

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

    /**
     * A constructor that became a static factory.
     *
     * {@code factoryOwner} is usually the class itself -- ResourceLocation gained
     * fromNamespaceAndPath alongside its privatised constructor. It is separate because some
     * constructors have no vanilla replacement at all and have to land on a bridge:
     * AttributeModifier collapsed a UUID and a display name into one identifier, which is past
     * what any argument-level rule can express.
     */
    record CtorRule(String owner, String ctorDesc, String factoryName, String factoryDesc,
                    String factoryOwner) {}

    /** A constructor whose two arguments were reordered, optionally narrowing the new first one. */
    record SwapRule(String owner, String oldDesc, String newDesc, String castTop) {}

    /** A method that kept its shape but changed owner or name. The plain call-site rewrite. */
    record RenameRule(String owner, String name, String desc,
                      String newOwner, String newName, String newDesc) {}

    private final Map<String, String> srgToOfficial = new HashMap<>();
    private final List<CtorRule> ctorRules = new ArrayList<>();
    private final List<SwapRule> swapRules = new ArrayList<>();
    private final List<RenameRule> renameRules = new ArrayList<>();
    private final List<RenameRule> methodToStaticRules = new ArrayList<>();
    private final Map<String, String> fieldRetypes = new LinkedHashMap<>();
    private final List<RenameRule> fieldToStaticRules = new ArrayList<>();
    private final Set<String> removed = new LinkedHashSet<>();
    private final Map<String, String> typeRenames = new LinkedHashMap<>();
    private final Map<String, String> prefixRenames = new LinkedHashMap<>();

    /** "from\tto" -> the bridge call that converts between them. See Translate#coercion. */
    private final Map<String, RenameRule> coercions = new LinkedHashMap<>();

    /** Several parameters 1.21 folded into one. See Translate#applyArgCollapse. */
    record CollapseRule(String owner, String name, String oldDesc, String newDesc,
                        String bridgeOwner, String bridgeName) {}

    private final List<CollapseRule> collapseRules = new ArrayList<>();

    /** A parameter type 1.21 added -> the no-arg bridge that supplies one. */
    private final Map<String, RenameRule> argFillers = new LinkedHashMap<>();

    /** A platform type that stopped being an interface -> what to implement in its place. */
    private final Map<String, String> interfaceSubstitutes = new LinkedHashMap<>();

    /** Every class in the jar being translated -> its superclass. The mod's own hierarchy. */
    private final Map<String, String> modSuper = new HashMap<>();

    /** A mod class whose implements clause was substituted -> the substitute it now carries. */
    private final Map<String, String> substitutedClasses = new HashMap<>();

    /** Translated class bytes, held until the coercion pass has run over all of them. */
    private final Map<String, byte[]> transformedClasses = new LinkedHashMap<>();

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

    /** Target classes that are interfaces, for detecting a mixin whose target changed kind. */
    private final Set<String> targetInterfaces = new HashSet<>();

    /** Vanilla class -> every member it declares, as "name desc". Fields and methods together. */
    private final Map<String, Set<String>> targetMembers = new HashMap<>();
    private final Map<String, String> targetSuper = new HashMap<>();
    private final Map<String, List<String>> targetIfaces = new HashMap<>();
    private final Map<String, Set<String>> resolvedMemberCache = new HashMap<>();

    /** "owner.member" -> the T inside its {@code Holder<T>}, read from the generic signature. */
    private final Map<String, String> holderValueType = new HashMap<>();

    /** Abstract methods each platform class declares, for stubbing ones 1.21 added. */
    private final Map<String, Set<String>> targetAbstractMethods = new HashMap<>();

    /** Platform classes that are abstract, for splitting listeners registered on one. */
    private final Set<String> targetAbstract = new HashSet<>();

    /** Platform class -> "name desc" -> what that method's body references. Filled on demand. */
    private final Map<String, Map<String, Set<String>>> targetBodyRefs = new HashMap<>();

    /** Vanilla classes 1.21 made final, and the methods it made final, for the hierarchy checks. */
    private final Set<String> targetFinalClasses = new HashSet<>();
    private final Map<String, Set<String>> targetFinalMethods = new HashMap<>();

    private void loadTargetIndex(String[] jars) {
        platformJarPaths = jars;
        for (String j : jars) {
            Path p = Paths.get(j);
            if (!Files.isRegularFile(p)) continue;
            try (ZipFile zip = new ZipFile(p.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String n = e.getName();
                    if (!n.endsWith(".class")) continue;
                    String internal = n.substring(0, n.length() - 6);
                    targetClasses.add(internal);
                    // Vanilla and NeoForge are indexed in detail; nothing else is. Mixins target
                    // net.minecraft, and the hierarchy checks need net.neoforged too -- placebo's
                    // only remaining blocker is overriding a method NeoForge made final. Reading
                    // every member of every jar on the path would triple a scan that runs once
                    // per mod for no finding either check can use.
                    // Shims count as a mixin target, and only as that. A mod that mixes into Forge's
                    // own classes -- supermartijn642corelib patches GameData -- has its injection
                    // applied to whatever forge-compat supplies, and a shim that does not have the
                    // patched method fails the launch exactly like a deleted vanilla one. Nothing
                    // saw those before, because judging stopped at net/minecraft.
                    boolean shim = internal.startsWith("net/minecraftforge/");
                    if (!internal.startsWith("net/minecraft/")
                            && !internal.startsWith("net/neoforged/") && !shim) continue;
                    try {
                        ClassNode node = new ClassNode();
                        new ClassReader(read(zip, e)).accept(node,
                                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                        if ((node.access & Opcodes.ACC_INTERFACE) != 0) targetInterfaces.add(internal);
                        if ((node.access & Opcodes.ACC_ABSTRACT) != 0) targetAbstract.add(internal);
                        // Deliberately only the member and hierarchy maps below are filled for a
                        // shim. The abstract-stub and final-override passes read the maps skipped
                        // here, and feeding them a partial hand-written shim would have them
                        // rewriting mod code on the strength of what forge-compat happens to
                        // declare today. This addition is scoped to the mixin question.
                        if (node.methods != null && !shim) {
                            Set<String> abs = new HashSet<>();
                            for (var m : node.methods) {
                                if ((m.access & Opcodes.ACC_ABSTRACT) != 0) abs.add(m.name + " " + m.desc);
                            }
                            if (!abs.isEmpty()) targetAbstractMethods.put(internal, abs);
                        }
                        Set<String> fields = new HashSet<>();
                        if (node.fields != null) {
                            for (var f : node.fields) fields.add(f.name + " " + f.desc);
                        }
                        Set<String> members = new HashSet<>(fields);
                        if (node.methods != null) {
                            for (var m : node.methods) members.add(m.name + " " + m.desc);
                        }
                        targetMembers.put(internal, members);
                        if (node.superName != null) targetSuper.put(internal, node.superName);
                        if (node.interfaces != null) targetIfaces.put(internal, node.interfaces);
                        if ((node.access & Opcodes.ACC_FINAL) != 0 && !shim) {
                            targetFinalClasses.add(internal);
                        }
                        if (node.methods != null && !shim) {
                            Set<String> fin = new HashSet<>();
                            for (var m : node.methods) {
                                if ((m.access & Opcodes.ACC_FINAL) != 0) fin.add(m.name + " " + m.desc);
                            }
                            if (!fin.isEmpty()) targetFinalMethods.put(internal, fin);
                        }

                        // Generic signatures, kept only for Holder-typed members. The erased
                        // descriptor says Holder and nothing else; the type argument inside the
                        // signature is the only place the platform records what is *in* the
                        // holder, and unwrapping has to cast to something.
                        if (node.fields != null) {
                            for (var f : node.fields) {
                                if (f.signature != null && HOLDER_DESC.equals(f.desc)) {
                                    String arg = holderTypeArgument(f.signature);
                                    if (arg != null) holderValueType.put(internal + "." + f.name, arg);
                                }
                            }
                        }
                        if (node.methods != null) {
                            for (var m : node.methods) {
                                if (m.signature == null || !m.desc.endsWith(HOLDER_DESC)) continue;
                                String ret = m.signature.substring(m.signature.lastIndexOf(')') + 1);
                                String arg = holderTypeArgument(ret);
                                if (arg != null) holderValueType.put(internal + "." + m.name, arg);
                            }
                        }
                    } catch (Exception ignored) {
                        // Unparseable class: it still counts as present, just not inspectable.
                    }
                }
            } catch (IOException ignored) {
                // A missing or unreadable platform jar just means a smaller index; the guard in
                // stripDeadMixins keeps that from being interpreted as "target absent".
            }
        }
    }

    private void run(Path in, Path out, Path mappings, Path rules) throws Exception {
        inputJar = in;
        loadMappings(mappings);
        loadRules(rules);
        System.out.printf("Loaded %d SRG mappings, %d type renames, %d ctor rules, %d removed-API entries%n",
                          srgToOfficial.size(), typeRenames.size(), ctorRules.size(), removed.size());

        Files.createDirectories(out.toAbsolutePath().getParent());
        try (ZipFile zip = new ZipFile(in.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {

            findDeadMixins(zip);
            findSubstitutedClasses(zip);

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
                    // Held rather than written, so the coercion pass can run against the
                    // *translated* hierarchy. See runCoercionPass.
                    transformedClasses.put(name, data);
                    continue;
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
            runCoercionPass();
            for (var e : transformedClasses.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
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
        fixIllegalHierarchy(node);
        splitAbstractListeners(node);
        stubAddedAbstractMethods(node);

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
        // Must run after the SRG pass above: it resolves coordinates against the platform, and
        // every one of them is still spelled in 1.20.1 SRG until that has happened.
        repairMixinCoordinates(node);

        // Pass 2: structural rules.
        for (MethodNode m : node.methods) {
            if (m.instructions == null) continue;
            applyRenameRules(m.instructions);
            applyMethodToStaticRules(m.instructions);
            applyFieldRetypeRules(m.instructions);
            applyFieldToStaticRules(m.instructions);
            applyArgCollapse(m);
            applyCtorRules(m.instructions);
            applySwapRules(m.instructions);
            // Unwrap before wrap, and both after the explicit rules. Unwrap normalises what the
            // mod receives back to its 1.20.1 static types, which is what lets the wrap pass
            // decide about each argument by looking at the call site alone. Running them the
            // other way round would have the wrap pass reasoning about values whose type the
            // unwrap pass is about to change.
            applyHolderUnwrap(m.instructions);
            applyWrapAdapters(m);
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
            min.owner = rule.factoryOwner();
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
     * Retypes every field read on a holder class whose constants were wrapped in {@code Holder}.
     *
     * 1.21 turned classes like {@code ArmorMaterials} from enums implementing the thing they
     * described into plain holders of {@code Holder<ArmorMaterial>} constants. The field names
     * did not change, so nothing in the SRG mapping or the type renames touches this -- but the
     * *descriptor* on every GETSTATIC did, and the verifier reads descriptors.
     *
     * That makes it fail before the code ever runs, with a VerifyError rather than a
     * NoSuchFieldError: the verifier sees {@code ArmorMaterials} pushed where
     * {@code ArmorMaterial} is required, and in 1.21 the former no longer implements the latter.
     * geckolib and aquaculture both die on exactly this.
     *
     * Written per-owner rather than per-field deliberately. The change was wholesale -- every
     * constant on the class was wrapped at once -- so enumerating twenty fields would be twenty
     * chances to miss one, and a missed one fails the same way.
     *
     * The constructor that consumes the value needs its own rule; RENAME_METHOD handles that,
     * since a constructor whose descriptor changed but whose argument order did not is just a
     * call-site rewrite.
     */
    private void applyFieldRetypeRules(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof org.objectweb.asm.tree.FieldInsnNode fin)) continue;
            String newDesc = fieldRetypes.get(fin.owner);
            if (newDesc == null || newDesc.equals(fin.desc)) continue;
            fin.desc = newDesc;
            count(appliedCounts, "FIELD_RETYPE " + fin.owner + " -> " + newDesc);
        }
    }

    /**
     * Rewrites a static field read into a static method call.
     *
     * For constants the target platform deleted outright, where the value can still be computed.
     * {@code IEnvironment.Keys.NAMING} is the case this exists for: cyclopscore reads it to decide
     * whether it is running in a development environment, and modlauncher removed the field, so
     * the read fails with NoSuchFieldError during mod construction.
     *
     * A GETSTATIC pushes exactly one value and an INVOKESTATIC with no arguments pushes exactly
     * one value, so swapping the instruction is the whole transformation -- no stack adjustment,
     * the same shape as METHOD_TO_STATIC. The descriptors must match, which the rule states
     * explicitly rather than inferring.
     */
    private void applyFieldToStaticRules(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof org.objectweb.asm.tree.FieldInsnNode fin)) continue;
            if (fin.getOpcode() != Opcodes.GETSTATIC) continue;
            for (RenameRule r : fieldToStaticRules) {
                if (!r.name().equals(fin.name) || !r.desc().equals(fin.desc)) continue;
                // The owner is matched through the hierarchy, not by equality. javac records the
                // *qualifying* class for a static field reference, and for an inherited one
                // referred to unqualified that is the referring class -- so a mod extending
                // SwordItem and reading BASE_ATTACK_DAMAGE_UUID emits its own class as the owner.
                // Matching by equality missed every such read, and the failure named the mod's
                // class as the one lacking a vanilla field.
                if (!inheritsFrom(fin.owner, r.owner())) continue;
                insns.set(fin, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        r.newOwner(), r.newName(), r.newDesc(), false));
                count(appliedCounts, "FIELD_TO_STATIC " + r.owner() + "#" + r.name()
                                     + " -> " + r.newOwner() + "#" + r.newName());
                break;
            }
        }
    }

    /**
     * Rewrites an instance call into a static one that takes the receiver as its first argument.
     *
     * The case this exists for: a type that *must* be renamed -- because the loader dispatches
     * on it, so a shim would be a different class and never fire -- whose replacement is missing
     * a method the corpus calls. {@code RegisterEvent} is the first instance. Neither a rename
     * nor a shim can fix it alone; the call site has to move to a helper.
     *
     * No stack manipulation is required, which is what makes this cheap. When INVOKEVIRTUAL
     * executes, the receiver is already below the arguments in exactly the position
     * INVOKESTATIC reads its first parameter from. Changing the opcode and prepending the
     * receiver type to the descriptor is the whole transformation.
     *
     * Distinct from RENAME_METHOD, which preserves the opcode and therefore needs the new owner
     * to declare a matching *instance* method.
     */
    private void applyMethodToStaticRules(InsnList insns) {
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            if (min.getOpcode() != Opcodes.INVOKEVIRTUAL
                    && min.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
            for (RenameRule r : methodToStaticRules) {
                if (!r.owner().equals(min.owner) || !r.name().equals(min.name)
                        || !r.desc().equals(min.desc)) continue;
                MethodInsnNode replacement = new MethodInsnNode(
                        Opcodes.INVOKESTATIC, r.newOwner(), r.newName(), r.newDesc(), false);
                insns.set(min, replacement);
                count(appliedCounts, "METHOD_TO_STATIC " + r.owner() + "#" + r.name()
                                     + " -> " + r.newOwner() + "#" + r.newName());
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

    // ---- Holder adaptation -------------------------------------------------------------

    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String HOLDER_DESC = "Lnet/minecraft/core/Holder;";
    private static final String HOLDER_BRIDGE = "easyport/bridge/HolderBridge";

    /**
     * Every member a vanilla type has, inherited members included.
     *
     * Inheritance matters more here than it looks. {@code ArmorItem.getMaterial()} is declared on
     * {@code ArmorItem}, but plenty of the calls this pass has to judge land on members a
     * supertype declares, and treating those as absent would have this pass adapting calls that
     * were never broken.
     *
     * Empty when the platform index is not loaded, which switches both adaptation passes off
     * rather than having them guess.
     */
    private Set<String> resolvedTargetMembers(String cls) {
        Set<String> hit = resolvedMemberCache.get(cls);
        if (hit != null) return hit;
        Set<String> all = new HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(cls);
        while (!queue.isEmpty()) {
            String k = queue.poll();
            if (!seen.add(k)) continue;
            Set<String> own = targetMembers.get(k);
            if (own != null) all.addAll(own);
            String sup = targetSuper.get(k);
            if (sup != null) queue.add(sup);
            List<String> ifs = targetIfaces.get(k);
            if (ifs != null) queue.addAll(ifs);
        }
        resolvedMemberCache.put(cls, all);
        return all;
    }

    /**
     * Implements abstract methods 1.21 added to a platform class the mod extends.
     *
     * A mod compiled against 1.20.1 implemented everything abstract *then* -- it would not have
     * compiled otherwise. So an abstract method its superclass declares and it does not implement
     * is, without exception, one the newer platform added. That is what makes stubbing these safe
     * to do automatically: there is no case where the mod meant to leave one unimplemented.
     *
     * {@code ParticleType} is the example that forced it. 1.21 moved particle serialization onto
     * codecs by *removing* the constructor argument and adding {@code codec()} and
     * {@code streamCodec()} as abstract methods, so a mod's particle type is now abstract and
     * cannot be instantiated -- an {@code InstantiationError} at registration, taking the mod with
     * it.
     *
     * The stubs return null, which is the same trade the codec bridges make: the type registers
     * and everything registered beside it survives, and the specific thing 1.21 added does not
     * work. Every one is named in the report rather than left to be discovered.
     */
    private void stubAddedAbstractMethods(ClassNode node) {
        if (targetMembers.isEmpty()) return;
        if ((node.access & Opcodes.ACC_ABSTRACT) != 0) return;

        Set<String> implemented = new HashSet<>();
        for (MethodNode m : node.methods) implemented.add(m.name + " " + m.desc);

        // Interfaces as well as the superclass chain. 1.21 added getIncorrectBlocksForDrops to
        // Tier, and a mod's anonymous `new Tier() { ... }` therefore no longer implements it --
        // an AbstractMethodError the first time a tool is used, from a class that loads perfectly.
        // Walking only the superclass chain missed every one of those.
        java.util.ArrayDeque<String> roots = new java.util.ArrayDeque<>();
        if (node.superName != null) roots.add(node.superName);
        roots.addAll(node.interfaces);
        if (roots.stream().noneMatch(targetMembers::containsKey)) return;

        List<MethodNode> stubs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (!roots.isEmpty()) {
            String cls = roots.poll();
            if (!seen.add(cls)) continue;
            // A lower class in the chain may already implement what a higher one declares
            // abstract, so its concrete methods count as implemented before this class's own
            // abstract ones are judged. Skipping this stubbed Block.asBlock() to null on every
            // block in the mod -- BlockBehaviour declares it abstract and Block implements it,
            // and walking the chain without tracking that overrides the real implementation.
            Set<String> abstractHere = targetAbstractMethods.getOrDefault(cls, Set.of());
            for (String member : targetMembers.getOrDefault(cls, Set.of())) {
                if (member.contains("(") && !abstractHere.contains(member)) implemented.add(member);
            }
            for (String member : abstractHere) {
                if (!implemented.add(member)) continue;
                int sp = member.indexOf(' ');
                String name = member.substring(0, sp);
                String desc = member.substring(sp + 1);
                MethodNode stub = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
                Type ret = Type.getReturnType(desc);
                if (ret.getSort() == Type.VOID) {
                    stub.instructions.add(new InsnNode(Opcodes.RETURN));
                } else if (ret.getSort() == Type.OBJECT || ret.getSort() == Type.ARRAY) {
                    stub.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                    stub.instructions.add(new InsnNode(Opcodes.ARETURN));
                } else {
                    stub.instructions.add(new InsnNode(
                            ret.getSort() == Type.LONG ? Opcodes.LCONST_0
                          : ret.getSort() == Type.FLOAT ? Opcodes.FCONST_0
                          : ret.getSort() == Type.DOUBLE ? Opcodes.DCONST_0
                          : Opcodes.ICONST_0));
                    stub.instructions.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
                }
                stubs.add(stub);
                count(unresolved, "ABSTRACT_STUB " + node.name + "." + name + desc
                                + " (added by " + cls + ")");
            }
            String sup = targetSuper.get(cls);
            if (sup != null) roots.add(sup);
            List<String> ifs = targetIfaces.get(cls);
            if (ifs != null) roots.addAll(ifs);
        }
        node.methods.addAll(stubs);
    }

    /**
     * Splits a listener for an event NeoForge made abstract into one per concrete subclass.
     *
     * Forge let a mod listen for {@code ScreenEvent.Init} and receive both its {@code Pre} and
     * {@code Post}. NeoForge refuses the registration outright:
     *
     * <pre>
     * Cannot register listeners for abstract class ScreenEvent$Init.
     * Register a listener to one of its subclasses instead!
     * </pre>
     *
     * That is a hard load failure -- it happens during {@code @EventBusSubscriber} injection,
     * before the mod's own code runs -- and it is the same strictness difference that has bitten
     * this project twice before: NeoForge throws where Forge was quiet, so a shim has to preserve
     * *Forge's* behaviour rather than the platform's.
     *
     * <h2>Why two methods rather than one</h2>
     *
     * Retargeting the listener at {@code Post} would load and would silently halve it. The mod
     * asked for both phases and its handler very often branches on which one it got. So the
     * original keeps its body and loses its annotation, and one small dispatcher per concrete
     * subclass carries the annotation and forwards -- which is what Forge's bus did internally,
     * made explicit.
     *
     * Registering the parent is not something a shim can intercept, because FML performs the
     * registration itself from the annotation; the split has to exist in the bytecode by then.
     */
    private void splitAbstractListeners(ClassNode node) {
        if (targetMembers.isEmpty()) return;
        List<MethodNode> generated = new ArrayList<>();

        for (MethodNode m : node.methods) {
            if (m.visibleAnnotations == null) continue;
            if (m.visibleAnnotations.stream().noneMatch(a -> SUBSCRIBE_DESC.equals(a.desc))) continue;
            Type[] params = Type.getArgumentTypes(m.desc);
            if (params.length != 1 || params[0].getSort() != Type.OBJECT) continue;

            String event = params[0].getInternalName();
            if (!targetAbstract.contains(event)) continue;
            List<String> concrete = new ArrayList<>();
            for (String suffix : new String[] {"$Pre", "$Post"}) {
                if (targetMembers.containsKey(event + suffix)
                        && !targetAbstract.contains(event + suffix)) {
                    concrete.add(event + suffix);
                }
            }
            if (concrete.isEmpty()) continue;

            var annotations = m.visibleAnnotations;
            m.visibleAnnotations = null;
            String body = m.name + "$easyport";
            m.name = body;
            boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;

            for (String target : concrete) {
                String suffix = target.substring(target.lastIndexOf('$') + 1);
                MethodNode dispatcher = new MethodNode(m.access, body + suffix,
                        "(L" + target + ";)V", null, null);
                dispatcher.visibleAnnotations = new ArrayList<>(annotations);
                InsnList code = dispatcher.instructions;
                if (!isStatic) code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, isStatic ? 0 : 1));
                code.add(new MethodInsnNode(
                        isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL,
                        node.name, body, m.desc, false));
                code.add(new InsnNode(Opcodes.RETURN));
                generated.add(dispatcher);
            }
            count(appliedCounts, "EVENT_SPLIT abstract listener: " + event
                                + " -> " + concrete.size() + " concrete");
        }
        node.methods.addAll(generated);
    }

    /**
     * Handles a mod class whose *hierarchy* became illegal, which no call-site rewrite can reach.
     *
     * Offline verification turned up a failure family nothing else in this project could see, and
     * it is not small: six classes across four mods, every one of them fatal at load. 1.21 sealed
     * a lot of vanilla down.
     *
     * <h2>Overriding a method that became final -- fixed</h2>
     *
     * {@code TurtleLandEntity} overrides {@code LivingEntity.canBreatheUnderwater()}, which 1.21
     * made final. The whole class fails to load, taking with it every entity, item and block that
     * class registers.
     *
     * Renaming the declaration out of the way fixes the load. Nothing else has to change: an
     * internal call to {@code this.canBreatheUnderwater()} then resolves to the inherited final
     * method, which is vanilla's own behaviour and the only behaviour available now. What is lost
     * is precisely the mod's override, and that is what the report names.
     *
     * This is a behaviour change, so it is loud. A silently different turtle is worse than a
     * reported one, and the alternative -- refusing to translate the mod -- loses everything else
     * the class carries.
     *
     * <h2>Extending a class or implementing a type that changed kind -- reported</h2>
     *
     * {@code OutOfJarResourceLocation extends ResourceLocation}, now a final record.
     * {@code AquaArmorMaterials implements ArmorMaterial}, now a record rather than an interface.
     * Neither has a mechanical fix: the mod's class exists to *be* that type, and there is no
     * longer a version of that type it can be. Reported here rather than left to surface as an
     * {@code IncompatibleClassChangeError} halfway through a launch.
     */
    private void fixIllegalHierarchy(ClassNode node) {
        if (targetMembers.isEmpty()) return;

        if (node.superName != null && targetFinalClasses.contains(node.superName)) {
            count(unresolved, "HIERARCHY cannot extend final class: " + node.name
                            + " extends " + node.superName);
        }
        for (int i = 0; i < node.interfaces.size(); i++) {
            String itf = node.interfaces.get(i);
            // targetMembers is the "was actually inspected" test, and it has to be. Asking
            // targetInterfaces alone reports every type the index did not look inside as "no
            // longer an interface" -- the first run of this check accused IFluidHandler,
            // IItemHandlerModifiable and BiomeModifier, all of which are interfaces and always
            // were. Same shape as the rename-target validation: never claim absence from a part
            // of the index that was never populated.
            if (!targetMembers.containsKey(itf) || targetInterfaces.contains(itf)) continue;

            String substitute = interfaceSubstitutes.get(itf);
            if (substitute == null) {
                count(unresolved, "HIERARCHY not an interface any more: " + node.name
                                + " implements " + itf);
                continue;
            }
            // Substituted only here, in the implements clause of a class that actually implements
            // it. A TYPE_RENAME would also rewrite the mod's reads of vanilla's own constants,
            // which are real records and not implementations of the substitute -- the mod would
            // then hold a record in a variable typed as the interface and fail somewhere else
            // entirely. Same trap the Holder work fell into by letting a new type spread.
            node.interfaces.set(i, substitute);
            count(appliedCounts, "HIERARCHY interface substituted: " + itf + " -> " + substitute);
        }

        for (MethodNode m : node.methods) {
            if (m.name.startsWith("<") || (m.access & Opcodes.ACC_STATIC) != 0) continue;
            String owner = finalOwnerOf(node.superName, m.name + " " + m.desc);
            if (owner == null) continue;
            count(unresolved, "HIERARCHY override of a now-final method dropped: "
                            + node.name + "." + m.name + m.desc + " (final on " + owner + ")");
            m.name = "easyport$" + m.name;
            // All three access bits must be cleared before setting private. Clearing only PUBLIC
            // left a protected method as private|protected, which is 0x6 and a ClassFormatError:
            // "illegal modifiers". The verifier caught it; a launch would have reported it as the
            // mod being corrupt.
            m.access &= ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
            m.access |= Opcodes.ACC_PRIVATE;
            // Private, so nothing can dispatch to it by accident. Only the declaration is touched:
            // call sites keep naming the original, and now reach the inherited final method --
            // which is the behaviour that is actually available.
        }
    }

    /**
     * Finds every class in the jar whose implements clause will be substituted, before any class
     * is rewritten.
     *
     * A separate pass because the order classes come out of a zip is not the order they depend on
     * each other in. The class that *passes* a custom armour material to vanilla is usually read
     * before the material class itself, and by then the coercion pass has to already know that
     * material no longer implements what its signature says it does.
     *
     * Reads only the header -- SKIP_CODE and no member walk -- so a whole jar costs about as much
     * as one ordinary class transform.
     */
    private void findSubstitutedClasses(ZipFile zip) {

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (!e.getName().endsWith(".class")) continue;
            try {
                ClassReader reader = new ClassReader(read(zip, e));
                if (reader.getSuperName() != null) {
                    modSuper.put(reader.getClassName(), reader.getSuperName());
                }
                for (String itf : reader.getInterfaces()) {
                    String substitute = interfaceSubstitutes.get(itf);
                    if (substitute != null && targetMembers.containsKey(itf)
                            && !targetInterfaces.contains(itf)) {
                        substitutedClasses.put(reader.getClassName(), substitute);
                    }
                }
            } catch (Exception ignored) {
                // Unreadable class. It will fail the same way in the main pass, where the failure
                // is reported; silently missing it here only costs a coercion.
            }
        }

        // A subclass of a coercion source needs the same conversion its parent does.
        // PerkTierIngredient extends the AbstractIngredient shim, and a method declared to return
        // Ingredient returning one of those is the same problem one level down -- matching only
        // exact names left it failing with the identical error under a different class name.
        Set<String> sources = new HashSet<>();
        for (String pair : coercions.keySet()) sources.add(pair.substring(0, pair.indexOf('\t')));
        for (String cls : modSuper.keySet()) {
            if (substitutedClasses.containsKey(cls)) continue;
            String walk = modSuper.get(cls);
            Set<String> seen = new HashSet<>();
            while (walk != null && seen.add(walk)) {
                if (sources.contains(walk)) {
                    substitutedClasses.put(cls, walk);
                    break;
                }
                walk = modSuper.get(walk);
            }
        }
    }

    /** Whether the class is, or descends from, the ancestor -- across mod and platform. */
    private boolean inheritsFrom(String cls, String ancestor) {
        String walk = cls;
        Set<String> seen = new HashSet<>();
        while (walk != null && seen.add(walk)) {
            if (walk.equals(ancestor)) return true;
            String platform = targetSuper.get(walk);
            walk = platform != null ? platform : modSuper.get(walk);
        }
        return false;
    }

    /**
     * The nearest supertype declaring this member final, or null.
     *
     * Walks the mod's own classes as well as the platform's, which it did not at first and had to.
     * A mod class almost never extends a vanilla class directly -- ars_nouveau's
     * {@code EntityProjectileSpell} reaches {@code Entity} through two of its own classes -- and a
     * walk that only knew platform supertypes stopped at the first mod class and found nothing.
     * That silently missed 38 of the 70 verification errors in one mod.
     */
    private String finalOwnerOf(String superName, String member) {
        String cls = superName;
        Set<String> seen = new HashSet<>();
        while (cls != null && seen.add(cls)) {
            Set<String> fin = targetFinalMethods.get(cls);
            if (fin != null && fin.contains(member)) return cls;
            String platform = targetSuper.get(cls);
            cls = platform != null ? platform : modSuper.get(cls);
        }
        return null;
    }

    /**
     * What to cast an unwrapped value to.
     *
     * The obvious answer -- whatever the corpus said the type was -- is wrong for the case that
     * matters most. {@code ArmorMaterials} was an *enum implementing* {@code ArmorMaterial} in
     * 1.20.1, so a mod reading {@code ArmorMaterials.IRON} has the descriptor
     * {@code Lnet/minecraft/world/item/ArmorMaterials;}. In 1.21 that class is a plain carrier of
     * {@code Holder<ArmorMaterial>} constants and is no longer an {@code ArmorMaterial} at all;
     * casting the unwrapped value back to it produces bytecode that fails verification with
     * "expected ArmorMaterial, but found ArmorMaterials".
     *
     * So the platform's own generic signature decides, and the corpus descriptor is only the
     * fallback for a member with no signature -- where it is also usually right, because a type
     * that was never an enum did not change identity when it got wrapped.
     */
    private String unwrappedType(String owner, String name, String corpusDesc) {
        String fromSignature = holderValueType.get(owner + "." + name);
        if (fromSignature != null) return fromSignature;
        return corpusDesc.substring(1, corpusDesc.length() - 1);
    }

    /**
     * The {@code T} in a {@code Lnet/minecraft/core/Holder&lt;LT;&gt;;} generic signature.
     *
     * Deliberately literal rather than a full signature parse: a nested or wildcard argument is
     * not something to guess at, so anything that is not a plain class type yields null and the
     * caller falls back.
     */
    private static String holderTypeArgument(String signature) {
        String open = "Lnet/minecraft/core/Holder<";
        int i = signature.indexOf(open);
        if (i < 0) return null;
        int start = i + open.length();
        if (start >= signature.length() || signature.charAt(start) != 'L') return null;
        int end = signature.indexOf(';', start);
        if (end < 0) return null;
        String inner = signature.substring(start + 1, end);
        // A nested generic argument (Holder<Foo<Bar>>) leaves a '<' inside; the erasure is still
        // just the outer name, so take it.
        int lt = inner.indexOf('<');
        if (lt >= 0) inner = inner.substring(0, lt);
        return inner.isEmpty() ? null : inner;
    }

    /**
     * Unwraps vanilla values that 1.21 started returning inside a {@code Holder}.
     *
     * The source half of the Holder boundary. A field read of {@code MobEffects.POISON} or a call
     * to {@code MobEffectInstance.getEffect()} now yields a {@code Holder}; this retypes the
     * reference and immediately unwraps, so what the mod's own bytecode receives is the
     * {@code MobEffect} it was compiled against.
     *
     * Both cases leave the value on top of the stack, which is the whole reason this half is
     * cheap: two instructions inserted directly after, no operand-stack analysis, no reordering.
     * The sink half is not so lucky -- see {@link #applyWrapAdapters}.
     *
     * Deliberately driven by the platform index rather than by a list of wrapped types. Any
     * member whose descriptor differs from the corpus's only by a {@code Holder} in the value
     * position is one of these, and asking the platform means the set never needs updating when
     * a later NeoForge build wraps something else.
     */
    private void applyHolderUnwrap(InsnList insns) {
        if (targetMembers.isEmpty()) return;
        for (AbstractInsnNode insn : insns.toArray()) {
            if (insn instanceof FieldInsnNode fin) {
                if (!fin.owner.startsWith("net/minecraft/")) continue;
                if (fin.getOpcode() != Opcodes.GETSTATIC && fin.getOpcode() != Opcodes.GETFIELD) continue;
                if (!fin.desc.startsWith("L") || fin.desc.equals(HOLDER_DESC)) continue;
                Set<String> members = resolvedTargetMembers(fin.owner);
                if (members.isEmpty()) continue;
                if (members.contains(fin.name + " " + fin.desc)) continue;
                if (members.contains(fin.name + " " + HOLDER_DESC)) {
                    String valueType = unwrappedType(fin.owner, fin.name, fin.desc);
                    fin.desc = HOLDER_DESC;
                    insns.insert(fin, new TypeInsnNode(Opcodes.CHECKCAST, valueType));
                    insns.insert(fin, new MethodInsnNode(Opcodes.INVOKEINTERFACE, HOLDER,
                            "value", "()Ljava/lang/Object;", true));
                    count(appliedCounts, "HOLDER_UNWRAP field " + fin.owner);
                    continue;
                }

                // Any other declared conversion, same as for returns. 1.21 re-typed whole
                // families of constants without wrapping them in a Holder --
                // BuiltInLootTables went from ResourceLocation to ResourceKey -- and the fix is
                // the same shape: read the new type, convert back to what the mod expects.
                // Sorted: members is a HashSet, so two candidates would otherwise be chosen
                // between by hash order, and a translator has to emit the same bytes every run.
                String want = fin.desc.substring(1, fin.desc.length() - 1);
                for (String candidate : new java.util.TreeSet<>(members)) {
                    int sp = candidate.indexOf(' ');
                    if (sp != fin.name.length() || !candidate.startsWith(fin.name)) continue;
                    String actual = candidate.substring(sp + 1);
                    if (!actual.startsWith("L") || !actual.endsWith(";")) continue;
                    RenameRule via = declaredCoercion(
                            actual.substring(1, actual.length() - 1), want);
                    if (via == null) continue;
                    fin.desc = actual;
                    insns.insert(fin, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            via.newOwner(), via.newName(), via.newDesc(), false));
                    count(appliedCounts, "COERCE field " + fin.owner + "." + fin.name
                                        + " -> " + want);
                    break;
                }
            } else if (insn instanceof MethodInsnNode min) {
                if (!min.owner.startsWith("net/minecraft/")) continue;
                if (min.name.equals("<init>")) continue;
                Type ret = Type.getReturnType(min.desc);
                if (ret.getSort() != Type.OBJECT || ret.getInternalName().equals(HOLDER)) continue;
                Set<String> members = resolvedTargetMembers(min.owner);
                if (members.isEmpty()) continue;
                if (members.contains(min.name + " " + min.desc)) continue;
                String args = min.desc.substring(0, min.desc.indexOf(')') + 1);
                String holderDesc = args + HOLDER_DESC;
                if (members.contains(min.name + " " + holderDesc)) {
                    String valueType = unwrappedType(min.owner, min.name, ret.getDescriptor());
                    min.desc = holderDesc;
                    insns.insert(min, new TypeInsnNode(Opcodes.CHECKCAST, valueType));
                    insns.insert(min, new MethodInsnNode(Opcodes.INVOKEINTERFACE, HOLDER,
                            "value", "()Ljava/lang/Object;", true));
                    count(appliedCounts, "HOLDER_UNWRAP return " + min.owner + "." + min.name);
                    continue;
                }

                // Any other declared conversion, not just Holder. 1.21 changed a number of
                // returns to a wrapper of some kind -- BuiltInLootTables.register went from
                // returning a ResourceLocation to a ResourceKey -- and the shape of the fix is
                // identical: take the new descriptor and convert the result back to what the
                // mod's own bytecode expects. Only Holder was handled at first, which left the
                // whole ResourceKey family failing at the call site with NoSuchMethodError.
                for (String candidate : new java.util.TreeSet<>(members)) {
                    int sp = candidate.indexOf(' ');
                    if (sp != min.name.length() || !candidate.startsWith(min.name)) continue;
                    String desc = candidate.substring(sp + 1);
                    if (!desc.startsWith(args)) continue;
                    Type newRet = Type.getReturnType(desc);
                    if (newRet.getSort() != Type.OBJECT) continue;
                    RenameRule via = declaredCoercion(newRet.getInternalName(),
                                                      ret.getInternalName());
                    if (via == null) continue;
                    min.desc = desc;
                    insns.insert(min, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            via.newOwner(), via.newName(), via.newDesc(), false));
                    count(appliedCounts, "COERCE return of " + min.owner + "." + min.name
                                        + " -> " + ret.getInternalName());
                    break;
                }
            }
        }
    }

    /**
     * Collapses several arguments into one, where 1.21 folded them into another parameter.
     *
     * `PickaxeItem(Tier, int, float, Properties)` became `PickaxeItem(Tier, Properties)`: attack
     * damage and speed are a component on the properties now. Four parameters became two, which
     * is past {@code ARG_DROP} -- that handles one at a time -- and past {@code CTOR_TO_STATIC},
     * which needs a NEW/DUP pair to rewrite and finds none, because a mod's own tool class calls
     * this as {@code super(...)}. That combination is why this needs a mechanism of its own.
     *
     * The rewrite spills every argument to a local, reloads the leading ones that did not change,
     * then calls a bridge with *all* the originals to produce the single replacement. Reloading
     * the originals after spilling is what makes it work: the bridge needs the `Tier` that the
     * constructor also still needs, and there is no way to reach a value twice on the stack.
     */
    private void applyArgCollapse(MethodNode method) {
        if (collapseRules.isEmpty() || method.instructions == null) return;
        InsnList insns = method.instructions;
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            CollapseRule rule = null;
            for (CollapseRule r : collapseRules) {
                if (r.owner().equals(min.owner) && r.name().equals(min.name)
                        && r.oldDesc().equals(min.desc)) { rule = r; break; }
            }
            if (rule == null) continue;

            Type[] have = Type.getArgumentTypes(rule.oldDesc());
            Type[] want = Type.getArgumentTypes(rule.newDesc());
            int keep = 0;
            while (keep < want.length - 1 && keep < have.length && have[keep].equals(want[keep])) {
                keep++;
            }

            InsnList fix = new InsnList();
            int base = method.maxLocals;
            int[] slot = new int[have.length];
            int next = base;
            for (int i = 0; i < have.length; i++) { slot[i] = next; next += have[i].getSize(); }
            for (int i = have.length - 1; i >= 0; i--) {
                fix.add(new VarInsnNode(have[i].getOpcode(Opcodes.ISTORE), slot[i]));
            }
            for (int i = 0; i < keep; i++) {
                fix.add(new VarInsnNode(have[i].getOpcode(Opcodes.ILOAD), slot[i]));
            }
            for (int i = 0; i < have.length; i++) {
                fix.add(new VarInsnNode(have[i].getOpcode(Opcodes.ILOAD), slot[i]));
            }
            fix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, rule.bridgeOwner(), rule.bridgeName(),
                    Type.getMethodDescriptor(want[want.length - 1], have), false));
            insns.insertBefore(min, fix);
            method.maxLocals = next;
            min.desc = rule.newDesc();
            count(appliedCounts, "ARG_COLLAPSE " + min.owner + "." + min.name);
        }
    }

    /**
     * Wraps mod values on their way into vanilla methods that now take a {@code Holder}.
     *
     * The sink half, and the awkward one. {@code new MobEffectInstance(effect, 100, 0)} needs the
     * first of three arguments wrapped, and by the time the call executes that value is buried two
     * slots down the operand stack. There is no instruction that reaches past the top.
     *
     * <h2>Spill and reload</h2>
     *
     * The arguments above the one being wrapped are stored into fresh locals, the wrap runs on
     * what is now the top of the stack, and they are pushed back in order:
     *
     * <pre>
     *   ..., effect, 100, 0        ISTORE d ; ISTORE c
     *   ..., effect                INVOKESTATIC HolderBridge.wrap
     *   ..., Holder                ILOAD c ; ILOAD d
     *   ..., Holder, 100, 0        INVOKESPECIAL &lt;init&gt;(Holder,I,I)V
     * </pre>
     *
     * Correct without any data-flow analysis, because the descriptor already says exactly what is
     * on the stack and in what order. Slot sizes come from {@code Type.getSize()}, so a long or
     * double argument spills and reloads as one value rather than being torn in half.
     *
     * <h2>Why not a generated adapter</h2>
     *
     * An earlier version redirected the call to a synthesised static method taking the arguments
     * in their existing order, which needs no stack surgery at all. It is a genuinely neater
     * rewrite and it cannot express the case that matters most: {@code super(...)}. geckolib's
     * {@code WolfArmorItem} extends {@code ArmorItem} and calls its changed constructor with no
     * NEW/DUP to redirect and an uninitialised {@code this} beneath the arguments. That is not an
     * edge case -- subclassing a vanilla type whose constructor was rewrapped is the single
     * commonest shape of this problem, and it is exactly what the previous approach to
     * {@code ArmorMaterials} could not reach.
     *
     * <h2>Matching</h2>
     *
     * A platform member is a candidate when it has the same name and arity, every parameter either
     * matches exactly or is a {@code Holder} where the corpus passes an object, and at least one
     * parameter is such a substitution. <b>An ambiguous match is skipped and reported.</b> Vanilla
     * overloads heavily, and guessing between two candidates produces a translation that links
     * and calls the wrong method.
     */
    private void applyWrapAdapters(MethodNode method) {
        if (targetMembers.isEmpty()) return;
        InsnList insns = method.instructions;
        for (AbstractInsnNode insn : insns.toArray()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            if (!min.owner.startsWith("net/minecraft/")) continue;
            if (min.getOpcode() == Opcodes.INVOKEDYNAMIC) continue;
            // Constructors are not inherited, so they must be judged against what the owner
            // itself declares. Folding supertypes in made DropExperienceBlock(Properties) look
            // resolvable -- Block declares that constructor -- so the call was skipped as fine
            // and failed at load with NoSuchMethodError. Every other member resolves through the
            // hierarchy and must keep doing so.
            boolean ctor = min.name.equals("<init>");
            Set<String> members = ctor ? targetMembers.getOrDefault(min.owner, Set.of())
                                       : resolvedTargetMembers(min.owner);
            if (members.isEmpty()) continue;
            if (members.contains(min.name + " " + min.desc)) continue;

            // Same arity wins outright over an overload that adds or drops a parameter.
            //
            // Without that precedence, AttributeSupplier$Builder.add(Attribute, double) matched
            // both add(Holder, double) -- the obvious answer -- and add(Holder), by dropping the
            // double. Two candidates is ambiguous, so the call was correctly refused and geckolib
            // regressed from loading to NoSuchMethodError. Adding arity matching quietly took
            // something away that had been working, which is the shape of regression this project
            // has to re-run the whole batch to catch.
            int arity = Type.getArgumentTypes(min.desc).length;
            String match = null;
            int candidates = 0;
            for (int pass = 0; pass < 2 && candidates == 0; pass++) {
                for (String m : members) {
                    int sp = m.indexOf(' ');
                    if (sp < 0 || sp != min.name.length() || !m.startsWith(min.name)) continue;
                    String cand = m.substring(sp + 1);
                    if (!cand.startsWith("(")) continue;          // a field of the same name
                    boolean sameArity = Type.getArgumentTypes(cand).length == arity;
                    if (sameArity != (pass == 0)) continue;
                    if (!wrapCompatible(min.desc, cand)) continue;
                    candidates++;
                    match = cand;
                }
            }
            if (candidates == 0) continue;
            if (candidates > 1) {
                count(unresolved, "HOLDER_WRAP ambiguous: " + min.owner + "." + min.name + min.desc);
                continue;
            }

            Type[] have = Type.getArgumentTypes(min.desc);
            Type[] want = Type.getArgumentTypes(match);

            // A parameter 1.21 dropped. Removed first, for the same reason insertion happens
            // first: the coercion loop below then sees two lists of equal length.
            if (have.length == want.length + 1) {
                int k = removalPoint(have, want);
                if (k < 0) {
                    count(unresolved, "ARG_DROP ambiguous position: " + min.owner + "." + min.name);
                    continue;
                }
                InsnList fix = new InsnList();
                int base = method.maxLocals;
                int slot = base;
                for (int j = have.length - 1; j > k; j--) {
                    fix.add(new VarInsnNode(have[j].getOpcode(Opcodes.ISTORE), slot));
                    slot += have[j].getSize();
                }
                fix.add(new InsnNode(have[k].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
                for (int j = k + 1; j < have.length; j++) {
                    slot -= have[j].getSize();
                    fix.add(new VarInsnNode(have[j].getOpcode(Opcodes.ILOAD), slot));
                }
                insns.insertBefore(min, fix);
                method.maxLocals = base + Arrays.stream(have, k + 1, have.length)
                                                .mapToInt(Type::getSize).sum();
                count(appliedCounts, "ARG_DROP " + have[k].getInternalName()
                                    + " from " + min.owner + "." + min.name);
                Type[] narrowed = new Type[want.length];
                System.arraycopy(have, 0, narrowed, 0, k);
                System.arraycopy(have, k + 1, narrowed, k, have.length - k - 1);
                have = narrowed;
                min.desc = Type.getMethodDescriptor(Type.getReturnType(min.desc), narrowed);
            }

            // A parameter 1.21 added. Inserted first, so the coercion loop below then sees two
            // argument lists of the same length and needs no special case.
            if (have.length + 1 == want.length) {
                int k = insertionPoint(have, want);
                if (k < 0) {
                    count(unresolved, "ARG_FILL ambiguous position: " + min.owner + "." + min.name);
                    continue;
                }
                RenameRule filler = argFillers.get(want[k].getInternalName());
                InsnList fix = new InsnList();
                int base = method.maxLocals;
                int slot = base;
                // Everything above the insertion point spills, exactly as for a coercion: the
                // new argument has to arrive at position k, and the stack only has a top.
                for (int j = have.length - 1; j >= k; j--) {
                    fix.add(new VarInsnNode(have[j].getOpcode(Opcodes.ISTORE), slot));
                    slot += have[j].getSize();
                }
                fix.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        filler.newOwner(), filler.newName(), filler.newDesc(), false));
                for (int j = k; j < have.length; j++) {
                    slot -= have[j].getSize();
                    fix.add(new VarInsnNode(have[j].getOpcode(Opcodes.ILOAD), slot));
                }
                insns.insertBefore(min, fix);
                method.maxLocals = base + Arrays.stream(have, k, have.length)
                                                .mapToInt(Type::getSize).sum();
                count(appliedCounts, "ARG_FILL " + want[k].getInternalName()
                                    + " into " + min.owner + "." + min.name);
                Type[] widened = new Type[want.length];
                System.arraycopy(have, 0, widened, 0, k);
                widened[k] = want[k];
                System.arraycopy(have, k, widened, k + 1, have.length - k);
                have = widened;
                min.desc = Type.getMethodDescriptor(Type.getReturnType(min.desc), widened);
            }

            // Highest-indexed argument needing a coercion. Everything above it must spill;
            // everything below it never moves, so working from the top down keeps each step's
            // spill set as small as it can be.
            for (int i = have.length - 1; i >= 0; i--) {
                if (have[i].equals(want[i])) continue;
                int base = method.maxLocals;
                InsnList fix = new InsnList();
                int slot = base;
                for (int j = have.length - 1; j > i; j--) {
                    fix.add(new VarInsnNode(have[j].getOpcode(Opcodes.ISTORE), slot));
                    slot += have[j].getSize();
                }
                RenameRule via = coercion(have[i], want[i]);
                fix.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        via.newOwner(), via.newName(), via.newDesc(), false));
                // Reload in the mirror order of the spill, so the operand stack is rebuilt
                // exactly as it was rather than reversed.
                for (int j = i + 1; j < have.length; j++) {
                    slot -= have[j].getSize();
                    fix.add(new VarInsnNode(have[j].getOpcode(Opcodes.ILOAD), slot));
                }
                insns.insertBefore(min, fix);
                method.maxLocals = base + Arrays.stream(have, i + 1, have.length)
                                               .mapToInt(Type::getSize).sum();
                count(appliedCounts, "HOLDER_WRAP " + min.owner + "." + min.name);
            }
            min.desc = match;
        }
    }

    /**
     * Inserts coercions where a value's *type* stopped being acceptable, though the call did not
     * change.
     *
     * <h2>Why descriptors are not enough</h2>
     *
     * {@link #applyWrapAdapters} finds calls whose descriptor no longer resolves. This finds the
     * opposite and harder case, where the descriptor is untouched and the value flowing into it
     * is wrong. {@code ModelResourceLocation} extended {@code ResourceLocation} in 1.20.1, so a
     * mod passing one to something taking a resource location compiled to a call site that still
     * reads perfectly: the parameter says {@code ResourceLocation} and always did. 1.21 made
     * {@code ModelResourceLocation} a record that merely holds one, and now the same instruction
     * fails verification while every descriptor involved still matches.
     *
     * Nothing static about the call site reveals that. The only thing that does is what the
     * verifier does -- follow the value.
     *
     * <h2>How</h2>
     *
     * The method is analysed with a verifier made deliberately lenient: it accepts a value of the
     * old type where the new one is expected, but only for pairs a {@code COERCE} rule declares.
     * That lets the analysis complete instead of stopping at the first mismatch, and the frames
     * it produces then say what is really on the stack at every call. Each argument is compared
     * against the declared parameter, and a declared coercion is inserted where the two disagree.
     *
     * Analysis is not cheap, so a method is only analysed when its instructions actually mention
     * a type some rule can coerce -- which for most methods in most mods is never.
     */
    private void applyValueCoercions(ClassNode owner, MethodNode method, ClassLoader loader) {
        if (coercions.isEmpty() || method.instructions == null) return;
        if (!mentionsCoercibleType(method)) return;

        org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.BasicValue>[] frames;
        try {
            var verifier = new LenientVerifier(owner, this::lenientPair);
            verifier.setClassLoader(loader);
            frames = new org.objectweb.asm.tree.analysis.Analyzer<>(verifier)
                    .analyze(owner.name, method);
        } catch (Throwable t) {
            // A method this analysis cannot complete is left exactly as it was. Guessing from a
            // partial frame set is how a transformer inserts a conversion in the wrong place.
            if (System.getenv("EASYPORT_DEBUG") != null) t.printStackTrace();
            count(unresolved, "COERCE analysis failed (" + t.getClass().getSimpleName() + ": "
                            + t.getMessage() + "): " + owner.name + "." + method.name);
            return;
        }

        AbstractInsnNode[] insns = method.instructions.toArray();

        // Returns first, and they are the easy half: the value is already on top of the stack, so
        // the conversion goes straight in front of the ARETURN with no spill. A mod method
        // declared to return Ingredient that returns its own custom ingredient needs exactly
        // this, and handling only arguments left it failing with the same error in a new place.
        Type declaredReturn = Type.getReturnType(method.desc);
        if (declaredReturn.getSort() == Type.OBJECT) {
            for (int i = 0; i < insns.length; i++) {
                if (insns[i].getOpcode() != Opcodes.ARETURN || frames[i] == null) continue;
                var top = frames[i].getStack(frames[i].getStackSize() - 1);
                if (top == null || top.getType() == null) continue;
                if (top.getType().getSort() != Type.OBJECT) continue;
                if (top.getType().equals(declaredReturn)) continue;
                RenameRule via = declaredCoercion(top.getType().getInternalName(),
                                                  declaredReturn.getInternalName());
                if (via != null) {
                    method.instructions.insertBefore(insns[i], new MethodInsnNode(
                            Opcodes.INVOKESTATIC, via.newOwner(), via.newName(), via.newDesc(),
                            false));
                    count(appliedCounts, "COERCE-marker");
                    count(appliedCounts, "COERCE return -> " + declaredReturn.getInternalName());
                    applyValueCoercions(owner, method, loader);
                    return;
                }
                // The value is a *merge* of two branches, so its type at the return is whatever
                // the two have in common -- which, now that the custom type is no longer an
                // Ingredient, is Object. Converting here is impossible: there is no single value
                // to convert, and one branch already holds the right type.
                //
                // So the conversion moves to the branch that needs it. A source analysis says
                // which instructions produced the merged value, and each is checked on its own.
                if (coerceAtProducers(owner, method, insns, i, declaredReturn, loader)) return;
            }
        }

        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof MethodInsnNode call) || frames[i] == null) continue;
            Type[] params = Type.getArgumentTypes(call.desc);
            if (params.length == 0) continue;
            var frame = frames[i];
            for (int p = params.length - 1; p >= 0; p--) {
                // Arguments sit at the top of the frame in declaration order, so the last
                // parameter is nearest the top. Counting back from the top gives the slot.
                int depth = 0;
                for (int q = p + 1; q < params.length; q++) depth += params[q].getSize();
                var value = frame.getStack(frame.getStackSize() - depth - params[p].getSize());
                if (value == null || value.getType() == null) continue;
                if (value.getType().getSort() != Type.OBJECT) continue;
                if (value.getType().equals(params[p])) continue;
                // Must go through the substitution-aware lookup, not the raw map. A mod class
                // whose implements clause was substituted has a name no rule mentions, and
                // reading the map directly silently matched nothing for exactly the case the
                // substitution exists to serve.
                RenameRule via = declaredCoercion(value.getType().getInternalName(),
                                                  params[p].getInternalName());
                if (via == null) continue;

                InsnList fix = new InsnList();
                int base = method.maxLocals;
                int slot = base;
                for (int q = params.length - 1; q > p; q--) {
                    fix.add(new VarInsnNode(params[q].getOpcode(Opcodes.ISTORE), slot));
                    slot += params[q].getSize();
                }
                fix.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        via.newOwner(), via.newName(), via.newDesc(), false));
                for (int q = p + 1; q < params.length; q++) {
                    slot -= params[q].getSize();
                    fix.add(new VarInsnNode(params[q].getOpcode(Opcodes.ILOAD), slot));
                }
                method.instructions.insertBefore(call, fix);
                method.maxLocals = base + Arrays.stream(params, p + 1, params.length)
                                                .mapToInt(Type::getSize).sum();
                count(appliedCounts, "COERCE " + via.owner() + " -> " + params[p].getInternalName());
                // One insertion invalidates the frames this loop is reading, so the method is
                // re-analysed from scratch rather than patched further against stale ones.
                count(appliedCounts, "COERCE-marker");
                applyValueCoercions(owner, method, loader);
                return;
            }
        }
    }

    /**
     * Runs the coercion pass over every translated class, once all of them exist.
     *
     * <h2>Why this cannot run inline</h2>
     *
     * The analysis needs to load the classes it reasons about, and the only version of them that
     * can be loaded is the translated one. Pointed at the input jar it tries to define
     * {@code AquaArmorMaterials implements ArmorMaterial} against a 1.21 platform where that is a
     * record, and fails with the very {@code IncompatibleClassChangeError} the substitution
     * exists to prevent -- so every method needing a coercion was skipped, for a reason that read
     * like the fix had not worked.
     *
     * Holding the translated classes in memory and serving them to the verifier is what makes the
     * question answerable: by this point {@code AquaArmorMaterials} implements
     * {@code easyport.vanilla.ArmorMaterial}, which is a real interface, and the hierarchy links.
     */
    private void runCoercionPass() {
        if (coercions.isEmpty() || transformedClasses.isEmpty()) return;
        ClassLoader loader = new TranslatedClassLoader(transformedClasses, coercionLoader());
        for (var entry : transformedClasses.entrySet()) {
            ClassNode node = new ClassNode();
            try {
                new ClassReader(entry.getValue()).accept(node, 0);
            } catch (Exception e) {
                continue;
            }
            boolean changed = false;
            for (MethodNode m : node.methods) {
                if (m.instructions == null) continue;
                int before = appliedCounts.getOrDefault("COERCE-marker", 0);
                applyValueCoercions(node, m, loader);
                if (appliedCounts.getOrDefault("COERCE-marker", 0) != before) changed = true;
            }
            if (changed) {
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                entry.setValue(writer.toByteArray());
            }
        }
        appliedCounts.remove("COERCE-marker");
    }

    /** Serves the in-progress translated classes to the coercion analysis, platform behind. */
    private static final class TranslatedClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        TranslatedClassLoader(Map<String, byte[]> classes, ClassLoader parent) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] data = classes.get(name.replace('.', '/') + ".class");
            if (data == null) throw new ClassNotFoundException(name);
            return defineClass(name, data, 0, data.length);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // Translated classes win over the parent. Without this the platform's copy of a
            // vanilla-named class the mod also carries would shadow the translated one, which is
            // the whole point of serving these at all.
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    if (classes.containsKey(name.replace('.', '/') + ".class")) {
                        found = findClass(name);
                    } else {
                        return super.loadClass(name, resolve);
                    }
                }
                if (resolve) resolveClass(found);
                return found;
            }
        }
    }

    /**
     * The declared conversion from one type to another, following an interface substitution.
     *
     * The second lookup is what lets a mod's own class participate: {@code AquaArmorMaterials}
     * appears in no rule, but its implements clause was rewritten to
     * {@code easyport.vanilla.ArmorMaterial}, and that type does have a conversion.
     */
    private RenameRule declaredCoercion(String have, String want) {
        RenameRule exact = coercions.get(have + "\t" + want);
        if (exact != null) return exact;
        String via = substitutedClasses.get(have);
        return via == null ? null : coercions.get(via + "\t" + want);
    }

    private boolean canCoerce(String have, String want) {
        return declaredCoercion(have, want) != null;
    }

    /**
     * What the analysis tolerates, which is deliberately wider than what it will act on.
     *
     * A branch merge collapses to {@code Object} once one side stops being assignable to the
     * other, and the analyser checks the return type itself -- so it threw at the return and
     * produced no frames at all, leaving nothing to diagnose the merge from. The pass could see
     * the error and never see the method.
     *
     * Accepting {@code Object} where a coercion target is expected costs nothing, because this
     * pass only ever *adds* conversions and the sweep re-verifies afterwards with a real
     * verifier. A leniency that turns out to be wrong shows up as an unfixed finding, never as
     * bad bytecode.
     */
    private boolean lenientPair(String have, String want) {
        if (canCoerce(have, want)) return true;
        if (!have.equals("java/lang/Object")) return false;
        for (String pair : coercions.keySet()) {
            if (pair.endsWith("\t" + want)) return true;
        }
        return false;
    }

    /**
     * A cheap gate, because the analysis is not cheap and most methods have nothing to find.
     *
     * Matches both the declared coercion sources and the mod's own substituted classes. Missing
     * the second set would skip exactly the methods the substitution was performed for: a custom
     * armour material's own name never appears in a COERCE rule, and it is the only type that
     * shows up at the call sites that now need converting.
     */
    /**
     * Converts each branch that feeds a merged value, where converting the merge itself cannot
     * work.
     *
     * {@code cond ? new PerkTierIngredient(...) : Ingredient.of(tag)} leaves the two branches with
     * no common type but {@code Object} once the custom ingredient stops being an
     * {@code Ingredient}. There is nothing at the return to convert -- one branch is already
     * right, and the other is not reachable from there.
     *
     * A {@code SourceInterpreter} answers which instructions produced the value, and the type
     * analysis says what each of them left on the stack. The conversion then goes immediately
     * after the producer that needs it, where the value is unambiguous and on top of the stack.
     *
     * @return whether anything was inserted, in which case the caller must re-analyse
     */
    private boolean coerceAtProducers(
            ClassNode owner, MethodNode method, AbstractInsnNode[] insns, int at,
            Type want, ClassLoader loader) {
        org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.BasicValue>[] types;
        try {
            var verifier = new LenientVerifier(owner, this::lenientPair);
            verifier.setClassLoader(loader);
            types = new org.objectweb.asm.tree.analysis.Analyzer<>(verifier)
                    .analyze(owner.name, method);
        } catch (Throwable t) {
            return false;
        }

        // The end of a branch, not the instruction that built the value. Tracing back to the
        // producer lands on the NEW/DUP pair, where the object does not exist yet and nothing can
        // be called on it. The jump into the merge is the last point where the branch's value is
        // unambiguous and on top of the stack, which is exactly what a conversion needs.
        //
        // Scoped to a method already known to fail at a return of `want`, so this cannot fire on
        // a value that was merely passing through.
        for (int i = 0; i < insns.length; i++) {
            if (insns[i].getOpcode() != Opcodes.GOTO || types[i] == null) continue;
            if (types[i].getStackSize() == 0) continue;
            var top = types[i].getStack(types[i].getStackSize() - 1);
            if (top == null || top.getType() == null) continue;
            if (top.getType().getSort() != Type.OBJECT) continue;
            RenameRule via = declaredCoercion(top.getType().getInternalName(),
                                              want.getInternalName());
            if (via == null) continue;
            method.instructions.insertBefore(insns[i], new MethodInsnNode(
                    Opcodes.INVOKESTATIC, via.newOwner(), via.newName(), via.newDesc(), false));
            count(appliedCounts, "COERCE-marker");
            count(appliedCounts, "COERCE at branch -> " + want.getInternalName());
            applyValueCoercions(owner, method, loader);
            return true;
        }
        return false;
    }

    private boolean mentionsCoercibleType(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            String probe = insn instanceof MethodInsnNode m ? m.owner + m.desc
                         : insn instanceof TypeInsnNode t ? t.desc
                         : insn instanceof FieldInsnNode f ? f.owner + f.desc : null;
            if (probe == null) continue;
            for (String pair : coercions.keySet()) {
                if (probe.contains(pair.substring(0, pair.indexOf('\t')))) return true;
            }
            for (String substituted : substitutedClasses.keySet()) {
                if (probe.contains(substituted)) return true;
            }
        }
        return false;
    }

    /** Classpath for the coercion analysis: the platform jars, loaded once. */
    private ClassLoader coercionLoader() {
        if (coercionLoader == null) {
            List<java.net.URL> urls = new ArrayList<>();
            // The mod's own jar first. SimpleVerifier computes subtype relations by loading
            // classes, and a mod class it cannot load fails the whole analysis -- which is what
            // happened on every one of the nine methods this pass first identified.
            if (inputJar != null) {
                try { urls.add(inputJar.toUri().toURL()); } catch (Exception ignored) { }
            }
            for (String jar : platformJarPaths) {
                try {
                    Path p = Paths.get(jar);
                    if (Files.isRegularFile(p)) urls.add(p.toUri().toURL());
                } catch (Exception ignored) {
                    // A jar that will not resolve just makes the analysis less able to answer,
                    // which the caller already treats as "leave this method alone".
                }
            }
            coercionLoader = new java.net.URLClassLoader(urls.toArray(new java.net.URL[0]),
                    Translate.class.getClassLoader());
        }
        return coercionLoader;
    }

    private ClassLoader coercionLoader;
    private Path inputJar;

    /**
     * A verifier that tolerates exactly the type mismatches a {@code COERCE} rule knows how to fix.
     *
     * A named class rather than an anonymous one because it has to be. {@code SimpleVerifier}'s
     * public constructor throws {@code IllegalStateException} when the instance is not exactly a
     * {@code SimpleVerifier} -- ASM's guard against subclasses skipping the API version -- and
     * only {@code super(...)} can reach the protected constructor that accepts one. The anonymous
     * version compiled cleanly and failed at run time on all nine methods this pass exists for.
     *
     * The leniency is narrow on purpose. Accepting every mismatch would let the analysis finish
     * on genuinely broken bytecode, and this pass would then insert nothing and report nothing.
     */
    private static final class LenientVerifier
            extends org.objectweb.asm.tree.analysis.SimpleVerifier {

        private final java.util.function.BiPredicate<String, String> allowed;

        LenientVerifier(ClassNode owner, java.util.function.BiPredicate<String, String> allowed) {
            super(Opcodes.ASM9,
                  Type.getObjectType(owner.name),
                  owner.superName == null ? null : Type.getObjectType(owner.superName),
                  owner.interfaces.stream().map(Type::getObjectType).toList(),
                  (owner.access & Opcodes.ACC_INTERFACE) != 0);
            this.allowed = allowed;
        }

        @Override
        protected boolean isSubTypeOf(org.objectweb.asm.tree.analysis.BasicValue value,
                                      org.objectweb.asm.tree.analysis.BasicValue expected) {
            if (super.isSubTypeOf(value, expected)) return true;
            return value.getType() != null && expected.getType() != null
                && value.getType().getSort() == Type.OBJECT
                && expected.getType().getSort() == Type.OBJECT
                && allowed.test(value.getType().getInternalName(),
                                expected.getType().getInternalName());
        }

        /**
         * Assignability, degrading to "yes" for a type this classpath cannot resolve.
         *
         * A mod's dependencies are usually not present when it is translated -- aquaculture's
         * armour code touches geckolib -- and SimpleVerifier answers an unresolvable type by
         * throwing, which fails the whole method rather than the one query. That skipped exactly
         * the methods the ArmorMaterial substitution had just been built for.
         *
         * Assuming compatible is safe here because it can only cause a coercion to be *missed*,
         * never inserted wrongly: insertions are driven by an explicit type-pair lookup against
         * the rules, not by this answer. Same degradation the rename-target index uses, and for
         * the same reason -- a check that is confidently wrong about what it cannot see does more
         * damage than one that declines to answer.
         */
        @Override
        protected boolean isAssignableFrom(Type target, Type value) {
            try {
                return super.isAssignableFrom(target, value);
            } catch (Throwable unresolvable) {
                return true;
            }
        }
    }
    private String[] platformJarPaths = new String[0];

    /**
     * Whether {@code target} is {@code called} with some arguments coerced.
     *
     * Requires at least one actual substitution, so an unrelated overload that happens to differ
     * only in return type is not treated as a match. Return types must agree exactly: a return
     * that also changed is the {@link #applyHolderUnwrap} case, which runs first and would have
     * handled it.
     */
    private boolean wrapCompatible(String called, String target) {
        Type[] a = Type.getArgumentTypes(called);
        Type[] b = Type.getArgumentTypes(target);
        if (!Type.getReturnType(called).equals(Type.getReturnType(target))) return false;
        if (a.length == b.length) {
            boolean substituted = false;
            for (int i = 0; i < a.length; i++) {
                if (a[i].equals(b[i])) continue;
                if (coercion(a[i], b[i]) == null) return false;
                substituted = true;
            }
            return substituted;
        }
        if (a.length + 1 == b.length) return insertionPoint(a, b) >= 0;
        return a.length == b.length + 1 && removalPoint(a, b) >= 0;
    }

    /**
     * Which argument 1.21 dropped, if exactly one position explains the difference.
     *
     * The mirror of {@link #insertionPoint}, and it arises from the same migration seen from the
     * other side: {@code ParticleType(boolean, Deserializer)} became {@code ParticleType(boolean)}
     * because serialization moved from a constructor argument to abstract methods. The value the
     * call site computes is simply no longer wanted.
     *
     * Ambiguity is refused for the same reason. Unlike an insertion, a wrong removal cannot even
     * be caught by the verifier -- both alignments type-check whenever the arguments happen to
     * share a type.
     *
     * @return the index of the removed parameter, or -1
     */
    private int removalPoint(Type[] have, Type[] want) {
        int found = -1;
        for (int k = 0; k < have.length; k++) {
            boolean matches = true;
            for (int i = 0, j = 0; j < want.length && matches; i++, j++) {
                if (i == k) i++;
                matches = i < have.length
                       && (have[i].equals(want[j]) || coercion(have[i], want[j]) != null);
            }
            if (!matches) continue;
            if (found >= 0) return -1;
            found = k;
        }
        return found;
    }

    /**
     * Where 1.21 inserted a parameter, if exactly one position explains the difference.
     *
     * A large share of the remaining vanilla drift is a signature that grew an argument rather
     * than changing one -- a {@code HolderLookup.Provider} for registry access, a
     * {@code StreamCodec} where a hand-written reader used to do. The call site cannot supply it,
     * because in 1.20.1 there was nothing to supply, so the value comes from a declared
     * {@code ARG_FILL} bridge.
     *
     * Ambiguity is refused rather than guessed. If two positions both explain the new signature
     * the call is left alone and reported, because inserting in the wrong one produces a call that
     * links and passes the arguments to the wrong parameters -- silently, and only wrong at
     * runtime.
     *
     * @return the index of the inserted parameter, or -1
     */
    private int insertionPoint(Type[] have, Type[] want) {
        int found = -1;
        for (int k = 0; k < want.length; k++) {
            if (!argFillers.containsKey(want[k].getInternalName())) continue;
            boolean matches = true;
            for (int i = 0, j = 0; i < have.length && matches; i++, j++) {
                if (j == k) j++;
                matches = have[i].equals(want[j]) || coercion(have[i], want[j]) != null;
            }
            if (!matches) continue;
            if (found >= 0) return -1;          // ambiguous
            found = k;
        }
        return found;
    }

    /**
     * The bridge call that turns a value of {@code have} into one of {@code want}, or null.
     *
     * Two sources. Holder wrapping is built in and untyped on the way in, because 1.21 wrapped a
     * whole family of registry types and enumerating them would be one chance per type to miss
     * one. Everything else is declared by a {@code COERCE} rule, because those are one-off shape
     * changes with no family behind them and no way to recognise one from its descriptor alone.
     *
     * The {@code COERCE} case exists because of {@code ModelResourceLocation}, which stopped being
     * a {@code ResourceLocation} subclass and became a record wrapping one. Every mod that passes
     * a model location to something taking a resource location breaks, and it broke four of the
     * twenty-two mods in the sweep -- the largest single remaining cause. Structurally it is the
     * same problem as Holder wrapping, a value that needs converting at the vanilla boundary, so
     * it uses the same spill-and-reload machinery rather than a mechanism of its own.
     */
    private RenameRule coercion(Type have, Type want) {
        if (have.getSort() != Type.OBJECT || want.getSort() != Type.OBJECT) return null;
        if (want.getInternalName().equals(HOLDER)) {
            return new RenameRule(null, null, null, HOLDER_BRIDGE, "wrap",
                    "(Ljava/lang/Object;)" + HOLDER_DESC);
        }
        RenameRule exact = coercions.get(have.getInternalName() + "\t" + want.getInternalName());
        if (exact != null) return exact;

        // A mod's *own* class whose implements clause was substituted. AquaArmorMaterials no
        // longer implements ArmorMaterial -- it implements easyport.vanilla.ArmorMaterial -- so
        // passing one where vanilla wants the record needs the same conversion the substitute
        // itself does, and the value's static type is a name no rule could have been written
        // against. This is why the substitutions are collected in a pass over the whole jar
        // before any class is rewritten: the class that *uses* AquaArmorMaterials is very often
        // read out of the zip before AquaArmorMaterials itself.
        String via = substitutedClasses.get(have.getInternalName());
        return via == null ? null : coercions.get(via + "\t" + want.getInternalName());
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
                    byte[] mixinBytes = read(zip, mixinEntry);
                    for (String target : mixinTargets(mixinBytes)) {
                        if (!target.startsWith("net/minecraft/")) continue;

                        if (!targetClasses.contains(target)) {
                            deadMixins.add(simple);
                            count(unresolved, "MIXIN_DROP (target gone: " + target + ") " + simple);
                            continue;
                        }

                        // A target that became an interface. Mixin refuses to apply a
                        // class-mixin to an interface target and aborts the whole launch --
                        // supermartijn642corelib dies on SpriteResourceLoader, which was a class
                        // in 1.20.1 and is an interface in 1.21. Applying it properly means
                        // rewriting the mixin as an interface mixin with default methods, which
                        // is real surgery; dropping keeps the mod loading and is reported.
                        if (targetInterfaces.contains(target) && !isInterface(mixinBytes)) {
                            deadMixins.add(simple);
                            count(unresolved, "MIXIN_DROP (target became an interface: "
                                              + target + ") " + simple);
                            continue;
                        }

                        // An unresolvable @Shadow used to drop the whole mixin class here. It is
                        // now handled per member by degradeShadowField / degradeShadowMethod,
                        // which turn the shadow into a mixin-added member instead: the class
                        // still applies and every injector in it still runs, where dropping lost
                        // all of them to fix one field. Nothing to do at class granularity.
                    }
                }
            } catch (Exception ignored) {
                // A config we cannot parse is left untouched rather than guessed at.
            }
        }
        if (!deadMixins.isEmpty()) reportOrphanedByDeadMixins(zip);
    }

    /**
     * Reports classes that only the dropped mixins referenced.
     *
     * Stripping a dead mixin keeps the mod loading, which is what it is for. It can also delete
     * content, silently, and that took a full trace through placebo to notice:
     *
     * <pre>
     *   LootTablesMixin  (dropped: LootDataManager removed in 1.21)
     *     -> LootSystem            referenced by nothing else in the jar
     *          -> StackLootEntry   static initialiser
     *               -> Registry.register(LOOT_POOL_ENTRY_TYPE, ...)
     * </pre>
     *
     * The registration sits in a static initialiser, so it only runs when something touches the
     * class, and the mixin was the only path in. Placebo translated cleanly, loaded cleanly, and
     * registered nothing -- no error anywhere.
     *
     * So: after deciding what to drop, walk what those mixins referenced and report anything the
     * rest of the jar never mentions. Not a proof of lost content -- a class can be reached
     * reflectively, or genuinely be mixin-only support code -- but it is the difference between
     * a warning and finding this by hand, once, per mod.
     */
    private void reportOrphanedByDeadMixins(ZipFile zip) {
        Set<String> referencedByDead = new LinkedHashSet<>();
        Set<String> referencedByLiving = new HashSet<>();
        Set<String> ownClasses = new HashSet<>();

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (!e.getName().endsWith(".class")) continue;
            String internal = e.getName().substring(0, e.getName().length() - 6);
            ownClasses.add(internal);
            String simple = internal.substring(internal.lastIndexOf('/') + 1);
            try {
                Set<String> refs = referencedClasses(read(zip, e));
                if (deadMixins.contains(simple)) {
                    referencedByDead.addAll(refs);
                } else {
                    // Self-references do not make a class reachable. Every class mentions itself
                    // -- its own methods, its own fields -- so counting those marked everything
                    // as live and the check reported nothing at all on the very mod it was
                    // written from.
                    refs.remove(internal);
                    referencedByLiving.addAll(refs);
                }
            } catch (Exception ignored) {
                // Unreadable class: cannot contribute either way.
            }
        }

        for (String orphan : referencedByDead) {
            if (!ownClasses.contains(orphan)) continue;          // not this mod's code
            if (referencedByLiving.contains(orphan)) continue;   // still reachable
            String simple = orphan.substring(orphan.lastIndexOf('/') + 1);
            if (deadMixins.contains(simple)) continue;           // a dropped mixin itself
            count(unresolved, "MIXIN_ORPHAN (only a dropped mixin referenced it) " + orphan);
        }
    }

    /** Every class this class file mentions anywhere in its constant pool. */
    private static Set<String> referencedClasses(byte[] classBytes) {
        Set<String> out = new LinkedHashSet<>();
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_FRAMES);
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min) out.add(min.owner);
                else if (insn instanceof TypeInsnNode tin) out.add(tin.desc);
                else if (insn instanceof org.objectweb.asm.tree.FieldInsnNode fin) out.add(fin.owner);
            }
        }
        if (node.superName != null) out.add(node.superName);
        if (node.interfaces != null) out.addAll(node.interfaces);
        return out;
    }

    private static boolean isInterface(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        return (node.access & Opcodes.ACC_INTERFACE) != 0;
    }

    /** Reads the class names a mixin declares in its {@code @Mixin} annotation. */
    private static List<String> mixinTargets(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE);
        return mixinTargets(node);
    }

    private static List<String> mixinTargets(ClassNode node) {
        List<String> targets = new ArrayList<>();

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

    // ---- mixin coordinate repair -------------------------------------------------------

    /**
     * Injector annotations, Mixin's own and MixinExtras'.
     *
     * All of them carry a {@code method} selector naming a method on the mixin's target, and all
     * of them route through {@code InjectionInfo}, which is what makes one soft-fail mechanism
     * work for the whole family.
     */
    private static final Set<String> INJECTOR_ANNOTATIONS = Set.of(
            "Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable", "ModifyConstant",
            "ModifyReturnValue", "ModifyExpressionValue", "WrapOperation", "WrapWithCondition",
            "WrapMethod");

    /**
     * Repairs, or failing that defuses, every mixin coordinate that no longer resolves.
     *
     * <h2>Why this is a separate problem from everything else the transformer does</h2>
     *
     * Ordinary code addresses its targets through the constant pool, so the remapper reaches all of
     * it. Mixins address theirs as <em>text</em>, and a stale string fails at mixin-apply time --
     * which takes down the <em>entire launch</em>, every mod in it, not just the mod that owns the
     * mixin. That is the whole reason a broken mixin was previously handled by deleting the mixin
     * class outright.
     *
     * <h2>Repair before defuse, and defuse before delete</h2>
     *
     * Three things happen here, in descending order of how much of the mod survives:
     *
     * <ol>
     *   <li><b>Renamed types inside the selector.</b> {@code BlockRenderDispatcher.renderBreakingTexture}
     *       takes a Forge {@code ModelData} in 1.20.1 and a NeoForge one in 1.21.1. The transformer
     *       rewrites that type everywhere in bytecode and never touched the string, so the selector
     *       was stale for a reason that has nothing to do with the method changing.</li>
     *   <li><b>Stale descriptors.</b> Where the name is still there and only the descriptor moved,
     *       the platform's own descriptor is the answer -- no rule needed, the same
     *       read-it-off-the-platform property that carried most of Phase 4.</li>
     *   <li><b>{@code require = 0}.</b> What cannot be repaired is made non-fatal <em>per
     *       injector</em> instead of fatal per mixin class. Verified against Mixin 0.8.5:
     *       {@code InjectionInfo.validateTargets} and {@code postInject} both throw only when
     *       {@code requiredCallbackCount > 0}, and {@code parseRequirements} takes an explicit
     *       {@code require} over the config default. So the injector silently does nothing and
     *       every other injector in the same class still applies.</li>
     * </ol>
     *
     * The third is the one that changes the shape of the problem. Dropping a mixin class loses
     * every injector in it and can silently delete registrations when the class was the only path
     * into a static initialiser -- which is exactly how placebo came to load and register nothing.
     * Per-injector granularity makes the loss the size of the actual breakage.
     */
    private void repairMixinCoordinates(ClassNode node) {
        if (targetMembers.isEmpty()) return;
        List<String> judged = new ArrayList<>();
        for (String t : mixinTargets(node)) {
            // Vanilla, NeoForge, and forge-compat's shims -- everything the platform index covers
            // in detail. A mixin into another mod's class cannot be judged here and is left alone,
            // because guessing would defuse working injectors.
            if (targetMembers.containsKey(t)) judged.add(t);
        }
        if (judged.isEmpty()) return;

        for (MethodNode m : new ArrayList<>(node.methods)) {
            for (var ann : mixinAnnotationsOf(m)) {
                String kind = mixinAnnotationName(ann.desc);
                if (kind == null) continue;
                if (kind.equals("Accessor") || kind.equals("Invoker")) {
                    degradeAccessor(node, m, ann, judged, kind);
                } else if (kind.equals("Shadow")) {
                    degradeShadowMethod(node, m, judged);
                } else if (INJECTOR_ANNOTATIONS.contains(kind)) {
                    repairInjector(node, m, ann, judged, kind);
                }
            }
        }
        for (var f : node.fields) degradeShadowField(node, f, judged);
    }

    /**
     * A {@code @Shadow} field the target no longer declares with that name and descriptor.
     *
     * Shadows are resolved by name <em>and</em> descriptor, so a field whose type merely changed is
     * as unresolvable as one that was deleted, and both abort the launch. curios dies on
     * {@code ApplyBonusCount.enchantment}, which is still there and is now a
     * {@code Holder&lt;Enchantment&gt;}.
     *
     * <h2>Why the annotation is removed rather than the descriptor repaired</h2>
     *
     * Repairing the descriptor is the obvious move and produces a {@code VerifyError}: the mixin's
     * own code was compiled against the old type and goes on calling {@code Enchantment} methods on
     * what is now a {@code Holder}. Removing {@code @Shadow} instead turns the field into one the
     * mixin <em>adds</em> to the target class. Every use of it inside the mixin stays type-correct,
     * the class verifies, and what is lost is precisely the link to vanilla's own field.
     *
     * That trade is the standing one, and it is a large improvement on what it replaces: the
     * previous behaviour deleted the whole mixin class, losing every injector in it as well.
     */
    private void degradeShadowField(ClassNode node, org.objectweb.asm.tree.FieldNode f,
                                    List<String> targets) {
        // An interface's fields must stay public static final, so there is no version of this a
        // mixin interface can carry. @Shadow fields there are vanishingly rare -- an interface
        // cannot shadow an instance field -- and clearing ACC_FINAL would emit an invalid class.
        if ((node.access & Opcodes.ACC_INTERFACE) != 0) return;
        boolean shadowed = false;
        for (var ann : fieldAnnotationsOf(f)) {
            if ("Shadow".equals(mixinAnnotationName(ann.desc))) { shadowed = true; break; }
        }
        if (!shadowed) return;

        for (String target : targets) {
            if (resolvedTargetMembers(target).contains(f.name + " " + f.desc)) return;
        }

        if (f.visibleAnnotations != null) {
            f.visibleAnnotations.removeIf(a -> "Shadow".equals(mixinAnnotationName(a.desc)));
        }
        if (f.invisibleAnnotations != null) {
            f.invisibleAnnotations.removeIf(a -> "Shadow".equals(mixinAnnotationName(a.desc)));
        }
        // @Final tells Mixin the shadowed field is final in the target. With nothing shadowed it
        // describes nothing, and Mixin rejects it on a field it did not resolve.
        removeFieldAnnotation(f, "Final");
        // A mixin-added field cannot be final: nothing in the mixin assigns it, and the target's
        // constructors know nothing about it.
        f.access &= ~Opcodes.ACC_FINAL;
        count(unresolved, "MIXIN_SHADOW_FIELD_STUB (no longer on the target; became a mixin-added "
                        + "field) " + node.name + "." + f.name);
    }

    /** The {@code @Shadow} method equivalent: given an inert body instead of vanilla's. */
    private void degradeShadowMethod(ClassNode node, MethodNode m, List<String> targets) {
        for (String target : targets) {
            if (resolvedTargetMembers(target).contains(m.name + " " + m.desc)) return;
        }
        removeMixinAnnotation(m, "Shadow");
        removeMixinAnnotation(m, "Final");
        m.access &= ~Opcodes.ACC_ABSTRACT;
        keepAccessorVariant(node, m);
        // A shadow of a concrete method conventionally carries a body that throws, on the grounds
        // that it can never run. Once it is a real merged method it can, so it is replaced rather
        // than kept.
        inertBody(m);
        count(unresolved, "MIXIN_SHADOW_METHOD_STUB (no longer on the target) "
                        + node.name + "." + m.name + m.desc);
    }

    private static List<org.objectweb.asm.tree.AnnotationNode> fieldAnnotationsOf(
            org.objectweb.asm.tree.FieldNode f) {
        List<org.objectweb.asm.tree.AnnotationNode> all = new ArrayList<>();
        if (f.visibleAnnotations != null) all.addAll(f.visibleAnnotations);
        if (f.invisibleAnnotations != null) all.addAll(f.invisibleAnnotations);
        return all;
    }

    private static void removeFieldAnnotation(org.objectweb.asm.tree.FieldNode f, String simpleName) {
        if (f.visibleAnnotations != null) {
            f.visibleAnnotations.removeIf(a -> simpleName.equals(mixinAnnotationName(a.desc)));
        }
        if (f.invisibleAnnotations != null) {
            f.invisibleAnnotations.removeIf(a -> simpleName.equals(mixinAnnotationName(a.desc)));
        }
    }

    private void repairInjector(ClassNode node, MethodNode m, org.objectweb.asm.tree.AnnotationNode ann,
                                List<String> targets, String kind) {
        boolean resolved = true;

        Object raw = annotationValue(ann, "method");
        List<String> selectors = new ArrayList<>();
        if (raw instanceof String s) selectors.add(s);
        else if (raw instanceof List<?> list) {
            for (Object o : list) if (o instanceof String s) selectors.add(s);
        }
        if (!selectors.isEmpty()) {
            List<String> rebuilt = new ArrayList<>();
            for (String sel : selectors) {
                // A handler is passed only for @Inject, where the handler's own parameters are
                // constrained by the target's descriptor. For every other injector the handler
                // matches the *redirected call*, not the enclosing method, so retargeting the
                // enclosing method cannot invalidate it.
                String fixed = repairSelector(sel, targets, kind.equals("Inject") ? m : null, kind);
                if (fixed == null) { resolved = false; break; }
                rebuilt.add(fixed);
            }
            if (resolved) setAnnotationValue(ann, "method", raw instanceof List ? rebuilt : rebuilt.get(0));
        }

        if (resolved) {
            // An @At anchor on @Inject only locates a position, so rewriting it cannot break the
            // handler contract. On @Redirect and friends the anchor *is* what the handler matches,
            // so it is checked and never rewritten -- repairing it there would trade a missing
            // injection point for a handler mismatch, which fails just as hard and reads worse.
            boolean mayRewrite = kind.equals("Inject");
            for (var at : atAnnotations(ann)) {
                if (!repairAtTarget(at, mayRewrite)) { resolved = false; break; }
            }
        }

        if (resolved) {
            // The last question, and the one the roadmap called the hard part: the anchor names a
            // member that still exists, and the method being patched no longer calls it.
            Set<String> selected = new LinkedHashSet<>();
            for (String sel : selectorsOf(ann)) selected.addAll(resolveTargetMethods(sel, targets));
            for (var at : atAnnotations(ann)) {
                if (!anchorReachable(at, selected)) { resolved = false; break; }
            }
        }

        if (!resolved) softFail(m, ann, kind, node.name);
    }

    /**
     * Makes one injector non-fatal, leaving the rest of its mixin class intact.
     *
     * {@code @Group} is stripped alongside. A named injector group carries its own {@code min}
     * check in {@code InjectorGroupInfo.validate}, which {@code require} does not reach -- so a
     * defused injector still in its group would fail the group instead, and the soft-fail would
     * look applied while changing nothing. Removing it from the group leaves the group to be
     * satisfied by its other members, or never registered at all if it had none.
     */
    private void softFail(MethodNode m, org.objectweb.asm.tree.AnnotationNode ann, String kind,
                          String owner) {
        setAnnotationValue(ann, "require", Integer.valueOf(0));
        // expect only fires under -Dmixin.debug.injectors, but leaving it set turns a dev-time run
        // into a different failure from a normal one, which is a bad way to find things out.
        setAnnotationValue(ann, "expect", Integer.valueOf(0));
        removeMixinAnnotation(m, "Group");
        count(unresolved, "MIXIN_SOFT_FAIL (" + kind + " no longer resolves; injector disabled, "
                        + "mixin kept) " + owner + "." + m.name);
    }

    /**
     * A method selector, repaired against the platform or reported unrepairable.
     *
     * Returns the selector to write back, or null when nothing can be done with it.
     */
    private String repairSelector(String selector, List<String> targets, MethodNode injectHandler,
                                  String kind) {
        String spec = renameTypesInText(selector);
        // Wildcards and Mixin's dynamic selectors are matched at apply time against things this
        // has no view of. Left exactly as written.
        if (spec.isEmpty() || spec.contains("*") || spec.startsWith("@")) return spec;

        String owner = selectorOwner(spec);
        List<String> owners = owner != null ? List.of(owner) : targets;
        if (owner != null && !targetMembers.containsKey(owner)) return spec;

        String name = selectorName(spec);
        String desc = selectorDesc(spec);

        Set<String> candidates = new java.util.TreeSet<>();
        for (String o : owners) {
            // Constructors are not inherited (gotcha #14): resolving one through the hierarchy
            // reports a removed constructor as present whenever a supertype declares its shape.
            Set<String> members = name.equals("<init>") ? targetMembers.getOrDefault(o, Set.of())
                                                        : resolvedTargetMembers(o);
            for (String member : members) {
                if (member.startsWith(name + " ")) candidates.add(member.substring(name.length() + 1));
            }
        }
        if (candidates.isEmpty()) return null;
        if (desc == null || candidates.contains(desc)) return spec;

        String replacement = chooseDescriptor(desc, candidates);
        if (replacement == null) return null;
        if (injectHandler != null && !handlerStillMatches(injectHandler, replacement)) return null;

        count(appliedCounts, "MIXIN_SELECTOR_REPAIR " + kind);
        return rebuildSelector(spec, name, replacement);
    }

    /**
     * An {@code @At} anchor's member reference.
     *
     * @param mayRewrite whether a stale descriptor may be replaced, or only detected.
     * @return whether the anchor resolves after whatever was done to it.
     */
    private boolean repairAtTarget(org.objectweb.asm.tree.AnnotationNode at, boolean mayRewrite) {
        Object raw = annotationValue(at, "target");
        if (!(raw instanceof String s) || s.isBlank()) return true;   // HEAD, RETURN, TAIL
        String spec = renameTypesInText(s);
        if (spec.contains("*")) { setAnnotationValue(at, "target", spec); return true; }

        String owner = selectorOwner(spec);
        // An anchor into a method Forge itself patched names a Forge type -- ShearsItem's
        // interactLivingEntity calls IForgeShearable.onSheared in 1.20.1 and IShearable.onSheared
        // in 1.21.1. Renaming the owner is what saves that injector; leaving it defuses one that
        // would have worked. These are always written `owner.name(desc)ret` with remap = false,
        // which is why the bare selector form had to be parsed before this was reachable at all.
        if (owner != null && !owner.startsWith("net/minecraft/")) {
            String renamed = typeRenames.get(owner);
            if (renamed != null && renameTargetExists(renamed)) {
                count(appliedCounts, "MIXIN_AT_OWNER_RENAME " + owner);
                spec = withOwner(spec, renamed);
                owner = renamed;
            }
        }
        if (owner == null || !targetMembers.containsKey(owner)) {
            setAnnotationValue(at, "target", spec);
            return true;                                   // not judgeable; leave it alone
        }

        String rest = afterOwner(spec);
        // @At("NEW") names only the type being constructed -- "Lnet/minecraft/Foo;" with nothing
        // after it. Read as a member reference that yields the empty name, which resolves to
        // nothing and defuses the injector: a silent false loss on an anchor that is perfectly
        // fine, since the class is right there in the index.
        if (rest.isEmpty()) { setAnnotationValue(at, "target", spec); return true; }
        boolean field = rest.contains(":") && !rest.contains("(");
        String name = field ? rest.substring(0, rest.indexOf(':')) : selectorName(spec);
        String desc = field ? rest.substring(rest.indexOf(':') + 1) : selectorDesc(spec);
        if (name.isEmpty()) { setAnnotationValue(at, "target", spec); return true; }

        Set<String> members = name.equals("<init>") ? targetMembers.getOrDefault(owner, Set.of())
                                                    : resolvedTargetMembers(owner);
        Set<String> candidates = new java.util.TreeSet<>();
        for (String member : members) {
            if (member.startsWith(name + " ")) candidates.add(member.substring(name.length() + 1));
        }
        if (candidates.isEmpty()) return false;
        if (desc == null || desc.isEmpty() || candidates.contains(desc)) {
            setAnnotationValue(at, "target", spec);
            return true;
        }
        if (!mayRewrite) return false;

        String replacement = chooseDescriptor(desc, candidates);
        if (replacement == null) return false;
        count(appliedCounts, "MIXIN_AT_REPAIR");
        setAnnotationValue(at, "target", field ? ownerPrefix(spec) + name + ":" + replacement
                                               : rebuildSelector(spec, name, replacement));
        return true;
    }

    /** The method selectors an injector declares, normalised the same way the repair pass does. */
    private List<String> selectorsOf(org.objectweb.asm.tree.AnnotationNode ann) {
        Object raw = annotationValue(ann, "method");
        List<String> out = new ArrayList<>();
        if (raw instanceof String s) out.add(renameTypesInText(s));
        else if (raw instanceof List<?> list) {
            for (Object o : list) if (o instanceof String s) out.add(renameTypesInText(s));
        }
        return out;
    }

    /** The concrete platform methods a selector picks out, as {@code owner\tname desc}. */
    private List<String> resolveTargetMethods(String selector, List<String> targets) {
        List<String> out = new ArrayList<>();
        if (selector.isEmpty() || selector.contains("*") || selector.startsWith("@")) return out;
        String owner = selectorOwner(selector);
        List<String> owners = owner != null ? List.of(owner) : targets;
        String name = selectorName(selector);
        String desc = selectorDesc(selector);
        for (String o : owners) {
            if (!targetMembers.containsKey(o)) continue;
            for (String member : targetMembers.getOrDefault(o, Set.of())) {
                if (!member.startsWith(name + " ")) continue;
                // A selector with no descriptor picks every overload, exactly as Mixin does.
                if (desc != null && !member.endsWith(" " + desc)) continue;
                out.add(o + "\t" + member);
            }
        }
        return out;
    }

    /**
     * Whether an anchor can still find anything inside the methods it will be searched in.
     *
     * This is the question the roadmap called the hard part of Phase 5, and every check before it
     * misses the case entirely: the anchor names {@code Level.getBlockState}, that method exists,
     * and the method being patched simply stopped calling it. Mixin resolves the target method,
     * scans its instructions for the anchor, finds nothing, and throws
     * {@code Critical injection failure} -- taking the launch with it.
     *
     * <h2>Why this can be answered offline</h2>
     *
     * The platform jar has the bodies. Mixin scans exactly the resolved target method and does not
     * descend into lambdas, so reading that one method's instruction list answers the same question
     * Mixin will ask, before the launch rather than during it.
     *
     * <h2>Conservative in one direction only</h2>
     *
     * An anchor is declared unreachable only when the body was read and does not contain it.
     * Anything unread -- a class outside the platform jars, a selector that matched no method,
     * an anchor shape not modelled here -- counts as reachable, which leaves behaviour alone. The
     * cost of a false negative is a launch failure that was already happening; the cost of a false
     * positive is silently deleting a working injector, which is far worse.
     */
    private boolean anchorReachable(org.objectweb.asm.tree.AnnotationNode at, Set<String> selected) {
        if (selected.isEmpty()) return true;
        Object raw = annotationValue(at, "target");
        if (!(raw instanceof String spec) || spec.isBlank() || spec.contains("*")) return true;

        String kind = annotationValue(at, "value") instanceof String s ? s : "";
        char want;
        switch (kind) {
            case "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING" -> want = 'M';
            case "FIELD" -> want = 'F';
            case "NEW" -> want = 'N';
            // HEAD, RETURN, TAIL, JUMP, CONSTANT and anything a mod registered itself are located
            // by something other than a member reference, so a member scan says nothing about them.
            default -> { return true; }
        }

        String owner = selectorOwner(spec);
        String rest = afterOwner(spec);
        if (want == 'N') {
            // NEW may name the type alone or a factory-style descriptor returning it.
            String type = owner != null && rest.isEmpty() ? owner
                        : Type.getReturnType(rest.isEmpty() ? spec : rest).getInternalName();
            return anyBodyContains(selected, "N " + type, null, null);
        }
        boolean field = want == 'F';
        String name = field && rest.contains(":") ? rest.substring(0, rest.indexOf(':'))
                                                  : selectorName(spec);
        String desc = field ? (rest.contains(":") ? rest.substring(rest.indexOf(':') + 1) : null)
                            : selectorDesc(spec);
        if (name.isEmpty()) return true;
        return anyBodyContains(selected, String.valueOf(want), owner, name + (desc == null ? "" : desc));
    }

    /**
     * @param prefix  "M", "F" or "N" -- the reference kind recorded by {@link #bodyRefsOf}.
     * @param owner   the owner the anchor constrains, or null for any.
     * @param member  name and descriptor concatenated, or a bare name when none was given.
     */
    private boolean anyBodyContains(Set<String> selected, String prefix, String owner, String member) {
        boolean anyKnown = false;
        for (String key : selected) {
            int tab = key.indexOf('\t');
            Map<String, Set<String>> bodies = bodyRefsOf(key.substring(0, tab));
            Set<String> refs = bodies.get(key.substring(tab + 1));
            if (refs == null) continue;               // body not readable; says nothing either way
            anyKnown = true;
            for (String ref : refs) {
                if (!ref.startsWith(prefix + " ")) continue;
                String body = ref.substring(prefix.length() + 1);
                if (member == null) {
                    if (body.equals(owner)) return true;
                    continue;
                }
                int dot = body.indexOf('.');
                String refOwner = dot < 0 ? body : body.substring(0, dot);
                String refMember = dot < 0 ? "" : body.substring(dot + 1);
                if (owner != null && !owner.equals(refOwner)) continue;
                if (refMember.startsWith(member)) return true;
            }
        }
        return !anyKnown;
    }

    /**
     * What each method of a platform class references, read from its body on demand.
     *
     * The main index is built with {@code SKIP_CODE}, which is right for every other check here and
     * useless for this one. Reading bodies for the whole platform would triple a scan that runs
     * once per mod; a mod has a handful of mixin targets, so they are read lazily and cached.
     */
    private Map<String, Set<String>> bodyRefsOf(String cls) {
        Map<String, Set<String>> hit = targetBodyRefs.get(cls);
        if (hit != null) return hit;
        Map<String, Set<String>> out = new HashMap<>();
        if (platformJarPaths != null) {
            for (String j : platformJarPaths) {
                Path p = Paths.get(j);
                if (!Files.isRegularFile(p)) continue;
                try (ZipFile zip = new ZipFile(p.toFile())) {
                    ZipEntry e = zip.getEntry(cls + ".class");
                    if (e == null) continue;
                    ClassNode node = new ClassNode();
                    new ClassReader(read(zip, e)).accept(node, ClassReader.SKIP_DEBUG);
                    for (MethodNode m : node.methods) {
                        Set<String> refs = new HashSet<>();
                        for (AbstractInsnNode insn : m.instructions.toArray()) {
                            if (insn instanceof MethodInsnNode min) {
                                refs.add("M " + min.owner + "." + min.name + min.desc);
                            } else if (insn instanceof org.objectweb.asm.tree.FieldInsnNode fin) {
                                refs.add("F " + fin.owner + "." + fin.name + fin.desc);
                            } else if (insn instanceof TypeInsnNode tin
                                    && tin.getOpcode() == Opcodes.NEW) {
                                refs.add("N " + tin.desc);
                            }
                        }
                        out.put(m.name + " " + m.desc, refs);
                    }
                    break;
                } catch (Exception ignored) {
                    // Unreadable: an empty map, which anyBodyContains treats as "cannot tell".
                }
            }
        }
        targetBodyRefs.put(cls, out);
        return out;
    }

    /**
     * Which of the platform's descriptors a stale one meant.
     *
     * Exactly one candidate is unambiguous. Beyond that only a same-arity match is accepted, and
     * only when it is the single one -- the arity-widening lesson from Phase 4 was that a broader
     * match can silently take away a match that worked, so this refuses rather than guesses.
     */
    private static String chooseDescriptor(String stale, Set<String> candidates) {
        if (candidates.size() == 1) return candidates.iterator().next();
        int arity = Type.getArgumentTypes(stale).length;
        String only = null;
        for (String c : candidates) {
            if (Type.getArgumentTypes(c).length != arity) continue;
            if (only != null) return null;
            only = c;
        }
        return only;
    }

    /**
     * Whether an {@code @Inject} handler is still valid for a retargeted method.
     *
     * A handler that takes only its {@code CallbackInfo} is valid against any target. One that
     * captures target arguments declares them ahead of the callback, and they have to keep
     * matching the target's parameters -- otherwise repairing the selector would trade a missing
     * target for {@code InvalidInjectionException} on the handler, which is no better.
     */
    private static boolean handlerStillMatches(MethodNode handler, String newTargetDesc) {
        Type[] params = Type.getArgumentTypes(handler.desc);
        int callback = -1;
        for (int i = 0; i < params.length; i++) {
            String n = params[i].getInternalName().isEmpty() ? "" : params[i].getInternalName();
            if (n.endsWith("injection/callback/CallbackInfo")
                    || n.endsWith("injection/callback/CallbackInfoReturnable")) {
                callback = i;
                break;
            }
        }
        if (callback <= 0) return true;                    // captures nothing before the callback
        Type[] target = Type.getArgumentTypes(newTargetDesc);
        if (callback > target.length) return false;
        for (int i = 0; i < callback; i++) {
            if (!params[i].equals(target[i])) return false;
        }
        return true;
    }

    /**
     * An {@code @Accessor} or {@code @Invoker} onto a member 1.21 no longer has.
     *
     * These cannot be soft-failed: Mixin throws {@code InvalidAccessorException} while generating
     * the accessor, before any {@code require} is consulted, and that aborts the launch. Until now
     * they were not detected at all, which is why yungsapi died on
     * {@code CriteriaTriggers.CRITERIA} after translating cleanly.
     *
     * So the annotation is removed and the method given an inert body. It is the same trade every
     * placeholder in this project makes, and always the same way round: the mod loads, everything
     * else it does still works, and the one thing that read a deleted field returns a default.
     * Named in the report rather than done quietly.
     */
    private void degradeAccessor(ClassNode node, MethodNode m, org.objectweb.asm.tree.AnnotationNode ann,
                                 List<String> targets, String kind) {
        String declared = annotationValue(ann, "value") instanceof String s ? s : null;
        String name = declared != null ? selectorName(declared) : inferAccessorName(m.name);
        if (name == null) return;                         // cannot tell what it addresses

        boolean invoker = kind.equals("Invoker");
        boolean ctor = name.equals("<init>") || invoker && declared == null && m.name.startsWith("new");
        String wanted = invoker ? m.desc : accessorFieldDesc(m);
        if (wanted == null) return;

        for (String target : targets) {
            Set<String> members = ctor ? targetMembers.getOrDefault(target, Set.of())
                                       : resolvedTargetMembers(target);
            for (String member : members) {
                if (!member.startsWith(name + " ")) continue;
                String desc = member.substring(name.length() + 1);
                // A factory invoker's descriptor returns the constructed type where the constructor
                // returns void, so only the arguments can be compared.
                if (ctor ? sameArguments(desc, wanted) : desc.equals(wanted)) return;
            }
        }

        removeMixinAnnotation(m, kind);
        m.access &= ~Opcodes.ACC_ABSTRACT;
        keepAccessorVariant(node, m);
        inertBody(m);
        count(unresolved, "MIXIN_ACCESSOR_STUB (" + kind + " target gone) "
                        + node.name + "." + m.name + " -> " + name);
    }

    /**
     * Keeps an interface mixin classified as an <em>accessor</em> mixin after a member is degraded.
     *
     * Mixin decides the variant from the mixin class itself, in {@code MixinInfo.getVariant}: an
     * interface is an ACCESSOR mixin only while every method it declares is an accessor or
     * synthetic, and an INTERFACE mixin otherwise -- which may only target an interface. So merely
     * removing an {@code @Accessor} annotation reclassifies the whole mixin and turns
     * {@code InvalidAccessorException} into
     * {@code @Mixin target type mismatch: ... is not an interface}, which is no improvement at all.
     * yungsapi failed exactly that way the first time this ran.
     *
     * Marking the degraded method synthetic keeps the variant. It stays a perfectly ordinary
     * default method at the JVM level -- {@code ACC_SYNTHETIC} means something to javac and nothing
     * to method resolution -- so callers still link and get the inert value.
     */
    private static void keepAccessorVariant(ClassNode node, MethodNode m) {
        if ((node.access & Opcodes.ACC_INTERFACE) != 0) m.access |= Opcodes.ACC_SYNTHETIC;
    }

    /** Replaces a method's body with the cheapest legal return for its descriptor. */
    private static void inertBody(MethodNode m) {
        if (m.instructions == null) m.instructions = new InsnList();
        m.instructions.clear();
        if (m.tryCatchBlocks != null) m.tryCatchBlocks.clear();
        if (m.localVariables != null) m.localVariables.clear();
        Type ret = Type.getReturnType(m.desc);
        if (ret.getSort() == Type.VOID) {
            m.instructions.add(new InsnNode(Opcodes.RETURN));
        } else if (ret.getSort() == Type.OBJECT || ret.getSort() == Type.ARRAY) {
            m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            m.instructions.add(new InsnNode(Opcodes.ARETURN));
        } else {
            m.instructions.add(new InsnNode(
                    ret.getSort() == Type.LONG ? Opcodes.LCONST_0
                  : ret.getSort() == Type.FLOAT ? Opcodes.FCONST_0
                  : ret.getSort() == Type.DOUBLE ? Opcodes.DCONST_0
                  : Opcodes.ICONST_0));
            m.instructions.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
        }
        m.maxStack = Math.max(m.maxStack, 2);
        m.maxLocals = Math.max(m.maxLocals, Type.getArgumentsAndReturnSizes(m.desc) >> 2);
    }

    /** The field descriptor an accessor implies: its return type, or a setter's one parameter. */
    private static String accessorFieldDesc(MethodNode m) {
        Type[] params = Type.getArgumentTypes(m.desc);
        Type ret = Type.getReturnType(m.desc);
        if (params.length == 0 && ret.getSort() != Type.VOID) return ret.getDescriptor();
        if (params.length == 1 && ret.getSort() == Type.VOID) return params[0].getDescriptor();
        // A mutable accessor returning `this` for chaining, and anything else unusual.
        return params.length == 1 ? params[0].getDescriptor() : null;
    }

    private static boolean sameArguments(String a, String b) {
        return Type.getMethodDescriptor(Type.VOID_TYPE, Type.getArgumentTypes(a))
                .equals(Type.getMethodDescriptor(Type.VOID_TYPE, Type.getArgumentTypes(b)));
    }

    /**
     * Mixin's own accessor-name inference, for the common case of an omitted value.
     *
     * Reproduced rather than approximated: a wrong guess here invents a missing member on a
     * working accessor and would stub it out for nothing.
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

    // ---- selector and annotation plumbing ----------------------------------------------

    /** {@code Lsome/Type;} sequences rewritten by the rule set, where that is safe. */
    private String renameTypesInText(String text) {
        if (text == null || text.indexOf('L') < 0) return text;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            int semi;
            if (ch == 'L' && (semi = text.indexOf(';', i)) > i + 1) {
                String type = text.substring(i + 1, semi);
                // Never net/minecraft: those renames point at relocated stand-ins, which are real
                // classes for ordinary code and meaningless as mixin coordinates -- a mixin is
                // applied to the class the game actually loads.
                String renamed = type.startsWith("net/minecraft/") ? null : typeRenames.get(type);
                sb.append('L')
                  .append(renamed != null && renameTargetExists(renamed) ? renamed : type)
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
     * The owner prefix as the selector spelled it, so a rebuilt selector keeps the same form.
     *
     * Mixin accepts two: {@code Lnet/minecraft/Foo;bar()V} and {@code net/minecraft/Foo.bar()V}.
     * Handling only the first left the second unjudged everywhere -- 178 coordinates corpus-wide,
     * and they are not a random sample: the bare form is what mods write for {@code remap = false}
     * anchors, which is exactly where a Forge type needs renaming to its NeoForge counterpart.
     */
    /** The part of a selector after its owner, in either spelling. */
    private static String afterOwner(String selector) {
        int semi = selector.indexOf(';');
        if (selector.startsWith("L") && semi > 0) return selector.substring(semi + 1);
        String owner = selectorOwner(selector);
        return owner == null ? selector : selector.substring(owner.length() + 1);
    }

    /** The same selector with a different owner, keeping whichever spelling it used. */
    private static String withOwner(String selector, String newOwner) {
        int semi = selector.indexOf(';');
        if (selector.startsWith("L") && semi > 0) return "L" + newOwner + ";" + afterOwner(selector);
        return newOwner + "." + afterOwner(selector);
    }

    private static String ownerPrefix(String selector) {
        int semi = selector.indexOf(';');
        if (selector.startsWith("L") && semi > 0) return selector.substring(0, semi + 1);
        String bare = selectorOwner(selector);
        return bare == null ? "" : bare + ".";
    }

    /** The owner an explicit selector names, or null when it addresses the mixin's own target. */
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
        // A '/' is what tells an owner apart from a bare member name that happens to contain a
        // dot. Mixin writes packages with slashes in this form for exactly that reason.
        return candidate.indexOf('/') >= 0 ? candidate : null;
    }

    private static String selectorName(String selector) {
        String s = selector;
        int semi = s.indexOf(';');
        if (s.startsWith("L") && semi > 0) {
            s = s.substring(semi + 1);
        } else if (selectorOwner(selector) != null) {
            int paren = s.indexOf('(');
            String head = paren < 0 ? s : s.substring(0, paren);
            s = s.substring(head.lastIndexOf('.') + 1);
        }
        int paren = s.indexOf('(');
        String head = paren < 0 ? s : s.substring(0, paren);
        int colon = head.indexOf(':');
        if (colon >= 0) head = head.substring(0, colon);
        return head.trim();
    }

    private static String selectorDesc(String selector) {
        int paren = selector.indexOf('(');
        return paren < 0 ? null : selector.substring(paren).trim();
    }

    private static String rebuildSelector(String original, String name, String desc) {
        return ownerPrefix(original) + name + desc;
    }

    private static List<org.objectweb.asm.tree.AnnotationNode> mixinAnnotationsOf(MethodNode m) {
        List<org.objectweb.asm.tree.AnnotationNode> all = new ArrayList<>();
        if (m.visibleAnnotations != null) all.addAll(m.visibleAnnotations);
        // Mixin annotations are CLASS-retention, so they land in the invisible list.
        if (m.invisibleAnnotations != null) all.addAll(m.invisibleAnnotations);
        return all;
    }

    /** The simple name of a Mixin or MixinExtras annotation, or null for anything else. */
    private static String mixinAnnotationName(String desc) {
        if (desc == null || !desc.startsWith("L") || !desc.endsWith(";")) return null;
        String internal = desc.substring(1, desc.length() - 1);
        if (!internal.startsWith("org/spongepowered/") && !internal.startsWith("com/llamalad7/")) {
            return null;
        }
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    private static void removeMixinAnnotation(MethodNode m, String simpleName) {
        if (m.visibleAnnotations != null) {
            m.visibleAnnotations.removeIf(a -> simpleName.equals(mixinAnnotationName(a.desc)));
        }
        if (m.invisibleAnnotations != null) {
            m.invisibleAnnotations.removeIf(a -> simpleName.equals(mixinAnnotationName(a.desc)));
        }
    }

    private static Object annotationValue(org.objectweb.asm.tree.AnnotationNode ann, String key) {
        if (ann.values == null) return null;
        for (int i = 0; i + 1 < ann.values.size(); i += 2) {
            if (key.equals(ann.values.get(i))) return ann.values.get(i + 1);
        }
        return null;
    }

    private static void setAnnotationValue(org.objectweb.asm.tree.AnnotationNode ann, String key,
                                           Object value) {
        if (ann.values == null) ann.values = new ArrayList<>();
        for (int i = 0; i + 1 < ann.values.size(); i += 2) {
            if (key.equals(ann.values.get(i))) { ann.values.set(i + 1, value); return; }
        }
        ann.values.add(key);
        ann.values.add(value);
    }

    /** Every {@code @At} an injector carries, including the ones nested inside a {@code @Slice}. */
    private static List<org.objectweb.asm.tree.AnnotationNode> atAnnotations(
            org.objectweb.asm.tree.AnnotationNode injector) {
        List<org.objectweb.asm.tree.AnnotationNode> out = new ArrayList<>();
        for (String key : new String[] {"at", "ats", "slice", "constant"}) {
            Object v = annotationValue(injector, key);
            collectAts(v, out);
        }
        return out;
    }

    private static void collectAts(Object value, List<org.objectweb.asm.tree.AnnotationNode> out) {
        if (value instanceof org.objectweb.asm.tree.AnnotationNode an) {
            String name = mixinAnnotationName(an.desc);
            if ("At".equals(name)) {
                out.add(an);
            } else if ("Slice".equals(name)) {
                // A slice bounds the search region with its own anchors, and a stale one fails
                // exactly as loudly as the injection point it was meant to narrow.
                collectAts(annotationValue(an, "from"), out);
                collectAts(annotationValue(an, "to"), out);
            }
        } else if (value instanceof List<?> list) {
            for (Object o : list) collectAts(o, out);
        }
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
                    // Optional 5th field: the class the factory lives on, when it is not the
                    // constructor's own class.
                    if (c.length >= 5) {
                        ctorRules.add(new CtorRule(c[1], c[2], c[3], c[4],
                                                   c.length > 5 ? c[5] : c[1]));
                    }
                }
                case "RENAME_METHOD" -> {
                    if (c.length >= 7) renameRules.add(new RenameRule(c[1], c[2], c[3], c[4], c[5], c[6]));
                }
                case "FIELD_TO_STATIC" -> {
                    if (c.length >= 7) fieldToStaticRules.add(new RenameRule(c[1], c[2], c[3], c[4], c[5], c[6]));
                }
                case "FIELD_RETYPE" -> {
                    if (c.length >= 3) fieldRetypes.put(c[1], c[2]);
                }
                case "METHOD_TO_STATIC" -> {
                    if (c.length >= 7) methodToStaticRules.add(new RenameRule(c[1], c[2], c[3], c[4], c[5], c[6]));
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
                case "ARG_COLLAPSE" -> {
                    if (c.length >= 7) {
                        collapseRules.add(new CollapseRule(c[1], c[2], c[3], c[4], c[5], c[6]));
                    }
                }
                case "ARG_FILL" -> {
                    // type <TAB> bridgeOwner <TAB> bridgeName; the descriptor follows from the
                    // type, so writing it out would only be a chance to write it differently.
                    if (c.length >= 4) {
                        argFillers.put(c[1], new RenameRule(c[1], null, null,
                                c[2], c[3], "()L" + c[1] + ";"));
                    }
                }
                case "INTERFACE_SUBSTITUTE" -> {
                    if (c.length >= 3) interfaceSubstitutes.put(c[1], c[2]);
                }
                case "COERCE" -> {
                    // from <TAB> to <TAB> bridgeOwner <TAB> bridgeName. The bridge's descriptor
                    // follows from the pair, so writing it in the rule would only be a chance to
                    // write it differently to the method it names.
                    if (c.length >= 5) {
                        coercions.put(c[1] + "\t" + c[2], new RenameRule(c[1], null, null,
                                c[3], c[4], "(L" + c[1] + ";)L" + c[2] + ";"));
                    }
                }
                default -> System.err.println("  unknown rule kind, ignored: " + c[0]);
            }
        }
    }

    private static byte[] read(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) { return in.readAllBytes(); }
    }
}
